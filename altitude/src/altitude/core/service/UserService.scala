package altitude.core.service

import com.typesafe.config.Config
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

import altitude.core._
import altitude.core.dao.RepositoryGrantDao
import altitude.core.dao.UserDao
import altitude.core.models.AuditEventType
import altitude.core.models.TokenClaims
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.NameKey
import altitude.core.util.Util
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

object UserService:

  /** What a login token is worth on a request (`UserService.checkToken`) */
  enum TokenCheck:
    // Minted at the account's current version: its claims hold as they are
    case Current(claims: TokenClaims)
    // Minted before a change to the account: its claims rebuilt from the database, and the token that replaces it
    case Renewed(claims: TokenClaims, token: String)
    // Not a token the server minted, expired, revoked, stale where it cannot be replaced, or of an account deactivated or deleted
    case Refused

  // A bcrypt hash of no password anyone has, checked for a login that names no account, so that answer takes as long as a wrong
  // password's and does not tell whether the account exists
  private val DUMMY_HASH = "$2a$10$b58qVLgVVVxh9C4.bF9JjuIB5nbMgw7MrQ69ysrJAXSJG.cdAjbSa"

  // How much of a failed login, as typed, its audit event keeps: enough to tell which account was tried, and little of a password
  // typed into the login field by mistake
  private val MAX_AUDITED_LOGIN_LENGTH = 64

  // How long a dev login that failed is not tried again, so a page of thumbnails does not cost a bcrypt check and an audit event
  // each
  private val DEV_LOGIN_RETRY_AFTER = Duration.ofSeconds(30)

  /** When a token minted at `now` expires: 7 days later, to the second, as the token keeps it */
  def tokenExpiry(now: Instant = Instant.now): Instant =
    now.plus(Duration.ofDays(Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS)).truncatedTo(ChronoUnit.SECONDS)

  /**
   * The dev user's login and password (`dev.user`, a username or an email address, and `dev.password`), development-only settings
   * (`Environment.devSetting`): None outside the dev environment, where they are ignored with a WARN, and when either is empty
   */
  def devCredentials(config: Config, environment: String = Environment.CURRENT): Option[(String, String)] =
    val login = Environment.devSetting(config, Const.Conf.DEV_USER, environment)
    val password = Environment.devSetting(config, Const.Conf.DEV_PASSWORD, environment)
    Option.when(login.nonEmpty && password.nonEmpty)((login, password))

class UserService(val app: Altitude) extends BaseService[User]:
  protected val dao: UserDao = app.DAO.user
  private val grantDao: RepositoryGrantDao = app.DAO.repositoryGrant

  override protected val txManager: TransactionManager = app.txManager

  private def versions: AccountVersions = app.service.accountVersions

  def switchContextToUser(user: User): Unit =
    RequestContext.account.value = Some(user)

  /**
   * Logs an account in by its username or its email address, either in any case or Unicode form: the login is folded with
   * `NameKey.of` and compared to `email_key` when the folded login holds an "@" (a username never does), to `username_key`
   * otherwise. The account, a session token of its claims and when the token expires, with the account made the request's; None
   * for a login that names no account, a wrong password or a deactivated account alike, so none of them can be told from the
   * others. A login with a control character names no account (no username or address holds one, and PostgreSQL refuses a NUL in
   * a text parameter), so it is not looked up.
   *
   * The lookup and the password check only read, so on SQLite the slow check holds no write connection, which import writes queue
   * for. The last login time and the audit event (success, or failure with the account named if any and the login as typed, made
   * printable and cut short; both with `clientIp`) are written after it in a short transaction of their own.
   */
  def loginAndSetUser(login: String, password: String, clientIp: Option[String] = None): Option[(User, String, Instant)] =
    val key = NameKey.of(login)

    val (found, passwordMatches) = txManager.asReadOnly {
      val found = if login.exists(_.isControl) then None else dao.getWithPasswordHash(key, byEmail = key.contains('@'))
      (found, Util.checkPassword(password, found.fold(UserService.DUMMY_HASH)(_._2)))
    }

    def failed(): None.type =
      txManager.withTransaction {
        app.service.audit.record(
          AuditEventType.LoginFailed,
          actor = None,
          target = found.map(_._1),
          detail = ujson.Obj("login" -> Util.printable(login, UserService.MAX_AUDITED_LOGIN_LENGTH)),
          clientIp = clientIp
        )
      }
      None

    found.filter((account, _) => passwordMatches && account.isActive) match
      case None => failed()
      case Some((account, checkedHash)) =>
        val id = account.persistedId
        // The version is the account's, so it is read once the lookup has named the account, and before the transaction that
        // reads the account again: a password change committed before this read is seen there, and a revocation after it leaves
        // the token below the revoked-below version
        val version = versions.current(id)
        val expiresAt = UserService.tokenExpiry()

        val claims = txManager.withTransaction {
          // The account locked, so a write of it in flight is waited for and read. A password changed since the check, or an
          // account deactivated or deleted since, fails the login as any refusal does.
          val claims = dao
            .lockWithPasswordHash(id)
            .filter((_, hash) => hash == checkedHash)
            .flatMap((locked, _) => claimsFor(locked, version, expiresAt))
          if claims.isDefined then
            dao.recordLogin(id)
            app.service.audit.record(AuditEventType.LoginSucceeded, actor = Some(account), clientIp = clientIp)
          claims
        }

        claims match
          case None => failed()
          case Some(claims) =>
            switchContextToUser(claims.account)
            Some((account, app.service.paseto.createToken(claims), claims.expiresAt))

  /**
   * What a login token is worth on a request, the account made the request's unless it is refused. A token minted at the
   * account's current version (`AccountVersions`) holds as it is, read with no database query. A token minted before a change to
   * the account is rebuilt from the database, with its expiry, when `renewable` (a cookie, which the answer replaces) and the
   * account is still active; a stale token that cannot be replaced (a Bearer header), one of an account deactivated or deleted
   * since, and one minted before its account's tokens were revoked, are refused. The token is judged and rebuilt against one
   * snapshot of the account's versions, so a revocation that lands meanwhile leaves the rebuilt token below it.
   */
  def checkToken(token: String, renewable: Boolean): UserService.TokenCheck =
    app.service.paseto.readToken(token) match
      case None => UserService.TokenCheck.Refused
      case Some(claims) =>
        val id = claims.account.persistedId
        val snapshot = versions.snapshot(id)
        snapshot.standing(claims.version) match
          case AccountVersions.Standing.Current =>
            switchContextToUser(claims.account)
            UserService.TokenCheck.Current(claims)
          case AccountVersions.Standing.Revoked =>
            logger.info(s"Refused a revoked token of account [$id]")
            UserService.TokenCheck.Refused
          case AccountVersions.Standing.Stale if !renewable =>
            logger.info(s"Refused a stale token of account [$id] that cannot be replaced")
            UserService.TokenCheck.Refused
          case AccountVersions.Standing.Stale =>
            renew(claims, snapshot) match
              case None =>
                logger.info(s"Refused a stale token of account [$id], which is deactivated or gone")
                UserService.TokenCheck.Refused
              case Some((fresh, renewed)) =>
                logger.debug(s"Rebuilt the token of account [$id] at version [${snapshot.current}]")
                switchContextToUser(fresh.account)
                UserService.TokenCheck.Renewed(fresh, renewed)

  /**
   * The claims of a stale token rebuilt from the database, with its expiry, at the current version of `at`, the snapshot its
   * standing was read from (read before the account is), and the token that carries them; None when the account is deactivated or
   * gone
   */
  private[core] def renew(claims: TokenClaims, at: AccountVersions.Snapshot): Option[(TokenClaims, String)] =
    txManager
      .asReadOnly(claimsAt(claims.account.persistedId, at.current, claims.expiresAt))
      .map(fresh => (fresh, app.service.paseto.createToken(fresh)))

  /**
   * The claims of the account as the database has them now, at its current version, for a token that expires as one minted now
   * does; None when the account is deactivated or does not exist
   */
  def claimsOf(accountId: String): Option[TokenClaims] =
    val version = versions.current(accountId)
    txManager.asReadOnly {
      claimsAt(accountId, version, UserService.tokenExpiry())
    }

  // The account and its grants, read in the caller's transaction, which began after `version` was read
  private def claimsAt(accountId: String, version: Long, expiresAt: Instant): Option[TokenClaims] =
    val account =
      try Some(dao.getById(accountId))
      catch case _: NotFoundException => None
    account.flatMap(claimsFor(_, version, expiresAt))

  // The claims of `account` with its grants, read in the caller's transaction; None when it is deactivated
  private def claimsFor(account: User, version: Long, expiresAt: Instant): Option[TokenClaims] =
    Option.when(account.isActive) {
      val grants = grantDao.getByAccount(account.persistedId).map(grant => grant.repositoryId -> grant.permissions).toMap
      TokenClaims.of(account, grants, version, expiresAt)
    }

  /**
   * Ends a session: the SessionController clears its cookie. Nothing is kept server-side, so a copy of the token stays valid
   * until it expires or its account's tokens are revoked (`AccountVersions.revoke`).
   */
  def logout(token: String): Unit =
    logger.debug("Logging out user (stateless - clearing client cookie only)")

  override def add(objIn: User): User =
    throw NotImplementedError("Use the alternate add() method with password")

  /**
   * Adds an account that keeps to the rules of its username, name and email address (`User.valid`, whose `ValidationException`
   * this passes on), its name stored normalized
   */
  def add(objIn: User, password: String): User =
    val account = User.valid(objIn)

    txManager.withTransaction {
      logger.debug(s"Adding user [${account.email}]")
      val passwordHash = Util.hashPassword(password)
      // An email address and a username each name one account under their name keys (`account_01`, `account_02`)
      try dao.addUser(account, passwordHash)
      catch case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
    }

  /**
   * Records `repoId` as the user's last active Library, the one login and `/` open next
   * (`RepositoryService.landingRepositoryOf`). Whether it wrote. Every page load of a Library calls it, so it reads the recorded
   * Library first, in a read-only transaction, and opens a write transaction only when that differs: a load of the Library
   * recorded already takes no write lock (on SQLite, the one write connection). The write is still the conditional UPDATE, so of
   * two loads racing, the second writes nothing.
   */
  def rememberLastActiveRepository(user: User, repoId: String): Boolean =
    val recorded = txManager.asReadOnly {
      dao.getById(user.persistedId).lastActiveRepoId
    }

    !recorded.contains(repoId) && txManager.withTransaction {
      val written = dao.rememberLastActiveRepository(user.persistedId, repoId)
      if written then logger.debug(s"Recorded [$repoId] as the last active repository of user [${user.persistedId}]")
      written
    }

  // The dev user's credentials, read once at startup: none outside the dev environment
  private val devCredentials: Option[(String, String)] = UserService.devCredentials(app.config)

  // The dev user's ID once its password has checked out, with the credentials it is for: every request asks for the dev user, a
  // page of thumbnails included, and a password check is a deliberately slow bcrypt
  private val verifiedDevUser = AtomicReference[Option[((String, String), String)]](None)

  // When a dev login last failed, with the credentials it is for, so it is not tried again on every request
  private val failedDevLogin = AtomicReference[Option[((String, String), Instant)]](None)

  /** The dev user's claims (`devUser`), when the dev credentials are set and the environment is dev */
  def getDevUser: Option[TokenClaims] = devCredentials.flatMap(devUser)

  /**
   * The claims of the account `credentials` log in, its account made the request's, or None when they do not log in. The password
   * is checked until it first succeeds; after that the claims are built from the database on every request (`claimsOf`), so a
   * change to the account is seen at once, and an account that is gone or deactivated is logged in again. A login that fails is
   * not tried again for `DEV_LOGIN_RETRY_AFTER`, so it costs one bcrypt check and one audit event, not one per request.
   */
  private[core] def devUser(credentials: (String, String)): Option[TokenClaims] =
    val now = Instant.now
    def failedRecently =
      failedDevLogin.get.exists((failed, at) => failed == credentials && at.plus(UserService.DEV_LOGIN_RETRY_AFTER).isAfter(now))

    verifiedDevUser.get.filter(_._1 == credentials).flatMap((_, id) => claimsOf(id)) match
      case Some(claims) =>
        switchContextToUser(claims.account)
        Some(claims)
      case None if failedRecently => None
      case None =>
        val (login, password) = credentials
        loginAndSetUser(login, password).map(_._1) match
          case Some(account) =>
            verifiedDevUser.set(Some((credentials, account.persistedId)))
            claimsOf(account.persistedId)
          case None =>
            logger.warn(s"The dev user [${Util.printable(login, UserService.MAX_AUDITED_LOGIN_LENGTH)}] does not log in")
            verifiedDevUser.set(None)
            failedDevLogin.set(Some((credentials, now)))
            None
