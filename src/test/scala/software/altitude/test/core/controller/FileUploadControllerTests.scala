package software.altitude.test.core.controller


import org.scalatest.DoNotDiscover
import software.altitude.core.Altitude
import software.altitude.test.core.ControllerTestCore

import java.io.File

@DoNotDiscover class FileUploadControllerTests(override val testApp: Altitude) extends ControllerTestCore {

  test("Upload with multiple files", Focused) {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    val file1 = new File(getClass.getResource("/import/people/bullock.jpg").getPath)
    val file2 = new File(getClass.getResource("/import/people/meme-ben.jpg").getPath)

    post(s"/import/r/${repoId}/upload",
      Map(),
      files=List(("files", file1), ("files", file2)),
      headers=testAuthHeaders()) {

      response.status should equal(200)
      response.body should include("id=\"uploadForm\"")
    }
  }
}
