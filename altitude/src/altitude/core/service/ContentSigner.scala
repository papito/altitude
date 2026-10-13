package altitude.core.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ContentSigner:
  private val ALGORITHM = "HmacSHA256"

/**
 * Signs and verifies content URLs, so a holder of a signed URL is served that one item without logging in.
 *
 * The signature is `HMAC-SHA256(key, "<path>\n<exp>")`, `exp` being the expiry in Unix seconds, carried in the URL as Base64URL
 * without padding. The path signed is the request path of the item (`/content/r/<repoId>/<dataType>/<itemId>`), which names one
 * item of one repository, so a signature grants that item and nothing else. Content paths carry no query string of their own:
 * `sign` appends one.
 *
 * The key is the persisted `system.signing_key` ([[SystemService.loadOrCreateSigningKey]]), so a signed URL survives a restart.
 */
class ContentSigner(key: Array[Byte]):
  private val keySpec = SecretKeySpec(key, ContentSigner.ALGORITHM)

  /** The path with its expiry and its signature as query parameters */
  def sign(path: String, expiresAt: Instant): String =
    val exp = expiresAt.getEpochSecond.toString
    s"$path?exp=$exp&sig=${signature(path, exp)}"

  /**
   * Whether `sig` is this key's signature of `path` and `exp`, and `now` is before `exp`. A malformed `exp` or `sig` is false.
   *
   * The expected signature is encoded and compared as text rather than `sig` decoded: a Base64 decoder also takes `sig` with
   * padding, or with other values in the two bits of its last character past the MAC, so only the one spelling `sign` makes
   * verifies. The comparison is `MessageDigest.isEqual`, which takes the same time whatever the first differing byte, so the time
   * of a refusal tells nothing of how much of a guessed signature was right.
   */
  def verify(path: String, exp: String, sig: String, now: Instant): Boolean =
    exp.toLongOption.exists(
      expSeconds =>
        now.getEpochSecond < expSeconds &&
          MessageDigest.isEqual(signature(path, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8)))

  // Base64URL without padding of the MAC. A Mac is not thread-safe, so every call gets its own.
  private def signature(path: String, exp: String): String =
    val mac = Mac.getInstance(ContentSigner.ALGORITHM)
    mac.init(keySpec)
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(s"$path\n$exp".getBytes(StandardCharsets.UTF_8)))
