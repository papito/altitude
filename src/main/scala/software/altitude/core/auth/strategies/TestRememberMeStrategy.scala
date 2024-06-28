package software.altitude.core.auth.strategies

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Environment
import software.altitude.core.RequestContext
import software.altitude.core.models.User

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class TestRememberMeStrategy(protected val app: ScalatraBase)
                            (implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  override def name: String = "RememberMe"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    if (Environment.ENV != Environment.TEST)
      throw new RuntimeException("TestRememberMeStrategy can only be used in test environment")

    // persists throughout the lifespan of the test server
    AltitudeServletContext.app.loggedInTestUser match {
      case Some(user) => Some(user)
      case None => throw new RuntimeException("There is no user set in the AltitudeServletContext.app.loggedInTestUser")
    }

    // persists only through the lifespan of the request
    RequestContext.account.value = AltitudeServletContext.app.loggedInTestUser

    log.warn(RequestContext.account.value.toString)
    RequestContext.account.value
  }
}
