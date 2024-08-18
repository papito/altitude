package software.altitude.core.auth.strategies

import org.scalatra.CookieOptions
import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Const
import software.altitude.core.models.User
import software.altitude.core.util.Util

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class RememberMeStrategy(protected val app: ScalatraBase)(implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override def name: String = "RememberMe"

  // This obviously means that after a restart, all rememberMe cookies will be invalid.
  private val COOKIE_KEY = Util.randomStr(40)
  private val ONE_WEEK = Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS * 24 * 3600

  /**
   * * Grab the value of the rememberMe cookie token.
   */
  private def tokenVal = {
    app.cookies.get(COOKIE_KEY) match {
      case Some(token) => token
      case None => ""
    }
  }

  /**
   * * Determine whether the strategy should be run for the current request.
   */
  override def isValid(implicit request: HttpServletRequest): Boolean = {
    logger.info("RememberMeStrategy: determining isValid: " + (tokenVal != "").toString)
    tokenVal != ""
  }

  /**
   * The member me strategy authenticates by means of an existing valid token.
   */
  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    AltitudeServletContext.app.service.user.getByToken(tokenVal) match {
      case Some(user) =>
        logger.info("RememberMeStrategy: authenticate: user found")
        Some(user)

      case None =>
        logger.warn("RememberMeStrategy: authenticate: user not found")
        None
    }
   }

  /** What should happen if the user is currently not authenticated? */
  override def unauthenticated()(implicit request: HttpServletRequest, response: HttpServletResponse): Unit = {
    app.redirect("/sessions/new")
  }

  /**
   * * After successfully authenticating with either the RememberMeStrategy, we set a rememberMe cookie for later use.
   */
  override def afterAuthenticate(winningStrategy: String, user: User)(implicit
      request: HttpServletRequest,
      response: HttpServletResponse): Unit = {
      logger.info("rememberMe: afterAuth fired")
      val token = "foobar"
      app.cookies.set(COOKIE_KEY, token)(CookieOptions(maxAge = ONE_WEEK, path = "/"))
  }

  /** Run this code before logout, to clean up any leftover database state and delete the rememberMe token cookie. */
  override def beforeLogout(user: User)(implicit request: HttpServletRequest, response: HttpServletResponse): Unit = {
    logger.info("rememberMe: beforeLogout")

    AltitudeServletContext.app.service.user.deleteToken(tokenVal)

    if (user != null) {
      user.forgetMe()
    }
    app.cookies.delete(COOKIE_KEY)(CookieOptions(path = "/"))
  }
}
