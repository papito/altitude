import org.scalatra._
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Environment

import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle with AltitudeServletContext {

  override def init(context: javax.servlet.ServletContext): Unit = {

    context.setInitParameter("org.scalatra.environment", Environment.CURRENT)

    AltitudeServletContext.mountEndpoints(context)
    AltitudeServletContext.app.runMigrations()
    AltitudeServletContext.app.setIsInitializedState()
  }

  override def destroy(context: ServletContext): Unit = {
    super.destroy(context)
  }
}
