package software.altitude.test.core.web


import org.scalatest.DoNotDiscover
import software.altitude.test.core.HtmxTestCore

import java.io.File

@DoNotDiscover class FileUploadControllerTests(val config: Map[String, Any]) extends HtmxTestCore {

  test("Upload with multiple files") {
    testContext.persistUser()

    val file1 = new File(getClass.getResource("/import/images/1.jpg").getPath)
    val file2 = new File(getClass.getResource("/import/images/cactus.jpg").getPath)

    post("/import/upload",
      Map(),
      files=List(("files", file1), ("files", file2)),
      headers=Map(getUserSessionHeader())) {

      response.status should equal(200)
      response.body should include("id=\"uploadForm\"")
    }
  }
}
