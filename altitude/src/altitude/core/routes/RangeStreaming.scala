package altitude.core.routes

import cask.model.Response
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.NotFoundException

/**
 * Serves a stored file by streaming a window of it, so a Video is never read into the heap and a player can seek: a plain request
 * gets the whole file as a `200` offering byte ranges, a single `bytes=a-b` range gets a `206` with `Content-Range`, and a range
 * past the end gets a `416`. Anything else the `Range` header might carry (several ranges, other units) is ignored, as the
 * standard allows, and the whole file is sent. The `200` and the `206` name the file inline, so a browser shows or plays it as
 * before and saves it under its own name. A file that is not there is a `NotFoundException`, thrown before any response is made;
 * one removed while it streams fails the response already sent.
 *
 * A player drops the connection whenever it has what it wants (a Video's metadata, before asking for the range it plays from; the
 * asset the user stepped away from), so a client that goes away mid-stream ends the response, and is not an error.
 */
object RangeStreaming:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val ACCEPT_RANGES: (String, String) = "Accept-Ranges" -> "bytes"

  private val SingleRange = """^bytes=(\d*)-(\d*)$""".r

  /** The RFC 5987 attr-chars, which the UTF-8 form of a file name keeps; all ASCII, so never a byte of a wider character */
  private val AttrChars: Set[Char] = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).toSet ++ "!#$&+-.^_`|~"

  /**
   * An inline `Content-Disposition` with the file name in both RFC 6266 forms: `filename`, a quoted string of printable ASCII
   * where every other character, and the `"` and `\` that would end or escape the string, is a `_`; and `filename*`, which a
   * browser prefers, the name's UTF-8 bytes with every one that is not an attr-char percent-encoded. Neither form carries a
   * control character, so no file name can end the header.
   */
  def contentDisposition(fileName: String): String =
    val ascii = fileName.codePoints.toArray.map {
      case c if c < 0x20 || c > 0x7e || c == '"' || c == '\\' => '_'
      case c => c.toChar
    }
    val encoded = fileName.getBytes(UTF_8).map(b => if AttrChars(b.toChar) then b.toChar.toString else f"%%${b & 0xff}%02X")
    s"""inline; filename="${ascii.mkString}"; filename*=UTF-8''${encoded.mkString}"""

  /** A range position; a run of digits too long for a Long is past the end of any file */
  private def position(digits: String): Long = digits.toLongOption.getOrElse(Long.MaxValue)

  /** The client refused a write: it closed the connection, which tells a failed write apart from a failed read of the file */
  private class ClientDisconnectedException(cause: IOException) extends IOException(cause)

  /** The response's channel, where any failure to write is the client's going away */
  private class ClientChannel(out: OutputStream) extends WritableByteChannel:
    private val channel = Channels.newChannel(out)

    override def write(source: ByteBuffer): Int =
      try channel.write(source)
      catch case e: IOException => throw ClientDisconnectedException(e)

    override def isOpen: Boolean = channel.isOpen
    override def close(): Unit = channel.close()

  /** A `geny.Writable` over `length` bytes of a file from `start`, copied channel to channel as it is written */
  class FileWindow(path: Path, start: Long, length: Long, mime: String) extends geny.Writable:
    override def contentLength: Option[Long] = Some(length)
    override def httpContentType: Option[String] = Some(mime)

    override def writeBytesTo(out: OutputStream): Unit =
      val channel = FileChannel.open(path, StandardOpenOption.READ)
      var position = start
      try
        val target = ClientChannel(out)
        var remaining = length
        while remaining > 0 do
          val transferred = channel.transferTo(position, remaining, target)
          if transferred <= 0 then throw IOException(s"Could not stream $path at $position")
          position += transferred
          remaining -= transferred
      catch
        case e: ClientDisconnectedException =>
          logger.debug(
            s"The client went away while $path was streamed, around byte $position of $start-${start + length - 1}: ${e.getCause}")
      finally channel.close()

  def respond(path: Path, mime: String, fileName: String, rangeHeader: Option[String]): Response.Raw =
    // The one look at the file before streaming it, so a file removed since it was found is not found either
    val size =
      try Files.size(path)
      catch case e: NoSuchFileException => throw NotFoundException(s"Cannot find file $path")
    val disposition = "Content-Disposition" -> contentDisposition(fileName)

    def whole = Response(Response.Data.WritableData(FileWindow(path, 0, size, mime)), 200, Seq(ACCEPT_RANGES, disposition))

    rangeHeader.map(_.trim) match
      case Some(SingleRange(first, last)) if first.nonEmpty || last.nonEmpty =>
        // `bytes=a-b`, `bytes=a-` to the end, or `bytes=-n` for the last n bytes
        val (from, to) =
          if first.isEmpty then (Math.max(0L, size - position(last)), size - 1)
          else (position(first), if last.isEmpty then size - 1 else Math.min(position(last), size - 1))

        if from >= size || from > to then
          Response(Response.Data.WritableData(""), 416, Seq(ACCEPT_RANGES, "Content-Range" -> s"bytes */$size"))
        else
          Response(
            Response.Data.WritableData(FileWindow(path, from, to - from + 1, mime)),
            206,
            Seq(ACCEPT_RANGES, "Content-Range" -> s"bytes $from-$to/$size", disposition))
      case _ => whole
