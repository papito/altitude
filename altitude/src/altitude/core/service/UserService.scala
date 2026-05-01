package altitude.core.service
import altitude.core.*
import altitude.core.dao.UserDao
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util

class UserService(val app: Altitude) extends BaseService[User] {
  protected val dao: UserDao = app.DAO.user

  override protected val txManager: TransactionManager = app.txManager

  /**
   * The User model is a model that does not have repository_id. Other models are scoped by it as no operations are cross-repo
   * (normally).
   */
  override def query(query: Query): QueryResult[User] = {
    txManager.asReadOnly {
      dao.query(query)
    }
  }

  def switchContextToUser(user: User): Unit = {
    RequestContext.account.value = Some(user)
  }

  def loginAndSetUser(email: String, password: String): Option[(User, String)] = {
    txManager.withTransaction {
      // Attempt to get password hash - returns None if user doesn't exist
      val passwordHashOpt = getPasswordHashByEmailSafe(email)

      // Always perform password check to prevent timing attacks
      // Use a valid bcrypt hash for non-existent users to ensure identical execution paths
      // This is a pre-generated valid bcrypt hash (hash of a dummy password)
      val dummyHash = "$2a$10$b58qVLgVVVxh9C4.bF9JjuIB5nbMgw7MrQ69ysrJAXSJG.cdAjbSa"
      val hashToCheck = passwordHashOpt.getOrElse(dummyHash)

      val passwordValid = Util.checkPassword(password, hashToCheck)

      // Only return user if both password is valid AND user exists
      if (passwordValid && passwordHashOpt.isDefined) {
        val user: User = getByEmail(email)

        // Create PASETO token with embedded user data
        val token = app.service.paseto.createToken(user)

        switchContextToUser(user)
        Some((user, token))
      } else {
        None
      }
    }
  }

  /**
   * Validates a PASETO token and returns the user if valid. This also switches the request context to the authenticated user.
   *
   * Note: This method does NOT query the database. The user data is extracted from the PASETO token claims. Token revocation is
   * not supported with this stateless approach - tokens remain valid until expiration.
   */
  def getUserFromToken(token: String): Option[User] = {
    // Validate using PASETO service - no database query needed
    app.service.paseto.validateTokenAndGetUser(token) match {
      case Some(user) =>
        switchContextToUser(user)
        Some(user)
      case None =>
        None
    }
  }

  /**
   * Logs out the user by clearing the client-side cookie. With stateless PASETO tokens, there's no server-side revocation. The
   * token will remain cryptographically valid until it expires.
   */
  def logout(token: String): Unit = {
    logger.info("Logging out user (stateless - clearing client cookie only)")
    // No server-side action needed - the SessionController clears the cookie
  }

  override def add(objIn: User): User =
    throw new NotImplementedError("Use the alternate add() method with password")

  def add(objIn: User, password: String): User = {
    txManager.withTransaction {
      val passwordHash = Util.hashPassword(password)
      dao.addUser(objIn, passwordHash)
    }
  }

  private def getPasswordHashByEmailSafe(email: String): Option[String] = {
    try {
      Some(getPasswordHashByEmail(email))
    } catch {
      case _: altitude.core.NotFoundException =>
        // User doesn't exist - this is expected, return None
        None
      case e: Exception =>
        // Unexpected error - log it but still return None to prevent timing attacks
        logger.warn(s"Unexpected error while retrieving password hash for email: ${e.getMessage}")
        None
    }
  }

  private def getPasswordHashByEmail(email: String): String = {
    val query = new Query(params = Map(FieldConst.User.EMAIL -> email))
    val sqlQuery = dao.sqlQueryBuilder.buildSelectSql(query)

    /* Since the user model does not explicitly store the hashed password,
       we need to do a low-level query to get the password hash */
    val userRec: Map[String, AnyRef] = dao.executeAndGetOne(sqlQuery.sqlAsString, sqlQuery.bindValues)

    userRec(FieldConst.User.PASSWORD_HASH).asInstanceOf[String]
  }

  def getByToken(token: String): Option[User] = {
    getUserFromToken(token)
  }

  private def getByEmail(email: String): User = {
    val query = new Query(params = Map(FieldConst.User.EMAIL -> email))
    dao.getOneByQuery(query)
  }

  def setLastActiveRepoId(user: User, repoId: String): Unit = {
    txManager.withTransaction {
      dao.updateById(user.persistedId, Map(FieldConst.User.LAST_ACTIVE_REPO_ID -> repoId))
    }
  }

  def getDevUser: Option[User] = {
    val devEmail: String = App.altitude.config.getString(Const.Conf.DEV_USER)
    val devPassword: String = App.altitude.config.getString(Const.Conf.DEV_PASSWORD)

    if (devEmail.nonEmpty && devPassword.nonEmpty) {
      val devUserRes = App.altitude.service.user.loginAndSetUser(devEmail, devPassword)
      devUserRes.map(_._1)
    } else None
  }
}
