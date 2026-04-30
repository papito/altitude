package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.include
import altitude.core.models.Stat
import altitude.core.models.Folder
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.test.TestFocus
import org.scalatest.matchers.should.Matchers.{convertToStringShouldWrapperForVerb, should}

import scala.language.implicitConversions


@DoNotDiscover class CoreModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Serialize and deserialize a Stat model") {
    val stat = Stat(dimension = "test_dim", dimVal = 42)
    val json = stat.toJson
    val deserialized: Stat = json
    deserialized.dimension should be(stat.dimension)
    deserialized.dimVal should be(stat.dimVal)
  }

  test("Serialize and deserialize a Folder model") {
    val folder = Folder(id = Some("test-id"), parentId = "parent-id", name = "Test Folder")
    val json = folder.toJson
    val deserialized: Folder = json
    deserialized.id should be(folder.id)
    deserialized.name should be(folder.name)
    deserialized.parentId should be(folder.parentId)
  }

  test("Model toJson contains expected fields") {
    val stat = Stat(dimension = "my_dimension", dimVal = 100)
    val jsonStr = stat.toJson.toString()
    jsonStr should include("my_dimension")
    jsonStr should include("100")
  }
}
