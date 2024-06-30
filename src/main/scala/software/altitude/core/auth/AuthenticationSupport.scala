package software.altitude.core.auth

import org.scalatra.ScalatraBase
import org.scalatra.auth.ScentryConfig
import org.scalatra.auth.ScentrySupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeServletContext
import software.altitude.core.RequestContext
import software.altitude.core.models.User

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

trait AuthenticationSupport extends ScalatraBase with ScentrySupport[User] {
  self: ScalatraBase =>

  protected def fromSession: PartialFunction[String, User] = {
    case id: String =>
      val altitude = AltitudeServletContext.app
      altitude.service.user.getById(id)
  }

  protected def toSession: PartialFunction[User, String] = {
    case usr: User => usr.persistedId
  }

  protected val scentryConfig: ScentryConfiguration = (new ScentryConfig {
    override val login = "/sessions/new"
  }).asInstanceOf[ScentryConfiguration]

  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def requireLogin(): Unit = {
    if (!isAuthenticated) {
      scentry.authenticate("RememberMeStrategy")
    }

    if (!isAuthenticated) {
      redirect(scentryConfig.login)
    }

    // we can now access the user via the thread-local RequestContext until the end of the request
    RequestContext.account.value = Some(user)
  }

  /**
   * If an unauthenticated user attempts to access a route which is protected by Scentry, run the unauthenticated() method on the
   * UserPasswordStrategy.
   */
  override protected def configureScentry(): Unit = {
    scentry.unauthenticated {
      scentry.strategies("UserPassword").unauthenticated()
    }
  }

  /**
   * Register auth strategies with Scentry. Any controller with this trait mixed in will attempt to progressively use all registered
   * strategies to log the user in, falling back if necessary.
   */
  override protected def registerAuthStrategies(): Unit = {
    AltitudeServletContext.app.scentryStrategies.foreach { case (strategyName, strategyClass) =>
      scentry.register(strategyName, app => {
        val constructor = strategyClass.getConstructor(classOf[ScalatraBase], classOf[HttpServletRequest], classOf[HttpServletResponse])
        constructor.newInstance(app, request, response)
      })
    }
  }
}
