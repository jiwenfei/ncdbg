package com.programmaticallyspeaking.ncd.nashorn

import java.util

import com.programmaticallyspeaking.ncd.host._
import com.programmaticallyspeaking.ncd.host.types.{ExceptionData, PropertyDescriptorType, Undefined}
import com.programmaticallyspeaking.ncd.infra.StringAnyMap
import jdk.nashorn.api.scripting.AbstractJSObject
import org.scalactic.Equality
import org.scalatest.Inside
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.collection.mutable
import scala.util.Try

class RealMarshallerTest extends RealMarshallerTestFixture with Inside with TableDrivenPropertyChecks {
  import RealMarshallerTest._

  val simpleValues = Table(
    ("desc", "expression", "expected"),
    ("string", "'hello world'", SimpleValue("hello world")),
    ("concatenated string", "'hello ' + 'world'", SimpleValue("hello world")),
    ("integer value", "42", SimpleValue(42)),
    ("floating-point value", "42.5", SimpleValue(42.5d)),
    ("boolean value", "true", SimpleValue(true)),
    ("null", "null", EmptyNode),
    ("undefined", "undefined", SimpleValue(Undefined)),
    ("NaN", "NaN", SimpleValue(Double.NaN))
  )

  val complexValues = Table(
    ("desc", "expression", "expected"),
    ("array", "[42]", Map("0" -> 42, "length" -> 1)),
    ("object", "{'a':'b'}", Map("a" -> "b")),
    ("RegExp", "/.*/", Map("multiline" -> false, "source" -> ".*", "global" -> false, "lastIndex" -> 0, "ignoreCase" -> false)),
//    ("Java Array",
//      """(function() {
//        |var StringArray = Java.type("java.lang.String[]");
//        |var arr = new StringArray(1);
//        |arr[0] = "testing";
//        |return arr;
//        |})()
//      """.stripMargin, Map("0" -> "testing")),
//    ("Java Iterator",
//      """(function() {
//        |var ArrayList = Java.type("java.util.ArrayList");
//        |var list = new ArrayList(1);
//        |list.add("testing");
//        |return list.iterator();
//        |})()
//      """.stripMargin, Map("0" -> "testing"))
    ("property with get/set",
      """(function() {
        |var obj = {};
        |var foo = 0;
        |Object.defineProperty(obj, "foo", {
        |  get: function () { return foo; },
        |  set: function (value) { foo = value; }
        |});
        |return obj;
        |})()
      """.stripMargin, Map("foo" -> Map("get" -> "<function>", "set" -> "<function>"))),
    ("JSObject array (classname)", s"createInstance('${classOf[ClassNameBasedArrayJSObject].getName}')", Map("0" -> "a", "1" -> "b", "length" -> 2)),
    ("JSObject array (isArray)", s"createInstance('${classOf[IsArrayBasedArrayJSObject].getName}')", Map("0" -> "a", "1" -> "b", "length" -> 2)),
    ("JSObject object", s"createInstance('${classOf[ObjectLikeJSObject].getName}')", Map("a" -> 42, "b" -> 43))
  )

  "Marshalling of simple values works for" - {
    forAll(simpleValues) { (desc, expr, expected) =>
      desc in {
        evaluateExpression(expr) { (_, actual) =>
          actual should equal (expected)
        }
      }
    }

    "Date" in {
      evaluateExpression("new Date(2017,0,21)") { (_, actual) =>
        inside(actual) {
          case DateNode(str, _) =>
            str should fullyMatch regex "Sat Jan 21 2017 00:00:00 [A-Z]{3}[0-9+]{5} (.*)"
        }
      }
    }

    "Error" in {
      evaluateExpression("new TypeError('oops')") { (_, actual) =>
        inside(actual) {
          case ErrorValue(data, isBasedOnThrowable, _) =>
            val stack = "TypeError: oops\n\tat <program> (<eval>:1)"
            data should be (ExceptionData("TypeError", "oops", 1, -1, "<eval>", Some(stack)))
            isBasedOnThrowable should be (false)
        }
      }
    }

    "Java Exception" - {
      val expr = "(function(){try{throw new java.lang.IllegalArgumentException('oops');}catch(e){return e;}})()"

      def evalException(handler: (ErrorValue) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case err: ErrorValue => handler(err)
          }
        }
      }

      "with appropriate exception data" in {
        evalException { err =>
          err.data should be (ExceptionData("java.lang.IllegalArgumentException", "oops", 3, -1, "<eval>",
            Some("java.lang.IllegalArgumentException: oops")))
        }
      }

      "with a flag indicating it's Throwable based" in {
        evalException { err =>
          err.isBasedOnThrowable should be (true)
        }
      }

      "with a property 'javaStack' (which cannot be evaluated yet...)" in {
        evaluateExpression(expr) { (host, actual) =>
          expand(host, actual) match {
            case StringAnyMap(aMap) =>
              aMap.get("javaStack") match {
                case Some(st: String) =>
                  st should startWith ("java.lang.IllegalArgumentException: oops")
                case Some(st) => fail("Unexpected javaStack: " + st)
                case None => fail("Missing javaStack")
              }

            case other => fail("Unexpected: " + other)
          }
        }
      }
    }

    "RegExp" - {
      // Note: flags 'u' and 'y' are not supported by Nashorn
      // See https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/RegExp
      val expr = "/.*/gim"

      def evalRegexp(handler: (RegExpNode) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case re: RegExpNode => handler(re)
          }
        }
      }

      "with a string representation" in {
        evalRegexp { re =>
          re.stringRepresentation should be ("/.*/gim")
        }
      }
    }

    "JSObject-based array" - {
      def evalArray(expr: String)(handler: (ArrayNode) => Unit): Unit = {
        evaluateExpression(expr) { (host, actual) =>
          inside(actual) {
            case an: ArrayNode => handler(an)
          }
        }
      }

      val testCases = Table(
        ("description", "class"),
        ("with proper class name", classOf[ClassNameBasedArrayJSObject]),
        ("with isArray==true", classOf[IsArrayBasedArrayJSObject])
      )

      forAll(testCases) { (description, clazz) =>
        description in {
          val expr = s"createInstance('${clazz.getName}')"
          evalArray(expr) { an =>
            an.size should be (2)
          }
        }
      }
    }

    "JSObject-based function" - {
      def evalFunction(expr: String)(handler: (FunctionNode) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case fn: FunctionNode => handler(fn)
          }
        }
      }

      val testCases = Table(
        ("description", "class", "tester"),
        ("with proper class name", classOf[ClassNameBasedFunctionJSObject],
          (fn: FunctionNode) => {fn.name should be ("")}),
        ("with isFunction==true", classOf[IsFunctionBasedFunctionJSObject],
          (fn: FunctionNode) => {fn.name should be ("")}),
        ("with a name", classOf[WithNameFunctionJSObject],
          (fn: FunctionNode) => {fn.copy(objectId = null) should be (FunctionNode("fun", "function fun() {}", null))})
      )

      forAll(testCases) { (description, clazz, tester) =>
        description in {
          val expr = s"createInstance('${clazz.getName}')"
          evalFunction(expr) { fn =>
            tester(fn)
          }
        }
      }
    }
  }

  "Marshalling of complex values works for" - {
    forAll(complexValues) { (desc, expr, expected) =>
      desc in {
        evaluateExpression(expr) { (host, actual) =>
          expand(host, actual) should equal (expected)
        }
      }
    }
  }

}

object RealMarshallerTest {
  def expand(host: ScriptHost, node: ValueNode): Any = {
    val seenObjectIds = mutable.Set[ObjectId]()
    def recurse(node: ValueNode): Any = node match {
      case complex: ComplexNode if seenObjectIds.contains(complex.objectId) =>
        // In Nashorn, apparently the constructor of the prototype of a function is the function itself...
        throw new Exception("Cycle detected for object " + complex.objectId)
      case complex: ComplexNode =>
        seenObjectIds += complex.objectId
        host.getObjectProperties(complex.objectId, true, false).map(e => e._2.descriptorType match {
          case PropertyDescriptorType.Generic =>
            e._1 -> "???"
          case PropertyDescriptorType.Data =>
            e._2.value match {
              case Some(value) => e._1 -> recurse(value)
              case None => throw new RuntimeException("Incorrect data descriptor, no value")
            }
          case PropertyDescriptorType.Accessor =>
            val props = e._2.getter.map(_ => "get").toSeq ++ e._2.setter.map(_ => "set")
            e._1 -> props.map(p => p -> "<function>").toMap
        })
      case EmptyNode => null
      case SimpleValue(simple) => simple
      case other => throw new Exception("Unhandled: " + other)
    }
    recurse(node)
  }

  implicit val valueNodeEq: Equality[ValueNode] =
    (a: ValueNode, b: Any) => b match {
      case vn: ValueNode =>
        a match {
          case SimpleValue(d: Double) =>
            vn match {
              case SimpleValue(ad: Double) =>
                // Required for NaN comparison
                java.lang.Double.compare(d, ad) == 0
              case other => false
            }
          case _ =>
            a == vn
        }

      case other => false
    }
}

abstract class BaseArrayJSObject(items: Seq[AnyRef]) extends AbstractJSObject {
  import scala.collection.JavaConverters._
  override def hasSlot(slot: Int): Boolean = slot >= 0 && slot < items.size

  override def getSlot(index: Int): AnyRef = items(index)

  override def hasMember(name: String): Boolean = Try(name.toInt).map(hasSlot).getOrElse(name == "length")

  override def getMember(name: String): AnyRef = Try(name.toInt).map(getSlot).getOrElse(if (name == "length") items.size.asInstanceOf[AnyRef] else null)

  override def keySet(): util.Set[String] = (items.indices.map(_.toString) :+ "length").toSet.asJava

  override def values(): util.Collection[AnyRef] = items.asJava
}

class ClassNameBasedArrayJSObject extends BaseArrayJSObject(Seq("a", "b")) {
  override def getClassName: String = "Array"
}

class IsArrayBasedArrayJSObject extends BaseArrayJSObject(Seq("a", "b")) {
  override def isArray: Boolean = true
}


class ObjectLikeJSObject extends AbstractJSObject {
  import scala.collection.JavaConverters._
  val data: Map[String, AnyRef] = Map("a" -> 42.asInstanceOf[AnyRef], "b" -> 43.asInstanceOf[AnyRef])

  override def values(): util.Collection[AnyRef] = data.values.toList.asJava

  override def hasMember(name: String): Boolean = data.contains(name)

  override def getMember(name: String): AnyRef = data(name)

  override def getClassName: String = "Object"

  override def keySet(): util.Set[String] = data.keySet.asJava
}

abstract class BaseFunctionJSObject extends AbstractJSObject {
  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"
}

class ClassNameBasedFunctionJSObject extends BaseFunctionJSObject {
  override def getClassName: String = "Function"

  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"
}

class IsFunctionBasedFunctionJSObject extends BaseFunctionJSObject {
  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"

  override def isFunction: Boolean = true
}

class WithNameFunctionJSObject extends ClassNameBasedFunctionJSObject {
  override def getMember(name: String): AnyRef = {
    if (name == "name") "fun"
    else super.getMember(name)
  }
}