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
    val bytes = new Array[Byte](100_003)
    new Random(11).nextBytes(bytes)
    val file = Files.createTempFile("murmur", ".bin")
    try
      Files.write(file, bytes)
      MurmurHash.hash32(file) shouldEqual MurmurHash.hash32(bytes)
    finally Files.delete(file)
  }
}
