package software.altitude.test.core.web


import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import software.altitude.test.core.HtmxTestCore

import java.io.File

@DoNotDiscover class FileImportTests(val config: Map[String, Any]) extends HtmxTestCore {

  test("POST /upload with multiple files", Focused) {
    val file1 = File.createTempFile("test1", ".txt")
    FileUtils.writeStringToFile(file1, "Test content 1", "UTF-8")

    val file2 = File.createTempFile("test2", ".txt")
    FileUtils.writeStringToFile(file2, "Test content 2", "UTF-8")

    post("/",
      Map(), files=List(("file", file1), ("file", file2)),
      headers=List(
        "Content-Disposition" -> "form-data; name=\"files\"; filename=\"test1.txt\"",
        "Content-Disposition" -> "form-data; name=\"files\"; filename=\"test2.txt\"",
        "Content-Type" -> "multipart/form-data; boundary=2ChY5dI4PKmv51s7Hs2n1qQ9f8JZJ9")) {

    }

    file1.delete()
    file2.delete()
  }
}
