package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._
import software.altitude.core.models.BaseModel
import software.altitude.test.core.TestFocus

import java.time.LocalDateTime
import scala.language.implicitConversions


@DoNotDiscover class CoreModelTests extends funsuite.AnyFunSuite with TestFocus {

  object TestModel {
    implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
    implicit val writes: OWrites[TestModel] = Json.writes[TestModel]
    implicit val reads: Reads[TestModel] = Json.reads[TestModel]

    implicit def fromJson(json: JsValue): TestModel = Json.fromJson[TestModel](json).get
  }

  case class TestModel(id: Option[String] = None,
                       createdAt: Option[LocalDateTime] = None,
                       updatedAt: Option[LocalDateTime] = None,
                       stringProp: String,
                       boolProp: Boolean,
                       intProp: Int) extends BaseModel {

    lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
  }

  test("Create a model") {
    TestModel(stringProp = "stringPropValue", boolProp = true, intProp = 2)
  }

  test("Model JSON conversion") {
    val obj = TestModel(
      id = Some("idValue"),
      createdAt = Some(LocalDateTime.now()),
      stringProp = "stringPropValue",
      boolProp = true,
      intProp = 2)

//    println(obj)
//    println(obj.toJson)

    val jsonObj = Json.toJson(obj)
    jsonObj.toString() should include("\"string_prop\":\"stringPropValue\"")
    jsonObj.toString() should include("\"created_at\":\"20")

    val objFromJson =  TestModel.fromJson(jsonObj)
    objFromJson.intProp should be(obj.intProp)
    objFromJson.boolProp should be(obj.boolProp)
    objFromJson.stringProp should be(obj.stringProp)
  }
}
