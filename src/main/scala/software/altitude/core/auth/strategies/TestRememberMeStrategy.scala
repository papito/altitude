package software.altitude.core.auth.strategies

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Const
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.core.models.User

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class TestRememberMeStrategy(protected val app: ScalatraBase)
                            (implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  override def name: String = "RememberMe"

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    if (Environment.ENV != Environment.TEST)
      throw new RuntimeException("TestRememberMeStrategy can only be used in test environment")

    // Where is RequestContext.account set?
    // See: https://github.com/papito/altitude/wiki/How-the-tests-work#auth-with-controller-tests
    RequestContext.account.value match {
      case Some(user) => Some(user)
      case None => None
    }
  }
}
