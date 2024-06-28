package software.altitude.test.core.web


import org.scalatest.DoNotDiscover
import software.altitude.test.core.HtmxTestCore

import java.io.File

@DoNotDiscover class FileUploadControllerTests(val config: Map[String, Any]) extends HtmxTestCore {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setTestUserOnTheTestingServer()
  }

  test("Upload with multiple files") {
    val file1 = new File(getClass.getResource("/import/images/1.jpg").getPath)
    val file2 = new File(getClass.getResource("/import/images/cactus.jpg").getPath)

    post("/import/upload", Map(), files=List(("files", file1), ("files", file2))) {
      response.status should equal(200)
      response.body should include("id=\"uploadForm\"")
    }
  }
}
