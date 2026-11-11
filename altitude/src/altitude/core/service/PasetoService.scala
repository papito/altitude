package altitude.core.service

import dev.paseto.jpaseto.Pasetos
import dev.paseto.jpaseto.lang.Keys
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

import altitude.core.Altitude
import altitude.core.models.AccountType
import altitude.core.models.Permissions
import altitude.core.models.TokenClaims
import altitude.core.models.User

object PasetoService:

  /**
   * The longest token the session cookie carries: a browser keeps 4096 bytes of a cookie, its name and attributes included (about
   * 130 bytes as `SessionController.authCookie` writes them). `RepositoryService.MAX_GRANTS_PER_ACCOUNT` and the account rules
   * (`User.valid`) keep every token within it; one past it is logged at ERROR.
   */
  val MAX_TOKEN_BYTES = 3950

class PasetoService(val app: Altitude):
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  // Initialize BouncyCastle provider for PASETO
  private def initializeBouncyCastle(): Unit =
    try
      val bcProvider = Class
        .forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[java.security.Provider]
      java.security.Security.addProvider(bcProvider)
      logger.debug("BouncyCastle provider initialized for PASETO")
    catch
      case e: Exception =>
        logger.warn(s"Failed to initialize BouncyCastle provider: ${e.getMessage}")

  initializeBouncyCastle()

  // Generate a secret key for PASETO local tokens (symmetric encryption)
  private val secretKey: SecretKey =
    val random = new SecureRandom()
    val keyBytes = new Array[Byte](32) // 256 bits for PASETO v2 local
    random.nextBytes(keyBytes)
    Keys.secretKey(keyBytes)

  private object Claim:
    val EMAIL = "email"
    val USERNAME = "username"
    val NAME = "name"
    val ACCOUNT_TYPE = "accountType"
    val CAN_CREATE_LIBRARIES = "canCreateLibraries"
    // A JSON object of Library ID to Permission bitmask
    val GRANTS = "grants"
    val VERSION = "ver"

  /**
   * A token of `claims`: the account's ID as the subject, its email, username, name, account type and Create Libraries flag, its
   * grants (none for an Admin) and its version, expiring at `claims.expiresAt`. It does not carry the last active Library, which
   * changes with every switch the token outlives: that is read from the database. The grants make most of its length, which
   * bounds how many an account may hold (`RepositoryService.MAX_GRANTS_PER_ACCOUNT`).
   */
  def createToken(claims: TokenClaims): String =
    val token = compact(claims)
    if token.length > PasetoService.MAX_TOKEN_BYTES then
      logger.error(
        s"The token of account [${claims.account.persistedId}] is [${token.length}] bytes, past the cookie's budget of " +
          s"[${PasetoService.MAX_TOKEN_BYTES}]: a browser may drop its session cookie")
    token

  private def compact(claims: TokenClaims): String =
    val account = claims.account
    val grants = java.util.LinkedHashMap[String, Integer]()
    claims.grants.foreach((repositoryId, permissions) => grants.put(repositoryId, permissions.mask))

    Pasetos.V2.LOCAL
      .builder()
      .setSharedSecret(secretKey)
      .setIssuedAt(Instant.now())
      .setExpiration(claims.expiresAt)
      .setSubject(account.persistedId)
      .claim(Claim.EMAIL, account.email)
      .claim(Claim.USERNAME, account.username)
      .claim(Claim.NAME, account.name)
      .claim(Claim.ACCOUNT_TYPE, account.accountType.toString)
      .claim(Claim.CAN_CREATE_LIBRARIES, account.canCreateLibraries)
      .claim(Claim.GRANTS, grants)
      .claim(Claim.VERSION, claims.version)
      .compact()

  /**
   * The claims of a token this process minted, read with no database query, so the account has no last active Library; None for a
   * token that does not decrypt under the key, has expired, or lacks a claim or carries one that is not what `createToken` writes
   * (a Permission bit that does not exist among them). The one place a token's claims are read.
   */
  def readToken(token: String): Option[TokenClaims] =
    try
      val claims = Pasetos.parserBuilder().setSharedSecret(secretKey).build().parse(token).getClaims

      if claims.getExpiration == null || claims.getExpiration.isBefore(Instant.now()) then
        logger.debug("Token has expired")
        return None

      def required[T](name: String, of: Class[T]): T =
        Option(claims.get(name, of)).getOrElse(throw IllegalArgumentException(s"No [$name] claim"))

      val account = User(
        id = Some(Option(claims.getSubject).filter(_.nonEmpty).getOrElse(throw IllegalArgumentException("No subject"))),
        email = required(Claim.EMAIL, classOf[String]),
        username = required(Claim.USERNAME, classOf[String]),
        name = required(Claim.NAME, classOf[String]),
        accountType = AccountType.valueOf(required(Claim.ACCOUNT_TYPE, classOf[String])),
        canCreateLibraries = required(Claim.CAN_CREATE_LIBRARIES, classOf[java.lang.Boolean]).booleanValue
      )
      val grants: Map[String, Permissions] = required(Claim.GRANTS, classOf[java.util.Map[?, ?]]).asScala.toMap.map {
        case (repositoryId: String, mask: Number) => repositoryId -> Permissions(mask.intValue)
        case grant => throw IllegalArgumentException(s"Not a grant: $grant")
      }
      val version = required(Claim.VERSION, classOf[Number]).longValue

      Some(TokenClaims.of(account, grants, version, claims.getExpiration))
    catch
      case e: Exception =>
        logger.debug(s"Token validation failed: ${e.getMessage}")
        None
