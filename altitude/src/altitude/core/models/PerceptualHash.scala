package altitude.core.models

import altitude.core.util.JsonCodec

object PerceptualHash:
  // One wire form whatever the value: 16 lowercase hex digits, zero-padded. upickle would write a Long as a number up to 2^53
  // and as a string beyond, so a hash with the high bit set would otherwise change type on the way out.
  given JsonCodec.ReadWriter[PerceptualHash] = JsonCodec
    .readwriter[String]
    .bimap(hash => f"${hash.bits}%016x", hex => PerceptualHash(java.lang.Long.parseUnsignedLong(hex, 16)))

/**
 * The DCT perceptual hash of a still image: 64 bits, stored as a signed `BIGINT`. Two hashes compare by Hamming distance,
 * `java.lang.Long.bitCount(a.bits ^ b.bits)`.
 */
case class PerceptualHash(bits: Long) extends AnyVal
