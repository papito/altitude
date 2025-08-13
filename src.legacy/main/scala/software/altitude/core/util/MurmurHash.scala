package software.altitude.core.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MurmurHash {
  def hash32(data: Array[Byte], seed: Int = 0): Int = {
    val c1 = 0xcc9e2d51
    val c2 = 0x1b873593
    val r1 = 15
    val r2 = 13
    val m = 5
    val n = 0xe6546b64

    val length = data.length
    val h1 = seed
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    var k1 = 0
    var h = h1

    while (buffer.remaining >= 4) {
      k1 = buffer.getInt
      k1 *= c1
      k1 = Integer.rotateLeft(k1, r1)
      k1 *= c2

      h ^= k1
      h = Integer.rotateLeft(h, r2) * m + n
    }

    k1 = 0
    if (buffer.remaining > 0) {
      for (i <- buffer.remaining - 1 to 0 by -1) {
        k1 ^= (buffer.get & 0xff) << (i * 8)
      }
      k1 *= c1
      k1 = Integer.rotateLeft(k1, r1)
      k1 *= c2
      h ^= k1
    }

    h ^= length
    h ^= (h >>> 16)
    h *= 0x85ebca6b
    h ^= (h >>> 13)
    h *= 0xc2b2ae35
    h ^= (h >>> 16)

    h
  }
}
