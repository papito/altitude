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
     * Assertions:
     *
     * The empty array and the three bytes of `abc` hash to the digests FIPS 180-4 publishes, as 64 lowercase hex characters, the
     * form `sha256sum` prints.
     */
    Sha256.hex(Array.emptyByteArray) shouldEqual "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    Sha256.hex("abc".getBytes("US-ASCII")) shouldEqual "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  test("The streamed hash equals the array hash for every short length and across read boundaries") {

    /**
     * Setup:
     *
     * Arrays of seeded random bytes of every length from 0 to 70, plus lengths around one and two 8 KiB read chunks and one of
     * 50,000 bytes.
     *
     * Assertions:
     *
     * Hashing each array as a stream gives the same digest as hashing it in memory.
     *
     * Edge cases:
     *
     * The empty stream, lengths on either side of SHA-256's 64-byte block, and lengths that end just before, on and just after a
     * read chunk boundary.
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

  test("A file hashes as its content") {

    /**
     * Setup:
     *
     * A temporary file of 100,003 seeded random bytes, a length that is neither a whole number of hash blocks nor of read chunks.
     *
     * Assertions:
     *
     * Hashing the file by its path gives the same digest as hashing its bytes in memory.
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
