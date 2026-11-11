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

  /*
   * Account management (the Users page). Each write runs in one transaction, records its audit event in it, and makes the
   * account's tokens stale once it commits (`AccountVersions.bump`; a password `revoke`s them), so its sessions see the change at
   * their next request. The acting account is read from the database in the write's transaction, never taken from the token.
   */

  /**
   * Adds an account for `creatorId`, which is recorded as its creator: the account's username, name and email address keep to
   * their rules (`User.valid`), its password to `Api.Constraints.MIN_`/`MAX_PASSWORD_LENGTH`, and a User or a Guest is given at
   * least one Library and at most `MAX_GRANTS_PER_ACCOUNT` (`grants`, Library ID to Permissions), each failing rule a
   * `ValidationException` naming its field, as is an email address or username another account holds. The account is held to its
   * type ([[shapedByType]]). A creator who is no Admin creates a User only where it holds Create Users and a Guest only where it
   * holds Create Guests, with no Permission it does not hold there, and never an Admin, the Create Libraries flag or the
   * new-Library grant (its switch or its Permissions): anything above that ceiling is a `ForbiddenException`, whatever was asked.
   * A creator deactivated since its login creates nothing (`ForbiddenException`).
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
   * An Admin's change to an account: its name, username, email address, type, grants, Create Libraries flag and new-Library
   * grant, held to the rules of [[create]] (the password aside, which [[setPassword]] changes) and to the account's type
   * ([[shapedByType]]): a change to Admin takes the grants away, a change to Guest keeps the Libraries and clears every
   * Permission. An Admin may not take the Admin type from its own account (a `ValidationException` on the type). The active
   * Admins' rows are locked first (`lockActiveAdmins`) and the actor must be one of them, so two demotions at once take turns and
   * the second, by an Admin the first demoted, is refused: one active Admin is always left. A change of the name, username or
   * email address rewrites the importer record of every asset the account imported, in every Library
   * (`AssetDao.rewriteImporterRecords`). The account as stored.
   */
  def update(actorId: String, model: User, grants: Map[String, Permissions], clientIp: Option[String] = None): User =
    val (account, accountGrants) = valid(model, grants, None)

    txManager.withTransaction {
      val admins = dao.lockActiveAdmins()
      val actor = activeAdmin(actorId, admins)
      val stored = dao.lockById(account.persistedId)
      val typeChanged = stored.accountType != account.accountType

      if typeChanged && stored.accountType == AccountType.Admin && stored.id == actor.id then
        throw fieldError(FieldConst.User.ACCOUNT_TYPE, Const.Msg.Err.OWN_ACCOUNT)

      whileKeysFree(dao.update(account))
      val heldGrants = grantDao.getByAccount(stored.persistedId).map(grant => grant.repositoryId -> grant.permissions).toMap
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

  /** An Admin deactivates an account: it can do nothing until it is reactivated. Refused as [[delete]] is. */
  def deactivate(actorId: String, accountId: String, clientIp: Option[String] = None): Unit =
    changeActive(actorId, accountId, active = false, clientIp)

  /** An Admin reactivates an account */
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
        app.service.audit.record(
          if active then AuditEventType.AccountReactivated else AuditEventType.AccountDeactivated,
          actor = Some(actor),
          target = Some(target),
          clientIp = clientIp)
        txManager.afterCommit(versions.bump(accountId))
    }

  /**
   * An Admin deletes an account, for good, in one transaction: every asset it owns, in every Library, becomes the Admin's
   * (`AssetDao.reassignOwner`), and the row is deleted, which takes its grants (`ON DELETE CASCADE`) and clears the creator of
   * the accounts it created (`ON DELETE SET NULL`). The importer records of its assets are left as they are. An Admin may not
   * delete its own account (`IllegalOperationException`); the active Admins' rows are locked first and the actor must be one of
   * them, so one active Admin is always left. How many assets moved.
   */
  def delete(actorId: String, accountId: String, clientIp: Option[String] = None): Int =
    txManager.withTransaction {
      val admins = dao.lockActiveAdmins()
      val actor = activeAdmin(actorId, admins)
      val target = dao.lockById(accountId)
      refuseRemoval(actor, target)

      val moved = app.DAO.asset.reassignOwner(accountId, actorId)
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

  /** How many assets the account owns, in every Library: what deleting it moves to the Admin (`AssetDao.countOwnedBy`) */
  def countOwnedAssets(accountId: String): Int =
    txManager.asReadOnly {
      app.DAO.asset.countOwnedBy(accountId)
    }

  /**
   * An Admin sets an account's password, to the password rule: every session of the account is refused from then on
   * (`AccountVersions.revoke`), the Admin's own included when it sets its own
   */
  def setPassword(actorId: String, accountId: String, password: String, clientIp: Option[String] = None): Unit =
    validPassword(FieldConst.User.PASSWORD, password).foreach(e => throw e)
    val passwordHash = Util.hashPassword(password)

    txManager.withTransaction {
      // The active Admins' rows locked first, in ID order, as every Admin's write locks them, so two Admins setting each other's
      // passwords take turns
      val actor = activeAdmin(actorId, dao.lockActiveAdmins())
      val target = dao.lockById(accountId)
      writePassword(actor, target, passwordHash, clientIp)
    }

  /**
   * An account changes its own password, giving its current one: a wrong one is a `ValidationException` on `currentField`, logged
   * at WARN and recorded as a failed change with the client IP, and a new one outside the password rule one on `newField`. The
   * current one is checked in a read-only transaction, as a login's is, and the write refuses it too when the password changed
   * meanwhile. Every session of the account is refused from then on, the one that made the change included, which mints itself a
   * new token.
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
   * The accounts `viewer` sees on the Users page, by name (natural order), each with its grants: every account and grant for an
   * Admin; for anyone else, the accounts it created that are no Admins (an Admin may have promoted one since), with their grants
   * in the Libraries it holds a grant in itself alone
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

  /**
   * The account and its grants held to its type and to the account rules: its name normalized, or a `ValidationException` with
   * the error of every failing field (the username, name and email address, `User.valid`; the password when one is given; the
   * grants: a User or a Guest is given at least one Library and at most `MAX_GRANTS_PER_ACCOUNT`)
   */
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
   * The errors of the account rules for an account and its grants, by field (as [[create]] refuses them), none when it keeps to
   * them: what a form shows beside an error of its own, such as a type left unchosen. It reads no database, so a taken address or
   * username is not among them.
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
   * The account and its grants as its type allows them: an Admin holds every Permission by type, so it keeps no grant, no Create
   * Libraries flag and no new-Library grant; a Guest holds no Permission, so it keeps its Libraries with none, no Create
   * Libraries flag, and a new-Library grant of access alone. With the new-Library switch off, the grant holds no Permission.
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
   * What a creator who is no Admin may give (its ceiling): a User where it holds Create Users, a Guest where it holds Create
   * Guests, with no Permission it does not hold there, and neither an Admin, the Create Libraries flag nor the new-Library grant.
   * Its grants are read in the write's transaction, so a Permission taken from it since its login is not passed on. A Library it
   * holds no grant in is refused as one above its ceiling, whether or not it exists. `account` is what was asked, before the
   * account is held to its type, so a new-Library Permission asked with the switch off is refused, not dropped.
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
        if !within then refuse(s"$permissions in [$repositoryId]")
    }

  /** The acting Admin among the active Admins locked, or a `ForbiddenException` for an account that is no longer one */
  private def activeAdmin(actorId: String, admins: List[User]): User =
    admins.find(_.id.contains(actorId)).getOrElse(throw ForbiddenException(Const.Msg.Err.FORBIDDEN_ADMIN))

  /**
   * Deactivating or deleting `target`: never the actor's own account. This is what keeps an active Admin: the actor is one of the
   * active Admins, read under their rows' lock ([[activeAdmin]]), and never the one it removes, so one is always left.
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
