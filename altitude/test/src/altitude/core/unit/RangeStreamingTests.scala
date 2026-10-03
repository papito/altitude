package altitude.core.unit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.routes.RangeStreaming.FileWindow

@DoNotDiscover class RangeStreamingTests extends AnyFunSuite {
  private val MIME = "video/mp4"

  private def withFile(bytes: Array[Byte])(test: Path => Unit): Unit =
    val path = Files.createTempFile("range-streaming", ".bin")
    try
      Files.write(path, bytes)
      test(path)
    finally Files.deleteIfExists(path)

  /** A client that takes `accepted` bytes and then goes away, failing every later write with `failure` */
  private class DepartingClient(accepted: Int, failure: => IOException) extends OutputStream:
    val received = ByteArrayOutputStream()

    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
      if received.size + length > accepted then throw failure
      received.write(bytes, offset, length)

  test("A window of a file is written whole") {
    val bytes = Array.tabulate[Byte](1000)(_.toByte)

    withFile(bytes) {
      path =>
        val out = ByteArrayOutputStream()
        FileWindow(path, 100, 50, MIME).writeBytesTo(out)
        out.toByteArray shouldBe bytes.slice(100, 150)
    }
  }

  test("A client that goes away mid-stream ends the response without an error") {
    // A player drops the connection whenever it has what it wants: the metadata of a Video, the asset the user left
    withFile(Array.fill[Byte](100000)(1)) {
      path =>
        Seq[() => IOException](() => ClosedChannelException(), () => IOException("Broken pipe")).foreach {
          failure =>
            val client = DepartingClient(accepted = 0, failure())
            noException should be thrownBy FileWindow(path, 0, 100000, MIME).writeBytesTo(client)
        }
    }
  }

  test("A file that cannot be read is still an error") {
    val missing = Files.createTempDirectory("range-streaming").resolve("missing.bin")

    a[NoSuchFileException] should be thrownBy FileWindow(missing, 0, 10, MIME).writeBytesTo(ByteArrayOutputStream())

    // A window past the end of the file has nothing to transfer
    withFile(Array.fill[Byte](10)(1)) {
      path => an[IOException] should be thrownBy FileWindow(path, 0, 20, MIME).writeBytesTo(ByteArrayOutputStream())
    }
  }
}
