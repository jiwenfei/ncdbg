package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.ValueNode
import com.programmaticallyspeaking.ncd.nashorn.mirrors.ScriptObjectMirror
import com.sun.jdi.ThreadReference

/**
  * Wraps a [[ScriptObjectMirror]] instance to do marshalling and "unpacking" of data.
  *
  * @param mirror the mirror instance that is responsible for `ScriptObject` interaction
  * @param thread the thread on which to execute methods (for further unpacking of data)
  * @param marshaller marshaller for marshalling JDI `Value` into [[ValueNode]]
  */
class ScriptObjectProxy(val mirror: ScriptObjectMirror, thread: ThreadReference, marshaller: Marshaller) {
  val scriptObject = mirror.scriptObject

  lazy val className = marshaller.marshalledAs[String](mirror.getClassName)

  lazy val isArray = marshaller.marshalledAs[Boolean](mirror.isArray)

  def isFunction = className == "Function"
  def isError = className == "Error"
  def isDate = className == "Date"
  def isRegExp = className == "RegExp"
}
