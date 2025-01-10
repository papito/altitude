import org.eclipse.jetty.server._
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

import software.altitude.core.AltitudeServletContext

object ScalatraLauncher extends App with AltitudeServletContext {
  private val port = 9010

  private val server = new Server(port)

  server.setStopAtShutdown(true)

  val context: WebAppContext = new WebAppContext()
  private val webXml = getClass.getResource("/WEB-INF/web.xml")

  private val webappDirLocation = if (webXml != null) {
    // running the assembly jar
    webXml.toString.replaceFirst("/WEB-INF/web.xml$", "/")
  } else {
    // running on the source tree
    "src/main/webapp/"
  }

  context.setResourceBase(webappDirLocation)
  context.setDescriptor(webappDirLocation + "WEB-INF/web.xml")

  context.addEventListener(new ScalatraListener)
  context.addServlet(classOf[DefaultServlet], "/")

  server.setHandler(context)

  server.start()
  server.join()
}
