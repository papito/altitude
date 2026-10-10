package altitude.core.unit

import altitude.test.TestFocus
import java.time.Instant
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.service.ContentSigner

@DoNotDiscover class ContentSignerTests extends funsuite.AnyFunSuite with TestFocus {
  private val PATH = "/content/r/7f9a7a4e-3c55-4b7e-9d36-0d9a1d2f6c11/preview/2c1b1d3e-8f0a-4a8e-b6a1-5e2f7c9d4b10"
  private val OTHER_PATH = "/content/r/7f9a7a4e-3c55-4b7e-9d36-0d9a1d2f6c11/preview/9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4"
  private val NOW = Instant.parse("2026-10-10T12:00:00Z")
  private val EXPIRES_AT = NOW.plusSeconds(3600)

  private val signer = ContentSigner(Array.tabulate[Byte](32)(_.toByte))

  /** The path, `exp` and `sig` of a signed URL */
  private def parts(signed: String): (String, String, String) =
    val (path, query) = signed.splitAt(signed.indexOf('?'))
    val params = query.drop(1).split('&').map(_.split('=')).map(kv => kv(0) -> kv(1)).toMap
    (path, params("exp"), params("sig"))

  test("A signed path verifies with the same key until it expires") {

    /**
     * Setup:
     *
     * A signer with a fixed 32-byte key, and a content path signed to expire an hour from a fixed now.
     *
     * Assertions:
     *
     * The signed URL is the path with `exp` (the expiry in Unix seconds) and `sig`, and the same signer verifies it at that now.
     */
    val (path, exp, sig) = parts(signer.sign(PATH, EXPIRES_AT))

    path shouldBe PATH
    exp shouldBe EXPIRES_AT.getEpochSecond.toString
    signer.verify(path, exp, sig, NOW) shouldBe true
  }

  test("A signed URL is exactly the known HMAC-SHA256 of its path and expiry") {

    /**
     * Setup:
     *
     * The suite's key (bytes 0 to 31), its content path and an expiry of 1791637200 (2026-10-10T13:00:00Z), and the signature
     * computed for them outside this code:
     *
     * printf '%s\n%s' "<path>" 1791637200 | openssl dgst -sha256 -mac HMAC -macopt hexkey:000102…1f -binary | basenc --base64url
     * | tr -d '='
     *
     * Assertions:
     *
     * `sign` makes exactly the path, the expiry and that signature, and `verify` accepts it. A link already issued must keep
     * verifying through any change to this code, so the format is pinned to a value the code did not produce.
     */
    val vector = "SCOu-8ZLHnbRqlxZlb8v2BlzcC_RNH-IilNAAUydoC8"

    signer.sign(PATH, Instant.ofEpochSecond(1791637200)) shouldBe s"$PATH?exp=1791637200&sig=$vector"
    signer.verify(PATH, "1791637200", vector, NOW) shouldBe true
  }

  test("A signature made under another key does not verify") {

    /**
     * Setup:
     *
     * A path signed by a signer with a different key.
     *
     * Assertions:
     *
     * The suite's signer refuses it.
     */
    val other = ContentSigner(Array.fill[Byte](32)(7))
    val (path, exp, sig) = parts(other.sign(PATH, EXPIRES_AT))

    signer.verify(path, exp, sig, NOW) shouldBe false
  }

  test("The signature is URL-safe Base64 without padding") {

    /**
     * Setup:
     *
     * A signed content path.
     *
     * Assertions:
     *
     * The `sig` of an HMAC-SHA256 (32 bytes) is 43 characters of the URL-safe Base64 alphabet, with no `=` padding.
     */
    val (_, _, sig) = parts(signer.sign(PATH, EXPIRES_AT))

    sig.matches("[A-Za-z0-9_-]{43}") shouldBe true
  }

  test("A moved expiry, a changed signature and another path do not verify") {

    /**
     * Setup:
     *
     * A signed content path, then its expiry moved a second later, one character of its signature changed, and its signature
     * presented with another item's path.
     *
     * Assertions:
     *
     * None of the three verifies.
     */
    val (path, exp, sig) = parts(signer.sign(PATH, EXPIRES_AT))
    val changedSig = (if sig.head == 'A' then 'B' else 'A') +: sig.tail

    signer.verify(path, (exp.toLong + 1).toString, sig, NOW) shouldBe false
    signer.verify(path, exp, changedSig, NOW) shouldBe false
    signer.verify(OTHER_PATH, exp, sig, NOW) shouldBe false
  }

  test("Only the one spelling of a signature that sign makes verifies") {

    /**
     * Setup:
     *
     * A signed content path, then its signature with `=` padding added, and with its last character swapped for the one that
     * differs only in the two bits past the MAC's 256 (43 characters carry 258 bits), both of which decode to the same bytes.
     *
     * Assertions:
     *
     * Neither verifies, so a signed URL has one spelling and cannot be varied by its holder.
     */
    val (path, exp, sig) = parts(signer.sign(PATH, EXPIRES_AT))
    val alphabet = ('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') :+ '-' :+ '_'
    val unusedBitFlipped = sig.init :+ alphabet(alphabet.indexOf(sig.last) ^ 1)

    signer.verify(path, exp, sig + "=", NOW) shouldBe false
    signer.verify(path, exp, unusedBitFlipped, NOW) shouldBe false
  }

  test("A malformed expiry or signature does not verify and throws nothing") {

    /**
     * Setup:
     *
     * A signed content path, then its expiry replaced by text that is not a number, and its signature by text that is not
     * URL-safe Base64, by an empty one, and by a valid Base64URL value of the wrong length.
     *
     * Assertions:
     *
     * Each is refused with false rather than an exception.
     */
    val (path, exp, sig) = parts(signer.sign(PATH, EXPIRES_AT))

    signer.verify(path, "soon", sig, NOW) shouldBe false
    signer.verify(path, "", sig, NOW) shouldBe false
    signer.verify(path, exp, "not+base64/url!", NOW) shouldBe false
    signer.verify(path, exp, "", NOW) shouldBe false
    signer.verify(path, exp, sig.take(20), NOW) shouldBe false
  }

  test("A signature at or after its expiry does not verify") {

    /**
     * Setup:
     *
     * A content path signed to expire an hour from a fixed now.
     *
     * Assertions:
     *
     * It is refused at the second it expires and after it, and accepted a second before.
     */
    val (path, exp, sig) = parts(signer.sign(PATH, EXPIRES_AT))

    signer.verify(path, exp, sig, EXPIRES_AT.minusSeconds(1)) shouldBe true
    signer.verify(path, exp, sig, EXPIRES_AT) shouldBe false
    signer.verify(path, exp, sig, EXPIRES_AT.plusSeconds(1)) shouldBe false
  }
}
