package altitude.core.routes

import cask.model.Response
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Serves a stored file by streaming a window of it, so a Video is never read into the heap and a player can seek: a plain request
 * gets the whole file as a `200` offering byte ranges, a single `bytes=a-b` range gets a `206` with `Content-Range`, and a range
 * past the end gets a `416`. Anything else the `Range` header might carry (several ranges, other units) is ignored, as the
 * standard allows, and the whole file is sent.
 */
object RangeStreaming:
  val ACCEPT_RANGES: (String, String) = "Accept-Ranges" -> "bytes"

  private val SingleRange = """^bytes=(\d*)-(\d*)$""".r

  /** A range position; a run of digits too long for a Long is past the end of any file */
  private def position(digits: String): Long = digits.toLongOption.getOrElse(Long.MaxValue)

  /** A `geny.Writable` over `length` bytes of a file from `start`, copied channel to channel as it is written */
  class FileWindow(path: Path, start: Long, length: Long, mime: String) extends geny.Writable:
    override def contentLength: Option[Long] = Some(length)
    override def httpContentType: Option[String] = Some(mime)

    override def writeBytesTo(out: OutputStream): Unit =
      val channel = FileChannel.open(path, StandardOpenOption.READ)
      try
        val target = Channels.newChannel(out)
        var position = start
        var remaining = length
        while remaining > 0 do
          val transferred = channel.transferTo(position, remaining, target)
          if transferred <= 0 then throw IOException(s"Could not stream $path at $position")
          position += transferred
          remaining -= transferred
      finally channel.close()

  def respond(path: Path, mime: String, rangeHeader: Option[String]): Response.Raw =
    val size = Files.size(path)

    def whole = Response(Response.Data.WritableData(FileWindow(path, 0, size, mime)), 200, Seq(ACCEPT_RANGES))

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
            Seq(ACCEPT_RANGES, "Content-Range" -> s"bytes $from-$to/$size"))
      case _ => whole
