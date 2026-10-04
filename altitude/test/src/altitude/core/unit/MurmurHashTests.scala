package altitude.core.unit

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.util.Random

import altitude.core.util.MurmurHash

/** The streamed checksum of a file is the checksum of its bytes, whatever the length and however the reads fall */
@DoNotDiscover class MurmurHashTests extends AnyFunSuite {

  test("The streamed hash equals the array hash for every tail length and across read boundaries") {

    /**
     * Setup:
     *
     * Arrays of seeded random bytes of every length from 0 to 70, plus lengths around one and two 8 KiB read chunks and one of
     * 50,000 bytes.
     *
     * Assertions:
     *
     * Hashing each array as a stream gives the same checksum as hashing it in memory.
     *
     * Edge cases:
     *
     * The empty array, every leftover of one to three bytes after the last whole int, and lengths that end just before, on and
     * just after a read chunk boundary, where a partial int has to carry over into the next read.
     */
    val random = new Random(7)
    val lengths = (0 to 70) ++ Seq(8191, 8192, 8193, 8195, 16384, 16387, 50_000)

    lengths.foreach {
      length =>
        val bytes = new Array[Byte](length)
        random.nextBytes(bytes)
        withClue(s"length $length: ") {
          MurmurHash.hash32(new ByteArrayInputStream(bytes)) shouldEqual MurmurHash.hash32(bytes)
        }
    }
  }

  test("A file hashes as its content") {

    /**
     * Setup:
     *
     * A temporary file of 100,003 seeded random bytes, a length that is neither a whole number of ints nor of read chunks.
     *
     * Assertions:
     *
     * Hashing the file by its path gives the same checksum as hashing its bytes in memory.
     */
    val bytes = new Array[Byte](100_003)
    new Random(11).nextBytes(bytes)
    val file = Files.createTempFile("murmur", ".bin")
    try
      Files.write(file, bytes)
      MurmurHash.hash32(file) shouldEqual MurmurHash.hash32(bytes)
    finally Files.delete(file)
  }
}
