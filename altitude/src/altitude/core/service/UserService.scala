package altitude.core.service

import com.typesafe.config.Config
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.MDC

import altitude.core._
import altitude.core.dao.RepositoryGrantDao
import altitude.core.dao.UserDao
import altitude.core.models.AccountSnapshot
import altitude.core.models.AccountType
import altitude.core.models.AuditEventType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.RepositoryGrant
import altitude.core.models.TokenClaims
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.NameKey
import altitude.core.util.NaturalOrder
import altitude.core.util.Util
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

object UserService:

  /** What a login token is worth on a request (`UserService.checkToken`) */
  enum TokenCheck:
    // Minted at the account's current version: its claims hold as they are
    case Current(claims: TokenClaims)
    // Minted before a change to the account: its claims rebuilt from the database, and the token that replaces it
    case Renewed(claims: TokenClaims, token: String)
    // Not minted here, expired, revoked, stale where it cannot be replaced, or of a deactivated or deleted account.
    // `clearsCookie` when the token will never be good again; one refused only as revoked or stale leaves the cookie, as the
    // browser may hold a newer token by now (a request in flight while its account changed its own password)
    case Refused(clearsCookie: Boolean)

  /** An account as an Admin's edit form opens it, with its grants and their fingerprint, which the form carries back on save */
  final case class EditForm(account: User, grants: Map[String, Permissions], state: String)

  /** When an account was added and when it last logged in (any session of it) */
  final case class AccountTimes(createdAt: Option[Instant], lastLoginAt: Option[Instant])

  /**
   * The fingerprint of what an Admin's edit shows of an account and its grants: another Admin's edit or a new Library's grant
   * changes it; a write touching none of it (a login, the last active Library) does not
   */
  def editState(account: User, grants: Map[String, Permissions]): String =
    val fields = List(
      account.name,
      account.username,
      account.email,
      account.accountType.toString,
      account.canCreateLibraries.toString,
      account.grantsNewLibraries.toString,
      account.newLibraryPermissions.mask.toString
    ) ++ grants.toList.sortBy(_._1).map((repositoryId, permissions) => s"$repositoryId:${permissions.mask}")
    // A NUL separates the fields, as none of them holds one
    altitude.core.util.Sha256.hex(fields.mkString("\u0000").getBytes(java.nio.charset.StandardCharsets.UTF_8))

  // A bcrypt hash of no password anyone has, checked for a login that names no account, so that answer takes as long as a wrong
  // password's and does not tell whether the account exists
  private val DUMMY_HASH = "$2a$10$b58qVLgVVVxh9C4.bF9JjuIB5nbMgw7MrQ69ysrJAXSJG.cdAjbSa"

  // How much of a failed login, as typed, its audit event keeps: enough to tell which account was tried, and little of a password
  // typed into the login field by mistake
  private val MAX_AUDITED_LOGIN_LENGTH = 64

  // How long a failed dev login is not retried, so a page of thumbnails does not cost a bcrypt check and an audit event each
  private val DEV_LOGIN_RETRY_AFTER = Duration.ofSeconds(30)

  /** When a token minted at `now` expires: 7 days later, to the second, as the token keeps it */
  def tokenExpiry(now: Instant = Instant.now): Instant =
    now.plus(Duration.ofDays(Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS)).truncatedTo(ChronoUnit.SECONDS)

  /**
   * The dev user's login (a username or an email address) and password, dev-only settings: None outside the dev environment,
   * where they are ignored with a WARN, and when either is empty
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

  /**
   * Makes `user` the account of the thread's request, and its ID the user of the lines the thread logs from here on (the MDC's
   * `USER`): the ID alone, never a name, username or email address. Every way a request gets its account ends here: a session
   * cookie's or a Bearer header's token ([[checkToken]]), the dev login, a login ([[loginAndSetUser]]) and Setup. The request
   * logger clears the MDC as the thread's next request starts.
   */
  def switchContextToUser(user: User): Unit =
    RequestContext.account.value = Some(user)
    MDC.put(Const.LogContext.USER, user.persistedId)

  /**
   * Logs an account in by username or email address in any case or Unicode form: the folded login is compared to `email_key` when
   * it holds an "@" (no username does), else to `username_key`. Returns the account, a session token and its expiry; None alike
   * for an unknown login, a wrong password or a deactivated account, so they cannot be told apart. A login with a control
   * character is not looked up, as none holds one and PostgreSQL refuses a NUL in a text parameter.
   *
   * The lookup and the slow password check only read, so on SQLite they hold no write connection, which imports queue for. The
   * last login time and the audit event are written after, in a short transaction of their own.
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
        // Read before the transaction that reads the account again: a password change committed before this read is seen there,
        // and a revocation after it leaves the token below the revoked version
        val version = versions.current(id)
        val expiresAt = UserService.tokenExpiry()

        val claims = txManager.withTransaction {
          // Locked, so a write of the account in flight is waited for. A password changed since the check, or an account
          // deactivated or deleted since, fails the login as any refusal does.
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
   * What a login token is worth on a request; unless refused, its account is made the request's. A token at the account's current
   * version (`AccountVersions`) holds with no database query. A stale one is rebuilt from the database, keeping its expiry, when
   * `renewable` (a cookie, which the answer replaces) and the account is still active; otherwise it is refused, as is one minted
   * before a revocation. Judging and rebuilding use one snapshot of the versions, so a revocation landing meanwhile leaves the
   * rebuilt token below it. A revoked cookie token's account is read to tell whether the cookie should go, as a deactivation
   * revokes too.
   */
  def checkToken(token: String, renewable: Boolean): UserService.TokenCheck =
    app.service.paseto.readToken(token) match
      case None => UserService.TokenCheck.Refused(clearsCookie = true)
      case Some(claims) =>
        val id = claims.account.persistedId
        val snapshot = versions.snapshot(id)
        snapshot.standing(claims.version) match
          case AccountVersions.Standing.Current =>
            switchContextToUser(claims.account)
            UserService.TokenCheck.Current(claims)
          case AccountVersions.Standing.Revoked =>
            logger.info(s"Refused a revoked token of account [$id]")
            UserService.TokenCheck.Refused(clearsCookie = renewable && !isActive(id))
          case AccountVersions.Standing.Stale if !renewable =>
            logger.info(s"Refused a stale token of account [$id] that cannot be replaced")
            UserService.TokenCheck.Refused(clearsCookie = false)
          case AccountVersions.Standing.Stale =>
            renew(claims, snapshot) match
              case None =>
                logger.info(s"Refused a stale token of account [$id], which is deactivated or gone")
                UserService.TokenCheck.Refused(clearsCookie = true)
              case Some((fresh, renewed)) =>
                logger.debug(s"Rebuilt the token of account [$id] at version [${snapshot.current}]")
                switchContextToUser(fresh.account)
                UserService.TokenCheck.Renewed(fresh, renewed)

  // Whether the account exists and is active, as stored
  private def isActive(accountId: String): Boolean =
    txManager.asReadOnly {
      try dao.getById(accountId).isActive
      catch case _: NotFoundException => false
    }

  /**
   * A stale token's claims rebuilt from the database at the current version of `at`, the snapshot its standing was read from,
   * with the token carrying them; None when the account is deactivated or gone
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
   * Nothing is kept server-side: ending a session only clears its cookie, so a copy of the token stays valid until it expires or
   * its account's tokens are revoked
   */
  def logout(token: String): Unit =
    logger.debug("Logging out user (stateless - clearing client cookie only)")

  override def add(objIn: User): User =
    throw NotImplementedError("Use the alternate add() method with password")

  /** Adds an account held to `User.valid`, its name stored normalized */
  def add(objIn: User, password: String): User =
    val account = User.valid(objIn)

    txManager.withTransaction {
      logger.debug(s"Adding user [${account.email}]")
      val passwordHash = Util.hashPassword(password)
      // An email address and a username each name one account under their name keys (`account_01`, `account_02`)
      try dao.addUser(account, passwordHash)
      catch case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
    }

  /*
   * Account management. Each write runs in one transaction with its audit event and makes the account's tokens stale once it
   * commits (a password change revokes them), so its sessions see the change at their next request. The acting account is read
   * in the write's transaction, never taken from the token.
   */

  /**
   * Adds an account recorded as `creatorId`'s, held to the account rules ([[valid]]) and its type; a taken email address or
   * username is a `ValidationException` on that field. A creator who is no Admin is held to its ceiling ([[withinCeiling]]), and
   * one deactivated since its login creates nothing; both are a `ForbiddenException`.
   */
  def create(
      creatorId: String,
      model: User,
      password: String,
      grants: Map[String, Permissions],
      clientIp: Option[String] = None): User =
    val (account, accountGrants) = valid(model, grants, Some(password))
    val passwordHash = Util.hashPassword(password)

    txManager.withTransaction {
      // The creator's row is locked, so a deactivation or a change of its type in flight is waited for and read
      val creator = dao.lockById(creatorId)
      if !creator.isActive then throw ForbiddenException(Const.Msg.Err.FORBIDDEN_INACTIVE)
      if creator.accountType != AccountType.Admin then withinCeiling(creator, model, accountGrants)

      val added = whileKeysFree(dao.addUser(account.copy(isActive = true, createdByAccountId = Some(creatorId)), passwordHash))
      app.service.repository.replaceGrants(added.persistedId, accountGrants)
      app.service.audit.record(
        AuditEventType.AccountCreated,
        actor = Some(creator),
        target = Some(added),
        detail = ujson.Obj("accountType" -> added.accountType.toString, "grants" -> grantsJson(accountGrants)),
        clientIp = clientIp
      )
      added
    }

  /**
   * An Admin's change to an account, held to the rules of [[create]] (the password aside) and to its type: a change to Admin
   * drops the grants, a change to Guest keeps the Libraries with no Permission. An Admin may not take the Admin type from itself.
   * The active Admins are locked first and the actor must be one of them, so two demotions take turns and one active Admin is
   * always left. A changed name, username or email rewrites the importer record of every asset the account imported. A save over
   * an account changed since `openedState` ([[UserService.editState]]) writes nothing. Returns the account as stored.
   */
  def update(
      actorId: String,
      model: User,
      grants: Map[String, Permissions],
      openedState: String,
      clientIp: Option[String] = None): User =
    val (account, accountGrants) = valid(model, grants, None)

    txManager.withTransaction {
      val admins = dao.lockActiveAdmins()
      val actor = activeAdmin(actorId, admins)
      val stored = dao.lockById(account.persistedId)
      // Read under the account's row lock, which every write of the account and of its grants takes (a new Library's grant
      // included), so the state compared is the one the save replaces
      val heldGrants = grantsOf(stored.persistedId)
      if UserService.editState(stored, heldGrants) != openedState then
        logger.info(s"Account [$actorId] saved account [${stored.persistedId}] over a change since its form was opened")
        throw fieldError(FieldConst.User.OPENED_STATE, Const.Msg.Err.ACCOUNT_CHANGED)
      val typeChanged = stored.accountType != account.accountType

      if typeChanged && stored.accountType == AccountType.Admin && stored.id == actor.id then
        throw fieldError(FieldConst.User.ACCOUNT_TYPE, Const.Msg.Err.OWN_ACCOUNT)

      whileKeysFree(dao.update(account))
      // An account that is no Admin any more owns no Watch folder's assets: its Watch folders pause until an Admin takes them
      if typeChanged && stored.accountType == AccountType.Admin then
        app.service.watchFolder.pauseOwnedBy(stored.persistedId, Const.WatchFolder.OWNER_DEMOTED)
      // Bumps the account's version once committed, so the update needs no bump of its own
      app.service.repository.replaceGrants(stored.persistedId, accountGrants)

      val snapshot = AccountSnapshot.of(account)
      if snapshot != AccountSnapshot.of(stored) then
        val rewritten = app.DAO.asset.rewriteImporterRecords(snapshot)
        logger.debug(s"Rewrote the importer record of [$rewritten] assets of account [${stored.persistedId}]")

      def record(event: AuditEventType, detail: ujson.Obj): Unit =
        app.service.audit.record(event, actor = Some(actor), target = Some(account), detail = detail, clientIp = clientIp): Unit

      val edited = List(
        "name" -> (stored.name != account.name),
        "username" -> (stored.username != account.username),
        "email" -> (stored.email != account.email),
        "canCreateLibraries" -> (stored.canCreateLibraries != account.canCreateLibraries)
      ).collect { case (field, true) => field }
      if edited.nonEmpty then record(AuditEventType.AccountEdited, ujson.Obj("changed" -> ujson.Arr.from(edited)))
      if typeChanged then
        record(
          AuditEventType.AccountTypeChanged,
          ujson.Obj("from" -> stored.accountType.toString, "to" -> account.accountType.toString))
      if heldGrants != accountGrants || stored.grantsNewLibraries != account.grantsNewLibraries ||
        stored.newLibraryPermissions != account.newLibraryPermissions
      then
        record(
          AuditEventType.PermissionsChanged,
          ujson.Obj(
            "grants" -> grantsJson(accountGrants),
            "newLibraries" -> (if account.grantsNewLibraries then permissionsJson(account.newLibraryPermissions) else ujson.Null)
          )
        )

      dao.getById(stored.persistedId)
    }

  /** When the account was added and when it last logged in, as stored */
  def accountTimes(accountId: String): UserService.AccountTimes =
    txManager.asReadOnly {
      val account = dao.getById(accountId)
      UserService.AccountTimes(account.createdAt.map(dao.instantOf), account.lastLoginAt.map(dao.instantOf))
    }

  /** The account as an Admin's edit form opens it, with its grants and their state, read in one transaction */
  def openForEdit(accountId: String): UserService.EditForm =
    txManager.asReadOnly {
      val account = dao.getById(accountId)
      val grants = grantsOf(accountId)
      UserService.EditForm(account, grants, UserService.editState(account, grants))
    }

  // The account's grants, Library ID to Permissions, read in the caller's transaction
  private def grantsOf(accountId: String): Map[String, Permissions] =
    grantDao.getByAccount(accountId).map(grant => grant.repositoryId -> grant.permissions).toMap

  /**
   * An Admin deactivates an account: its tokens are refused for good, so once reactivated it logs in again. Refused as [[delete]]
   * is.
   */
  def deactivate(actorId: String, accountId: String, clientIp: Option[String] = None): Unit =
    changeActive(actorId, accountId, active = false, clientIp)

  def reactivate(actorId: String, accountId: String, clientIp: Option[String] = None): Unit =
    changeActive(actorId, accountId, active = true, clientIp)

  // An account deactivated, or reactivated, by an Admin: one already so is left as it is
  private def changeActive(actorId: String, accountId: String, active: Boolean, clientIp: Option[String]): Unit =
    txManager.withTransaction {
      val admins = dao.lockActiveAdmins()
      val actor = activeAdmin(actorId, admins)
      val target = dao.lockById(accountId)
      if !active then refuseRemoval(actor, target)

      if target.isActive != active then
        dao.setActive(accountId, active)
        if !active then app.service.watchFolder.pauseOwnedBy(accountId, Const.WatchFolder.OWNER_DEACTIVATED)
        app.service.audit.record(
          if active then AuditEventType.AccountReactivated else AuditEventType.AccountDeactivated,
          actor = Some(actor),
          target = Some(target),
          clientIp = clientIp)
        // A deactivation refuses the account's tokens for good, so a reactivated account logs in again; a reactivation only
        // makes them stale, there being none left to rebuild
        txManager.afterCommit(if active then versions.bump(accountId) else versions.revoke(accountId))
    }

  /**
   * An Admin deletes an account for good: every asset it owns, in every Library, becomes the Admin's, and the row's delete takes
   * its grants and clears the creator of the accounts it created. Importer records stay as they are. Refused for the actor's own
   * account ([[refuseRemoval]]). Returns how many assets moved.
   */
  def delete(actorId: String, accountId: String, clientIp: Option[String] = None): Int =
    txManager.withTransaction {
      val admins = dao.lockActiveAdmins()
      val actor = activeAdmin(actorId, admins)
      val target = dao.lockById(accountId)
      refuseRemoval(actor, target)

      val moved = app.DAO.asset.reassignOwner(accountId, actorId)
      app.service.watchFolder.passOn(accountId, actorId)
      dao.deleteById(accountId)
      app.service.audit.record(
        AuditEventType.AccountDeleted,
        actor = Some(actor),
        target = Some(target),
        detail = ujson.Obj("assetsMoved" -> moved),
        clientIp = clientIp)
      txManager.afterCommit(versions.bump(accountId))
      moved
    }

  /**
   * The account an imported file is inserted for, share-locked until the insert's transaction ends, so a delete or update of the
   * account waits and then sees the asset; an account gone or deactivated is an `ImporterUnavailableException` saying which
   */
  def lockImporter(accountId: String): User =
    txManager.withTransaction {
      dao.shareLock(accountId) match
        case None => throw ImporterUnavailableException(Const.Msg.Err.IMPORTER_GONE)
        case Some(account) if !account.isActive => throw ImporterUnavailableException(Const.Msg.Err.IMPORTER_DEACTIVATED)
        case Some(account) => account
    }

  /**
   * Share-locks the account, whatever its state, for a write of rows an account write of it rewrites (an import's completion of
   * an asset it owns), taken before them as an account write takes the account before them; nothing for an account gone
   */
  def shareLock(accountId: String): Unit =
    txManager.withTransaction {
      dao.shareLock(accountId): Unit
    }

  /**
   * The acting account of a write that stores a reference to it (an Edit's saver, a Watch folder's owner), share-locked as
   * [[lockImporter]] locks an importer. Taken before the write's first row: an account write locks the account before the rows it
   * changes, so the two then take turns rather than each holding what the other waits on. An account deleted or deactivated since
   * its login is refused, and with `mustBeAdmin` one that is no Admin by then (a Watch folder's owner must be an active Admin).
   */
  def lockActor(accountId: String, mustBeAdmin: Boolean = false): User =
    txManager.withTransaction {
      val actor =
        dao.shareLock(accountId).filter(_.isActive).getOrElse(throw ForbiddenException(Const.Msg.Err.FORBIDDEN_INACTIVE))
      if mustBeAdmin && actor.accountType != AccountType.Admin then throw ForbiddenException(Const.Msg.Err.FORBIDDEN_ADMIN)
      actor
    }

  /** How many assets the account owns, in every Library: what deleting it moves to the Admin */
  def countOwnedAssets(accountId: String): Int =
    txManager.asReadOnly {
      app.DAO.asset.countOwnedBy(accountId)
    }

  /** An Admin sets an account's password: every session of the account is refused from then on, the Admin's own included */
  def setPassword(actorId: String, accountId: String, password: String, clientIp: Option[String] = None): Unit =
    validPassword(FieldConst.User.PASSWORD, password).foreach(e => throw e)
    val passwordHash = Util.hashPassword(password)

    txManager.withTransaction {
      // The active Admins first, in ID order, as every Admin's write locks them, so two Admins setting each other's passwords
      // take turns
      val actor = activeAdmin(actorId, dao.lockActiveAdmins())
      val target = dao.lockById(accountId)
      writePassword(actor, target, passwordHash, clientIp)
    }

  /**
   * An account changes its own password, giving its current one; a wrong one is logged at WARN and audited. The current one is
   * checked in a read-only transaction, as a login's is, and the write refuses it too if the password changed meanwhile. Every
   * session of the account is refused from then on, the one making the change included.
   */
  def changeOwnPassword(
      accountId: String,
      currentPassword: String,
      newPassword: String,
      currentField: String,
      newField: String,
      clientIp: Option[String] = None): Unit =
    validPassword(newField, newPassword).foreach(e => throw e)

    val checkedHash = txManager.asReadOnly {
      val account = dao.getById(accountId)
      dao.getWithPasswordHash(account.usernameKey, byEmail = false).map(_._2)
    }
    val wrong = fieldError(currentField, Const.Msg.Err.WRONG_PASSWORD)
    if !checkedHash.exists(hash => Util.checkPassword(currentPassword, hash)) then
      logger.warn(s"Account [$accountId] gave a wrong current password from [${clientIp.getOrElse("unknown")}]")
      txManager.withTransaction {
        val account = dao.getById(accountId)
        app.service.audit
          .record(AuditEventType.PasswordChangeFailed, actor = Some(account), target = Some(account), clientIp = clientIp)
      }
      throw wrong
    val passwordHash = Util.hashPassword(newPassword)

    txManager.withTransaction {
      val (account, hash) = dao.lockWithPasswordHash(accountId).getOrElse(throw NotFoundException(s"No account [$accountId]"))
      if !checkedHash.contains(hash) || !account.isActive then throw wrong
      writePassword(account, account, passwordHash, clientIp)
    }

  // The password hash written, its event recorded, and the account's sessions refused once that commits
  private def writePassword(actor: User, target: User, passwordHash: String, clientIp: Option[String]): Unit =
    dao.setPasswordHash(target.persistedId, passwordHash)
    app.service.audit.record(AuditEventType.PasswordSet, actor = Some(actor), target = Some(target), clientIp = clientIp)
    txManager.afterCommit(versions.revoke(target.persistedId))

  /**
   * The accounts `viewer` sees, in natural name order, with their grants: all of them for an Admin; for anyone else, the
   * non-Admin accounts it created (an Admin may have promoted one since), with only their grants in Libraries the viewer holds a
   * grant in
   */
  def accountsVisibleTo(viewer: User): List[(User, List[RepositoryGrant])] =
    txManager.asReadOnly {
      val isAdmin = viewer.accountType == AccountType.Admin
      val accounts =
        if isAdmin then dao.getAll else dao.getCreatedBy(viewer.persistedId).filter(_.accountType != AccountType.Admin)
      val visible = if isAdmin then None else Some(grantDao.getByAccount(viewer.persistedId).map(_.repositoryId).toSet)
      val grants = grantDao
        .getByAccounts(accounts.map(_.persistedId).toSet)
        .filter(grant => visible.forall(_.contains(grant.repositoryId)))
        .groupBy(_.accountId)

      accounts.sortBy(_.name)(using NaturalOrder).map(account => account -> grants.getOrElse(account.persistedId, Nil))
    }

  /** The account and grants held to its type and the account rules, or a `ValidationException` with each failing field's error */
  private def valid(model: User, grants: Map[String, Permissions], password: Option[String]): (User, Map[String, Permissions]) =
    val (account, shapedGrants, errors) = checked(model, grants, password)
    errors.headOption.foreach {
      (_, message) =>
        val ex = ValidationException(message)
        ex.errors ++= errors
        throw ex
    }
    (account, shapedGrants)

  /**
   * The account-rule errors by field, as [[create]] refuses them, for a form to show beside its own. It reads no database, so a
   * taken address or username is not among them.
   */
  def fieldErrors(model: User, grants: Map[String, Permissions], password: Option[String]): Map[String, String] =
    checked(model, grants, password)._3.toMap

  // The account and its grants held to its type and to the rules, with the error of every field that fails, in field order
  private def checked(
      model: User,
      grants: Map[String, Permissions],
      password: Option[String]): (User, Map[String, Permissions], scala.collection.mutable.LinkedHashMap[String, String]) =
    val (shaped, shapedGrants) = shapedByType(model, grants)
    val errors = scala.collection.mutable.LinkedHashMap[String, String]()
    val account =
      try User.valid(shaped)
      catch
        case e: ValidationException =>
          errors ++= e.errors
          shaped

    password.flatMap(validPassword(FieldConst.User.PASSWORD, _)).foreach(errors ++= _.errors)
    if account.accountType != AccountType.Admin then
      if shapedGrants.isEmpty then errors += FieldConst.User.GRANTS -> Const.Msg.Err.LIBRARY_REQUIRED
      else if shapedGrants.size > RepositoryService.MAX_GRANTS_PER_ACCOUNT then
        errors += FieldConst.User.GRANTS -> Const.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT)
    (account, shapedGrants, errors)

  /**
   * The account and grants as its type allows: an Admin holds every Permission by type, so keeps no grant, Create Libraries flag
   * or new-Library grant; a Guest holds no Permission, so keeps its Libraries with none, no Create Libraries flag, and a
   * new-Library grant of access alone
   */
  private def shapedByType(model: User, grants: Map[String, Permissions]): (User, Map[String, Permissions]) =
    // The new-Library grant's Permissions mean nothing with its switch off
    val account = if model.grantsNewLibraries then model else model.copy(newLibraryPermissions = Permissions.none)
    account.accountType match
      case AccountType.Admin =>
        (
          account.copy(canCreateLibraries = false, grantsNewLibraries = false, newLibraryPermissions = Permissions.none),
          Map.empty)
      case AccountType.Guest =>
        (
          account.copy(canCreateLibraries = false, newLibraryPermissions = Permissions.none),
          grants.view.mapValues(_ => Permissions.none).toMap)
      case AccountType.User => (account, grants)

  // The password rule: a `ValidationException` on `field` for a password outside its lengths
  private def validPassword(field: String, password: String): Option[ValidationException] =
    val (min, max) = (Api.Constraints.MIN_PASSWORD_LENGTH, Api.Constraints.MAX_PASSWORD_LENGTH)
    Option.when(password.length < min || password.length > max)(fieldError(field, Const.Msg.Err.PASSWORD_LENGTH.format(min, max)))

  private def fieldError(field: String, message: String): ValidationException =
    val ex = ValidationException(message)
    ex.errors += field -> message
    ex

  /**
   * What a creator who is no Admin may give: a User where it holds Create Users, a Guest where it holds Create Guests, no
   * Permission it does not hold there, and never an Admin, the Create Libraries flag or the new-Library grant. Its grants are
   * read in the write's transaction, so a Permission taken since its login is not passed on. `account` is as asked, before
   * [[shapedByType]], so a new-Library Permission asked with the switch off is refused, not dropped.
   */
  private def withinCeiling(creator: User, account: User, grants: Map[String, Permissions]): Unit =
    def refuse(why: String): Nothing =
      logger.warn(s"Account [${creator.persistedId}] refused an account above its ceiling: $why")
      throw ForbiddenException(Const.Msg.Err.FORBIDDEN_CEILING)

    if account.accountType == AccountType.Admin then refuse("an Admin")
    if account.canCreateLibraries || account.grantsNewLibraries || !account.newLibraryPermissions.isEmpty then
      refuse("the Create Libraries flag or the new-Library grant")

    val needed = if account.accountType == AccountType.Guest then Permission.CreateGuests else Permission.CreateUsers
    val held = grantDao.getByAccount(creator.persistedId).map(grant => grant.repositoryId -> grant.permissions).toMap
    grants.foreach {
      (repositoryId, permissions) =>
        val within = held.get(repositoryId).exists(own => own.has(needed) && (permissions.mask & ~own.mask) == 0)
        // The ID is the request's, escaped for the log line
        if !within then refuse(s"$permissions in [${Util.printable(repositoryId, 36)}]")
    }

  /** The acting Admin among the active Admins locked, or a `ForbiddenException` for an account that is no longer one */
  private def activeAdmin(actorId: String, admins: List[User]): User =
    admins.find(_.id.contains(actorId)).getOrElse(throw ForbiddenException(Const.Msg.Err.FORBIDDEN_ADMIN))

  /**
   * Never the actor's own account. This keeps an active Admin: the actor is one of the active Admins, read under their rows' lock
   * ([[activeAdmin]]), and never the one it removes, so one is always left.
   */
  private def refuseRemoval(actor: User, target: User): Unit =
    if target.id == actor.id then throw IllegalOperationException(Const.Msg.Err.OWN_ACCOUNT)

  /**
   * Runs an insert or update of an account, turning a unique violation of its email address's or its username's name key
   * (`account_01`, `account_02`) into a `ValidationException` on that field
   */
  private def whileKeysFree[A](write: => A): A =
    try write
    catch
      case e: SQLException =>
        newDuplicateExceptionOrRethrow(e) match
          case _: DuplicateException =>
            // QueryRunner rethrows the driver's exception with the statement in its message, the driver's as the next one
            val message = Option(e.getNextException).getOrElse(e).getMessage
            if message.contains("username_key") || message.contains("account_02") then
              throw fieldError(FieldConst.User.USERNAME, Const.Msg.Err.USERNAME_TAKEN)
            else throw fieldError(FieldConst.User.EMAIL, Const.Msg.Err.EMAIL_TAKEN)
          case other => throw other

  private def grantsJson(grants: Map[String, Permissions]): ujson.Obj =
    ujson.Obj.from(grants.toSeq.sortBy(_._1).map((repositoryId, permissions) => repositoryId -> permissionsJson(permissions)))

  private def permissionsJson(permissions: Permissions): ujson.Arr = ujson.Arr.from(permissions.toList.map(_.toString))

  /**
   * Records `repoId` as the user's last active Library; returns whether it wrote. Every page load of a Library calls it, so it
   * reads first and writes only when the Library differs, sparing SQLite's one write connection. The write is still a conditional
   * UPDATE, so of two racing loads the second writes nothing.
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

  /** The dev user's claims, when the dev credentials are set and the environment is dev */
  def getDevUser: Option[TokenClaims] = devCredentials.flatMap(devUser)

  /**
   * The claims of the account `credentials` log in, made the request's, or None. The password is checked until it first succeeds;
   * after that the claims are rebuilt from the database each request, so account changes show at once, and an account gone or
   * deactivated is logged in again.
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
