package software.altitude.core.auth.strategies

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.models.User

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class UserPasswordStrategy(protected val app: ScalatraBase)
                          (implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override def name: String = "UserPassword"

  private def login = app.params.getOrElse("login", "")
  private def password = app.params.getOrElse("password", "")

  /**
   * * Determine whether the strategy should be run for the current request.
   */
  override def isValid(implicit request: HttpServletRequest): Boolean = {
    logger.info("UserPasswordStrategy: determining isValid: " + (login != "" && password != "").toString)
    login != "" && password != ""
  }

  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    val altitude = AltitudeServletContext.app
    logger.info("UserPasswordStrategy: attempting authentication")
    altitude.service.user.loginAndGetUser(login, password)
  }

  /** What should happen if the user is currently not authenticated? */
  override def unauthenticated()(implicit request: HttpServletRequest, response: HttpServletResponse): Unit = {
    app.redirect("/session/new")
  }

}
