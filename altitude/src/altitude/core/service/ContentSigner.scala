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
 * Signs content URLs, so a holder of a signed URL is served that one item without logging in. The signature is
 * `HMAC-SHA256(key, "<path>\n<exp>")`, `exp` in Unix seconds; the path names one item of one repository, so a signature grants
 * that item alone. The key is persisted ([[SystemService.loadOrCreateSigningKey]]), so a signed URL survives a restart.
 */
class ContentSigner(key: Array[Byte]):
  private val keySpec = SecretKeySpec(key, ContentSigner.ALGORITHM)

  /** Content paths carry no query string of their own, so this appends one */
  def sign(path: String, expiresAt: Instant): String =
    val exp = expiresAt.getEpochSecond.toString
    s"$path?exp=$exp&sig=${signature(path, exp)}"

  /**
   * The expected signature is compared as text rather than `sig` decoded, since a Base64 decoder also accepts padding or other
   * values in the spare bits of the last character; so only the one spelling `sign` makes verifies. `MessageDigest.isEqual` is
   * constant-time, so a refusal's timing reveals nothing of a guessed signature.
   */
  def verify(path: String, exp: String, sig: String, now: Instant): Boolean =
    exp.toLongOption.exists(
      expSeconds =>
        now.getEpochSecond < expSeconds &&
          MessageDigest.isEqual(signature(path, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8)))

  // A Mac is not thread-safe, so every call gets its own
  private def signature(path: String, exp: String): String =
    val mac = Mac.getInstance(ContentSigner.ALGORITHM)
    mac.init(keySpec)
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(s"$path\n$exp".getBytes(StandardCharsets.UTF_8)))
