package software.altitude.core.auth.strategies

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.{Environment, RequestContext}
import software.altitude.core.models.User

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class LocalDevRememberMeStrategy(protected val app: ScalatraBase)(implicit request: HttpServletRequest, response: HttpServletResponse)
  extends ScentryStrategy[User] {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override def name: String = "RememberMe"

  def authenticate()(implicit request: HttpServletRequest, response: HttpServletResponse): Option[User] = {
    if (Environment.ENV != Environment.DEV)
      throw new RuntimeException("LocalDevRememberMeStrategy can only be used in development environment")

    // The base web controller will have already set the repository and user in the request context
    // by finding the first available repository and user in the database
    RequestContext.account.value match {
      case Some(user) => Some(user)
      case None => None
    }
  }
}
