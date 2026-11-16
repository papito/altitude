package altitude.core.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * SHA-256 as 64 lowercase hex characters, as `sha256sum` prints it. Every overload gives the same value over the same bytes, and
 * so does a `Digest` fed them in pieces.
 */
object Sha256:
  def hex(bytes: Array[Byte]): String = render(newDigest.digest(bytes))

  /** Streamed rather than read into memory */
  def hex(path: Path): String =
    val digest = Digest()
    digest.update(path)
    digest.hex()

  def hex(stream: InputStream): String =
    val digest = Digest()
    digest.update(stream)
    digest.hex()

  /** Fed its bytes in pieces, as they come; not thread-safe, as the `MessageDigest` under it is not */
  final class Digest:
    private val md = newDigest

    def update(bytes: Array[Byte], offset: Int, length: Int): Unit = md.update(bytes, offset, length)

    def update(stream: InputStream): Unit =
      val chunk = new Array[Byte](8192)
      var read = stream.read(chunk)
      while read != -1 do
        md.update(chunk, 0, read)
        read = stream.read(chunk)

    def update(path: Path): Unit =
      val stream = Files.newInputStream(path)
      try update(stream)
      finally stream.close()

    /** The digest is then empty again, as a new one is */
    def hex(): String = render(md.digest())

  // A MessageDigest is stateful and not thread-safe, so every hash takes a fresh one
  private def newDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  private def render(hash: Array[Byte]): String = HexFormat.of().formatHex(hash)
