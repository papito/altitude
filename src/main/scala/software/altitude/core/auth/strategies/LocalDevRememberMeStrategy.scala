package software.altitude.core.auth.strategies

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Const
import software.altitude.core.Environment
import software.altitude.core.models.User
import software.altitude.core.util.Query

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class LocalDevRememberMeStrategy(protected val app: ScalatraBase)(implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override def name: String = "RememberMe"

  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    if (Environment.ENV != Environment.DEV)
      throw new RuntimeException("LocalDevRememberMeStrategy can only be used in development environment")

    val altitude = AltitudeServletContext.app
    logger.info("LOCAL AUTHENTICATION - hardcoded user")
    val res = altitude.service.user.query(new Query().add(Const.User.EMAIL -> "drey10@gmail.com")).records.headOption

    res match {
      case Some(user) => Some(user)
      case None => throw new RuntimeException("Development User not found")
    }
  }
}
