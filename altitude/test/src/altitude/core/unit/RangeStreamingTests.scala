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

import altitude.core.NotFoundException
import altitude.core.routes.RangeStreaming
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

    /**
     * Setup:
     *
     * A 1,000-byte temporary file of counting bytes.
     *
     * Assertions:
     *
     * A 50-byte window from byte 100 writes exactly that slice of the file.
     */
    val bytes = Array.tabulate[Byte](1000)(_.toByte)

    withFile(bytes) {
      path =>
        val out = ByteArrayOutputStream()
        FileWindow(path, 100, 50, MIME).writeBytesTo(out)
        out.toByteArray shouldBe bytes.slice(100, 150)
    }
  }

  test("A client that goes away mid-stream ends the response without an error") {

    /**
     * Setup:
     *
     * A 100,000-byte file written to clients that take the first 50,000 bytes and then refuse every write, with a
     * ClosedChannelException or a "Broken pipe" IOException.
     *
     * Assertions:
     *
     * Either way the window ends without an exception, and the client holds an unbroken start of the file: a player drops the
     * connection whenever it has what it wants.
     *
     * Edge cases:
     *
     * A client that refuses the very first write.
     */
    val bytes = Array.tabulate[Byte](100000)(_.toByte)

    withFile(bytes) {
      path =>
        for
          accepted <- Seq(0, 50000)
          failure <- Seq[() => IOException](() => ClosedChannelException(), () => IOException("Broken pipe"))
        do
          val client = DepartingClient(accepted, failure())
          noException should be thrownBy FileWindow(path, 0, bytes.length, MIME).writeBytesTo(client)

          val received = client.received.toByteArray
          received.length should be <= accepted
          received shouldBe bytes.take(received.length)
          if accepted > 0 then received should not be empty
    }
  }

  test("A file that cannot be read is still an error") {

    /**
     * Setup:
     *
     * A path to a missing file, and a 20-byte window over a 10-byte file.
     *
     * Assertions:
     *
     * Each failure to read throws, unlike a client going away.
     */
    val dir = Files.createTempDirectory("range-streaming")
    try
      val missing = dir.resolve("missing.bin")
      a[NoSuchFileException] should be thrownBy FileWindow(missing, 0, 10, MIME).writeBytesTo(ByteArrayOutputStream())
    finally Files.deleteIfExists(dir)

    // A window past the end of the file has nothing to transfer
    withFile(Array.fill[Byte](10)(1)) {
      path => an[IOException] should be thrownBy FileWindow(path, 0, 20, MIME).writeBytesTo(ByteArrayOutputStream())
    }
  }

  test("A file name is offered inline as quoted ASCII and as RFC 5987 UTF-8, never with a raw control character") {

    /**
     * Setup:
     *
     * File names with characters that each form of the Content-Disposition file name treats differently.
     *
     * Assertions:
     *
     * The ASCII form keeps printable ASCII but the double quote and backslash, replacing each other character with one
     * underscore. The UTF-8 form keeps the RFC 5987 attr-chars and percent-encodes every other byte in upper case, so neither
     * form can end the header or the parameter early.
     *
     * Edge cases:
     *
     * Characters `encodeURIComponent` or `URLEncoder` would leave alone (`*`, `'`, parentheses) or turn into a plus (a space) are
     * encoded; a CR LF, a tab and a DEL cannot start a new header; a character outside the BMP is one underscore and four bytes;
     * a lone surrogate is one underscore and the `?` UTF-8 encoding substitutes for it.
     */
    def disposition(ascii: String, encoded: String) = s"""inline; filename="$ascii"; filename*=UTF-8''$encoded"""

    RangeStreaming.contentDisposition("IMG_0001.JPG") shouldBe disposition("IMG_0001.JPG", "IMG_0001.JPG")
    RangeStreaming.contentDisposition("!#$&+-.^_`|~") shouldBe disposition("!#$&+-.^_`|~", "!#$&+-.^_`|~")
    RangeStreaming.contentDisposition("a b*c'd%e(f).txt") shouldBe disposition("a b*c'd%e(f).txt", "a%20b%2Ac%27d%25e%28f%29.txt")
    RangeStreaming.contentDisposition("say \"hi\" \\ ü.jpg") shouldBe
      disposition("say _hi_ _ _.jpg", "say%20%22hi%22%20%5C%20%C3%BC.jpg")
    RangeStreaming.contentDisposition("evil\r\nSet-Cookie: x=1\t.jpg") shouldBe
      disposition("evil__Set-Cookie: x=1_.jpg", "evil%0D%0ASet-Cookie%3A%20x%3D1%09.jpg")
    RangeStreaming.contentDisposition("\uD83D\uDE00.png") shouldBe disposition("_.png", "%F0%9F%98%80.png")
    RangeStreaming.contentDisposition("a\u007fb") shouldBe disposition("a_b", "a%7Fb")
    RangeStreaming.contentDisposition("a\uD83Db") shouldBe disposition("a_b", "a%3Fb")
  }

  test("A file that is not there is not found before any response is made") {

    /**
     * Setup:
     *
     * A path to a missing file.
     *
     * Assertions:
     *
     * Responding with it throws NotFoundException, answered as a 404, rather than the file system's own exception.
     */
    val dir = Files.createTempDirectory("range-streaming")
    try a[NotFoundException] should be thrownBy RangeStreaming.respond(dir.resolve("missing.bin"), MIME, "missing.bin", None)
    finally Files.deleteIfExists(dir)
  }
}
