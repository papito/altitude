package altitude.core.unit

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.util.Random

import altitude.core.util.Sha256

/** The checksum is the standard SHA-256 in lowercase hex, the same over an array, a stream or a file of the same bytes */
@DoNotDiscover class Sha256Tests extends AnyFunSuite {

  test("The hex digest matches the FIPS 180-4 test vectors") {

    /**
     * Setup:
     *
     * The empty array and the bytes of `abc`.
     *
     * Assertions:
     *
     * Each hashes to its FIPS 180-4 digest, as 64 lowercase hex characters.
     */
    Sha256.hex(Array.emptyByteArray) shouldEqual "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    Sha256.hex("abc".getBytes("US-ASCII")) shouldEqual "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  test("The streamed hash equals the array hash for every short length and across read boundaries") {

    /**
     * Setup:
     *
     * Seeded random arrays of every length from 0 to 70, lengths around one and two 8 KiB read chunks, and 50,000 bytes.
     *
     * Assertions:
     *
     * Each hashes the same as a stream and in memory.
     *
     * Edge cases:
     *
     * The empty stream, lengths around SHA-256's 64-byte block, and lengths ending just before, on and after a chunk boundary.
     */
    val random = new Random(7)
    val lengths = (0 to 70) ++ Seq(8191, 8192, 8193, 8195, 16384, 16387, 50_000)

    lengths.foreach {
      length =>
        val bytes = new Array[Byte](length)
        random.nextBytes(bytes)
        withClue(s"length $length: ") {
          Sha256.hex(new ByteArrayInputStream(bytes)) shouldEqual Sha256.hex(bytes)
        }
    }
  }

  test("A digest fed in pieces hashes as the whole") {

    /**
     * Setup:
     *
     * 50,000 seeded random bytes, fed to one digest in uneven pieces, and to another as an array prefix then a stream.
     *
     * Assertions:
     *
     * Both give the hash of the whole array.
     *
     * Edge cases:
     *
     * An empty piece changes nothing, and reading a digest resets it, so the same bytes again give the same hash.
     */
    val bytes = new Array[Byte](50_000)
    new Random(13).nextBytes(bytes)

    val pieces = Sha256.Digest()
    Seq(0, 1, 63, 64, 65, 8192, 0, 10_000).foldLeft(0) {
      (offset, length) =>
        pieces.update(bytes, offset, length)
        offset + length
    }
    pieces.update(bytes, 18_385, bytes.length - 18_385)
    pieces.hex() shouldEqual Sha256.hex(bytes)

    val mixed = Sha256.Digest()
    mixed.update(bytes, 0, 20_000)
    mixed.update(new ByteArrayInputStream(bytes, 20_000, bytes.length - 20_000))
    mixed.hex() shouldEqual Sha256.hex(bytes)

    mixed.update(bytes, 0, bytes.length)
    mixed.hex() shouldEqual Sha256.hex(bytes)
  }

  test("A file hashes as its content") {

    /**
     * Setup:
     *
     * A temporary file of 100,003 seeded random bytes, a whole number of neither hash blocks nor read chunks.
     *
     * Assertions:
     *
     * The file hashes the same by its path as its bytes in memory.
     */
    val bytes = new Array[Byte](100_003)
    new Random(11).nextBytes(bytes)
    val file = Files.createTempFile("sha256", ".bin")
    try
      Files.write(file, bytes)
      Sha256.hex(file) shouldEqual Sha256.hex(bytes)
    finally Files.delete(file)
  }
}
