package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import play.api.libs.json._
import software.altitude.core.models.BaseModel
import software.altitude.test.core.TestFocus

import scala.language.implicitConversions


@DoNotDiscover class ModelTests extends funsuite.AnyFunSuite with TestFocus {

  /**
    * Start test model definition
    */
  object TestModel {
    implicit def fromJson(json: JsValue): TestModel = {
      TestModel(
        id = (json \ "id").asOpt[String],
        stringProp = (json \ "stringProp").as[String],
        boolProp = (json \ "boolProp").as[Boolean],
        intProp = (json \ "intProp").as[Int]
      ).withCoreAttr(json)
    }
  }

  case class TestModel(id: Option[String] = None,
                       stringProp: String,
                       boolProp: Boolean,
                       intProp: Int) extends BaseModel {
    val toJson: JsObject = Json.obj(
      "id" -> id,
      "stringProp" -> stringProp,
      "boolProp" -> boolProp,
      "intProp" -> intProp
    ) ++ coreJsonAttrs
  }
  /**
    * End test model definition
    */


  test("Create a model") {
    TestModel(stringProp = "stringProp", boolProp = true, intProp = 2)
  }
}
