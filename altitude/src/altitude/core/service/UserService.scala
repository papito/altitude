package altitude.core.service

import altitude.core._
import altitude.core.FieldConst
import altitude.core.dao.UserDao
import altitude.core.dao.UserTokenDao
import altitude.core.models.User
import altitude.core.models.UserToken
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util
import java.time.LocalDateTime
import play.api.libs.json.JsObject
import play.api.libs.json.Json

class UserService(val app: Altitude) extends BaseService[User] {
  protected val dao: UserDao = app.DAO.user
  private val tokenDao: UserTokenDao = app.DAO.userToken

  override protected val txManager: TransactionManager = app.txManager

  /**
   * The User model is a model that does not have repository_id. Other models are scoped by it as no operations are cross-repo
   * (normally).
   */
  override def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.query(query)
    }
  }

  def switchContextToUser(user: User): Unit = {
    RequestContext.account.value = Some(user)
  }

  def loginAndGetUser(email: String, password: String): Option[(User, String)] = {
    txManager.withTransaction {
      val passwordHash = getPasswordHashByEmail(email)

      if (!Util.checkPassword(password, passwordHash)) {
        None
      } else {
        val user: User = getByEmail(email)

        // Create PASETO token
        val token = app.service.paseto.createToken(user)

        // Also save the token to the database for tracking/revocation
        val userToken = UserToken(
          userId = user.persistedId,
          token = token,
          expiresAt = LocalDateTime.now.plusDays(Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS))

        tokenDao.add(userToken.toJson)
        app.usersByToken += (token -> user)

        switchContextToUser(user)
        Some((user, token))
      }
    }
  }

  /**
   * Validates a PASETO token and returns the user if valid.
   * This also switches the request context to the authenticated user.
   */
  def getUserFromToken(token: String): Option[User] = {
    txManager.asReadOnly[Option[User]] {
      // First check the cache
      app.usersByToken.get(token) match {
        case Some(user) =>
          switchContextToUser(user)
          Some(user)
        case None =>
          // Validate using PASETO service
          app.service.paseto.validateTokenAndGetUser(token) match {
            case Some(user) =>
              // Cache the token -> user mapping
              app.usersByToken += (token -> user)
              switchContextToUser(user)
              Some(user)
            case None =>
              None
          }
      }
    }
  }

  /**
   * Logs out the user by invalidating the token.
   */
  def logout(token: String): Unit = {
    logger.info("Logging out user with token")
    deleteToken(token)
  }

  override def add(objIn: User): JsObject =
    throw new NotImplementedError("Use the alternate add() method with password")

  def add(objIn: User, password: String): JsObject = {
    txManager.withTransaction {
      // password and hash are not stored in the model and are not passed around outside of login flow
      val passwordHash = Util.hashPassword(password)
      dao.add(objIn.toJson ++ Json.obj(FieldConst.User.PASSWORD_HASH -> passwordHash))
    }
  }

  private def getPasswordHashByEmail(email: String): String = {
    // try cache first

    if (app.usersPasswordHashByEmail.contains(email)) {
      return app.usersPasswordHashByEmail(email)
    }

    val query = new Query(params = Map(FieldConst.User.EMAIL -> email))
    val sqlQuery = dao.sqlQueryBuilder.buildSelectSql(query)

    /* Since the user model does not explicitly store the hashed password,
       we need to do a low-level query to get the password hash */
    val userRec: Map[String, AnyRef] = dao.executeAndGetOne(sqlQuery.sqlAsString, sqlQuery.bindValues)

    val passwordHash = userRec(FieldConst.User.PASSWORD_HASH).asInstanceOf[String]

    app.usersPasswordHashByEmail += (email -> passwordHash)
    passwordHash
  }

  def getByToken(token: String): Option[User] = {
    txManager.asReadOnly[Option[User]] {
      app.usersByToken.get(token)
    }
  }

  def deleteToken(token: String): Unit = {
    logger.info("Deleting token: " + token)
    tokenDao.deleteByQuery(new Query(params = Map(FieldConst.UserToken.TOKEN -> token)))
    app.usersByToken -= token
  }

  private def getByEmail(email: String): JsObject = {
    // try cache first
    if (app.usersByEmail.contains(email)) {
      return app.usersByEmail(email).toJson
    }

    val query = new Query(params = Map(FieldConst.User.EMAIL -> email))

    /* Since the user model does not explicitly store the hashed password,
       we need to do a low-level query to get the password hash */
    val user: User = dao.getOneByQuery(query)

    app.usersByEmail += (email -> user)
    user.toJson
  }

  override def getById(id: String): JsObject = {
    // try cache first
    if (app.usersById.contains(id)) {
      return app.usersById(id).toJson
    }

    val user = super.getById(id)
    app.usersById += (id -> user)
    user
  }

  def setLastActiveRepoId(user: User, repoId: String): Unit = {
    txManager.withTransaction {
      dao.updateById(user.persistedId, Map(FieldConst.User.LAST_ACTIVE_REPO_ID -> repoId))
    }
  }

}
