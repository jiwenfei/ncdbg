package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.PrintMessage
import com.sun.jdi.{ArrayReference, ClassType, ThreadReference, Value}
import com.sun.jdi.event.{BreakpointEvent, Event}
import org.slf4s.Logging

object PrintSupport {
  val JSType_toString_signature = "toString(Ljava/lang/Object;)Ljava/lang/String;"
  val Object_toString_signature = "toString()Ljava/lang/String;"
}

/**
  * Responsible for capturing Nashorns 'print' and 'println' extensions, and converting them into [[PrintMessage]]
  * events.
  */
trait PrintSupport { self: NashornDebuggerHost with Logging =>

  import scala.collection.JavaConverters._
  import TypeConstants._
  import PrintSupport._
  import com.programmaticallyspeaking.ncd.nashorn.mirrors.Mirrors._

  def enablePrintCapture(global: ClassType): Unit = {
    Breakpoints.enableBreakingAtGlobalPrint(global) { breakpointRequests =>
      breakpointRequests.foreach(beforeEventIsHandled(_)(eventHandler))
    }
  }

  private def eventHandler(ev: Event, maybePausedData: Option[PausedData]): Boolean = {
    ev match {
      case b: BreakpointEvent if maybePausedData.isDefined =>
        val pausedData = maybePausedData.get
        implicit val marshaller: Marshaller = pausedData.marshaller
        implicit val thread: ThreadReference = pausedData.thread

        // Try to use JSType.toString()
        val jsType = foundWantedTypes.get(NIR_JSType)
        val jsTypeInvoker = jsType.map(Invokers.shared.getStatic)
        def valueToString(v: Value): String = {
          //TODO: What's a good fallback here?
          jsTypeInvoker.map(_.applyDynamic(JSType_toString_signature)(v).asString).getOrElse("???")
        }

        // Signature is:
        // public static Object print(final Object self, final Object... objects) {
        // So grab the arguments and skip the first. What we have then should be an array!
        val argStrings = b.location().method().arguments().asScala.tail.headOption.map(thread.frame(0).getValue) match {
          case Some(arr: ArrayReference) =>
            arr.getValues.asScala.map(valueToString)
          case _ => Seq.empty
        }

        emitEvent(PrintMessage(argStrings.mkString(" ")))

      case other =>
        log.warn("Unexpected event: " + other)
    }
    // Consume the event!
    true
  }

}