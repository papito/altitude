package altitude.core.service

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

import altitude.core._
import altitude.core.dao.UserDao
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

class UserService(val app: Altitude) extends BaseService[User]:
  protected val dao: UserDao = app.DAO.user

  override protected val txManager: TransactionManager = app.txManager

  /**
   * The User model is a model that does not have repository_id. Other models are scoped by it as no operations are cross-repo
   * (normally).
   */
  override def query(query: Query): QueryResult[User] =
    txManager.asReadOnly {
      dao.query(query)
    }

  def switchContextToUser(user: User): Unit =
    RequestContext.account.value = Some(user)

  /** Only reads, so on SQLite a login does not wait for the write connection behind import writes */
  def loginAndSetUser(email: String, password: String): Option[(User, String)] =
    txManager.asReadOnly {
      // Attempt to get password hash - returns None if user doesn't exist
      val passwordHashOpt = getPasswordHashByEmailSafe(email)

      // Always perform password check to prevent timing attacks
      val dummyHash = "$2a$10$b58qVLgVVVxh9C4.bF9JjuIB5nbMgw7MrQ69ysrJAXSJG.cdAjbSa"
      val hashToCheck = passwordHashOpt.getOrElse(dummyHash)

      val passwordValid = Util.checkPassword(password, hashToCheck)

      // Only return user if both password is valid AND user exists
      if passwordValid && passwordHashOpt.isDefined then
        val user: User = dao.getByEmail(email)

        // Create PASETO token with embedded user data
        val token = app.service.paseto.createToken(user)

        switchContextToUser(user)
        Some((user, token))
      else None
    }

  /**
   * Validates a PASETO token and returns the user if valid. This also switches the request context to the authenticated user.
   *
   * Note: This method does NOT query the database. The user data is extracted from the PASETO token claims. Token revocation is
   * not supported with this stateless approach - tokens remain valid until expiration.
   */
  def getUserFromToken(token: String): Option[User] =
    // Validate using PASETO service - no database query needed
    app.service.paseto.validateTokenAndGetUser(token) match
      case Some(user) =>
        switchContextToUser(user)
        Some(user)
      case None =>
        None

  /**
   * Logs out the user by clearing the client-side cookie. With stateless PASETO tokens, there's no server-side revocation. The
   * token will remain cryptographically valid until it expires.
   */
  def logout(token: String): Unit =
    logger.debug("Logging out user (stateless - clearing client cookie only)")
    // No server-side action needed - the SessionController clears the cookie

  override def add(objIn: User): User =
    throw NotImplementedError("Use the alternate add() method with password")

  def add(objIn: User, password: String): User =
    txManager.withTransaction {
      logger.debug(s"Adding user [${objIn.email}]")
      val passwordHash = Util.hashPassword(password)
      // An email address names one account, whatever its case
      try dao.addUser(objIn, passwordHash)
      catch case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
    }

  private def getPasswordHashByEmailSafe(email: String): Option[String] =
    try Some(dao.getPasswordHashByEmail(email))
    catch
      case _: altitude.core.NotFoundException =>
        // User doesn't exist - this is expected, return None
        None
      case e: Exception =>
        // Unexpected error - log it but still return None to prevent timing attacks
        logger.warn(s"Unexpected error while retrieving password hash for email: ${e.getMessage}")
        None

  def getByToken(token: String): Option[User] =
    getUserFromToken(token)

  def setLastActiveRepoId(user: User, repoId: String): Unit =
    txManager.withTransaction {
      logger.trace(s"Setting the last active repository of user [${user.persistedId}] to [$repoId]")
      dao.updateById(user.persistedId, Map(FieldConst.User.LAST_ACTIVE_REPO_ID -> repoId))
    }

  // The dev user's ID once its password has checked out: every request asks for the dev user, a page of thumbnails included,
  // and a password check is a deliberately slow bcrypt
  private val verifiedDevUserId = AtomicReference[Option[String]](None)

  /**
   * The user that `dev.user` and `dev.password` log in, made the request's account, or None when they are not set or do not log
   * in. The password is checked until it first succeeds; after that the user is read by ID, so a change to it (its last active
   * repository) is seen, and a user that is gone is logged in again.
   */
  def getDevUser: Option[User] =
    val devEmail: String = App.altitude.config.getString(Const.Conf.DEV_USER)
    val devPassword: String = App.altitude.config.getString(Const.Conf.DEV_PASSWORD)

    if devEmail.isEmpty || devPassword.isEmpty then return None

    val verified = verifiedDevUserId.get.flatMap(
      id =>
        try Some(getById(id))
        catch case _: NotFoundException => None)
    verified match
      case Some(user) =>
        switchContextToUser(user)
        Some(user)
      case None =>
        val user = loginAndSetUser(devEmail, devPassword).map(_._1)
        verifiedDevUserId.set(user.map(_.persistedId))
        user
