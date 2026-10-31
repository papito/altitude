package altitude.core.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MurmurHash:
  private val c1 = 0xcc9e2d51
  private val c2 = 0x1b873593
  private val r1 = 15
  private val r2 = 13
  private val m = 5
  private val n = 0xe6546b64

  def hash32(data: Array[Byte], seed: Int = 0): Int =
    val length = data.length
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    var h = seed

    while (buffer.remaining >= 4) {
      h = mixBlock(h, buffer.getInt)
    }

    if buffer.remaining > 0 then
      var k1 = 0
      for (i <- buffer.remaining - 1 to 0 by -1) {
        k1 ^= (buffer.get & 0xff) << (i * 8)
      }
      h = mixTail(h, k1)

    finish(h, length)

  private def mixBlock(h: Int, block: Int): Int =
    var k1 = block
    k1 *= c1
    k1 = Integer.rotateLeft(k1, r1)
    k1 *= c2
    Integer.rotateLeft(h ^ k1, r2) * m + n

  private def mixTail(h: Int, tail: Int): Int =
    var k1 = tail
    k1 *= c1
    k1 = Integer.rotateLeft(k1, r1)
    k1 *= c2
    h ^ k1

  private def finish(hash: Int, length: Int): Int =
    var h = hash
    h ^= length
    h ^= (h >>> 16)
    h *= 0x85ebca6b
    h ^= (h >>> 13)
    h *= 0xc2b2ae35
    h ^= (h >>> 16)
    h
