package altitude.core.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * SHA-256 as 64 lowercase hex characters, the form `sha256sum` prints: the checksum of an asset's file and of a Face's crop.
 * Every overload gives the same value over the same bytes.
 */
object Sha256:
  def hex(bytes: Array[Byte]): String = render(newDigest.digest(bytes))

  /** The hash of a file's content, streamed rather than read into memory */
  def hex(path: Path): String =
    val stream = Files.newInputStream(path)
    try hex(stream)
    finally stream.close()

  def hex(stream: InputStream): String =
    val md = newDigest
    val chunk = new Array[Byte](8192)
    var read = stream.read(chunk)
    while read != -1 do
      md.update(chunk, 0, read)
      read = stream.read(chunk)
    render(md.digest())

  // A MessageDigest is stateful and not thread-safe, so every hash takes a fresh one
  private def newDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  private def render(hash: Array[Byte]): String = HexFormat.of().formatHex(hash)
