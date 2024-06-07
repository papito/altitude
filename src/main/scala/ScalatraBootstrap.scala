import org.scalatra._
import org.slf4j.LoggerFactory
import software.altitude.core.Environment
import software.altitude.core.SingleApplication

import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle with SingleApplication {
  private final val log = LoggerFactory.getLogger(getClass)

  override def init(context: javax.servlet.ServletContext): Unit = {
    val environment =  Environment.ENV match {
      case Environment.DEV => "development"
      case Environment.PROD => "production"
    }
    context.setInitParameter("org.scalatra.environment", environment)

    // FIXME: hardcoded
    context.setInitParameter("org.scalatra.cors.allowedOrigins", "http://localhost:3000")
    SingleApplication.mountEndpoints(context)
    SingleApplication.app.runMigrations()
  }

  override def destroy(context: ServletContext): Unit = {
    log.info("Cleaning up after ourselves...")
    app.freeResources()
    super.destroy(context)
    log.info("All done.")
  }
}
