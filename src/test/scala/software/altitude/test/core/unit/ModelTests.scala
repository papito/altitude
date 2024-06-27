package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
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

  test("Get a modified model copy") {
    val original = TestModel(stringProp = "stringProp", boolProp = true, intProp = 2)
    original.stringProp shouldBe "stringProp"

    val modified: TestModel = original.modify("stringProp" -> "new!", "intProp" -> 3)
    modified.stringProp shouldBe "new!"
    modified.intProp shouldBe 3
  }

  test("Attempt to modify a non-existing property should fail") {
    val original = TestModel(stringProp = "stringProp", boolProp = true, intProp = 2)
    intercept[IllegalArgumentException] {
      original.modify("funky" -> "LOLZ", "veryFunky" -> "LOLZ")
    }
  }
}
