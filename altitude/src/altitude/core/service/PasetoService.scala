package altitude.core.service

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.models.User
import dev.paseto.jpaseto.Paseto
import dev.paseto.jpaseto.PasetoParser
import dev.paseto.jpaseto.Pasetos
import dev.paseto.jpaseto.lang.Keys
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.SecretKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PasetoService(val app: Altitude) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  // Initialize BouncyCastle provider for PASETO
  // This is a pure Java implementation that doesn't require native libraries
  private def initializeBouncyCastle(): Unit = {
    try {
      val bcProvider = Class
        .forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[java.security.Provider]
      java.security.Security.addProvider(bcProvider)
      logger.info("BouncyCastle provider initialized for PASETO")
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to initialize BouncyCastle provider: ${e.getMessage}")
    }
  }

  initializeBouncyCastle()

  // Generate a secret key for PASETO local tokens (symmetric encryption)
  // In production, this should be loaded from configuration or environment variables
  private val secretKey: SecretKey = {
    val random = new SecureRandom()
    val keyBytes = new Array[Byte](32) // 256 bits for PASETO v2 local
    random.nextBytes(keyBytes)
    Keys.secretKey(keyBytes)
  }

  private val tokenExpirationDays: Int = Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS

  /** Creates a PASETO token for the given user. The token contains the user ID and expiration time. */
  def createToken(user: User): String = {
    val now = Instant.now()
    val expiration = now.plus(Duration.ofDays(tokenExpirationDays))

    val builder = Pasetos.V2.LOCAL
      .builder()
      .setSharedSecret(secretKey)
      .setIssuedAt(now)
      .setExpiration(expiration)
      .setSubject(user.persistedId)
      .claim("email", user.email)
      .claim("name", user.name)
      .claim("accountType", user.accountType.toString)

    // Add lastActiveRepoId if present
    user.lastActiveRepoId.foreach(repoId => builder.claim("lastActiveRepoId", repoId))

    builder.compact()
  }

  /** Validates a PASETO token and returns the user ID if valid. Returns None if the token is invalid or expired. */
  def validateToken(token: String): Option[String] = {
    try {
      val parser: PasetoParser = Pasetos
        .parserBuilder()
        .setSharedSecret(secretKey)
        .build()

      val parsedToken: Paseto = parser.parse(token)
      val claims = parsedToken.getClaims

      val expiration = claims.getExpiration
      if (expiration != null && expiration.isBefore(Instant.now())) {
        logger.debug("Token has expired")
        return None
      }

      val userId = claims.getSubject
      if (userId == null || userId.isEmpty) {
        logger.debug("Token has no subject (user ID)")
        return None
      }

      Some(userId)
    } catch {
      case e: Exception =>
        logger.debug(s"Token validation failed: ${e.getMessage}")
        None
    }
  }

  /**
   * Validates a PASETO token and returns the User object if valid. Returns None if the token is invalid or expired.
   *
   * The User object is reconstructed from the token claims - no database query.
   */
  def validateTokenAndGetUser(token: String): Option[User] = {
    try {
      val parser: PasetoParser = Pasetos
        .parserBuilder()
        .setSharedSecret(secretKey)
        .build()

      val parsedToken: Paseto = parser.parse(token)
      val claims = parsedToken.getClaims

      val expiration = claims.getExpiration
      if (expiration != null && expiration.isBefore(Instant.now())) {
        logger.debug("Token has expired")
        return None
      }

      val userId = claims.getSubject
      if (userId == null || userId.isEmpty) {
        logger.debug("Token has no subject (user ID)")
        return None
      }

      // Extract user data from token claims
      val email = claims.get("email", classOf[String])
      val name = claims.get("name", classOf[String])
      val accountTypeStr = claims.get("accountType", classOf[String])
      val lastActiveRepoId = Option(claims.get("lastActiveRepoId", classOf[String]))

      if (email.isEmpty || name.isEmpty || accountTypeStr.isEmpty) {
        logger.debug("Token missing required user claims")
        return None
      }

      // Reconstruct User from token claims
      import altitude.core.models.{ AccountType, User }
      val user = User(
        id = Some(userId),
        email = email,
        name = name,
        accountType = AccountType.valueOf(accountTypeStr),
        lastActiveRepoId = lastActiveRepoId
      )

      Some(user)
    } catch {
      case e: Exception =>
        logger.debug(s"Token validation failed: ${e.getMessage}")
        None
    }
  }
}
