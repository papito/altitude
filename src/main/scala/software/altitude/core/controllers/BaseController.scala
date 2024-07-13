package software.altitude.core.controllers

import org.scalatra.scalate.ScalateUrlGeneratorSupport
import org.scalatra.{ContentEncodingSupport, InternalServerError, ScalatraServlet, UrlGeneratorSupport}
import org.slf4j.MDC
import software.altitude.core.AltitudeServletContext
import software.altitude.core.Const
import software.altitude.core.auth.AuthenticationSupport
import software.altitude.core.models.Repository
import software.altitude.core.models.User

import java.io.PrintWriter
import java.io.StringWriter
import java.lang.System.currentTimeMillis

abstract class BaseController
  extends ScalatraServlet
    with ContentEncodingSupport
    with UrlGeneratorSupport
    with ScalateUrlGeneratorSupport
    with AuthenticationSupport
    with AltitudeServletContext {

  final def user: User = request.getAttribute("user").asInstanceOf[User]

  final def repository: Repository = request.getAttribute("repository").asInstanceOf[Repository]

  before() {
    val requestId = software.altitude.core.Util.randomStr(size = 6)
    request.setAttribute("request_id", requestId)
    MDC.put("REQUEST_ID", s"[$requestId]")
    request.setAttribute("startTime", currentTimeMillis)
    logRequestStart()
    setUser()
    setRepository()
  }

  after() {
    logRequestEnd()
    MDC.clear()
  }

  protected def logRequestStart(): Unit = {
    if (isAssetRequest) return

    logger.info(s"Request START: ${request.getRequestURI} args: {${request.getParameterMap}}")
  }

  protected def logRequestEnd(): Unit = {
    if (isAssetRequest) return

    val startTime: Long = request.getAttribute("startTime").asInstanceOf[Long]
    logger.info(s"Request END: ${request.getRequestURI} in ${currentTimeMillis - startTime}ms")
  }

  private def isAssetRequest =  request.pathInfo.startsWith("/css") ||
    request.pathInfo.startsWith("/js") ||
    request.pathInfo.startsWith("/webfonts") ||
    request.pathInfo.startsWith("/images") ||
    request.pathInfo.startsWith(s"/${Const.DataStore.PREVIEW}")

  protected def setUser(): Unit = {
/*    val userId =
      Option(request.getAttribute("user_id").asInstanceOf[String]) orElse params.get("user_id")

    if (userId.isEmpty) {
      throw new IllegalStateException("Request contains no USER ID as either attribute or a parameter")
    }

    log.debug(s"Request user id [${userId.get}]")

    val user: User = app.service.user.getUserById(userId.get)
    MDC.put("USER", s"[$user]")
    request.setAttribute("user", user)
*/  }

  protected def setRepository(): Unit = {
  /*  val repositoryId =
      Option(request.getAttribute("repository_id").asInstanceOf[String]) orElse params.get("repository_id")

    if (repositoryId.isEmpty) {
      throw new IllegalStateException("Request contains no REPOSITORY ID as either attribute or a parameter")
    }

    log.debug(s"Request repo id [${repositoryId.get}]")

    val repository: Repository = app.service.repository.getRepositoryById(repositoryId.get)
    MDC.put("REPO", s"[$repository]")
    request.setAttribute("repository", repository)
  */}

  error {
    case ex: Exception =>
      ex.printStackTrace()
      val sw: StringWriter = new StringWriter()
      val pw: PrintWriter = new PrintWriter(sw)
      ex.printStackTrace(pw)
      logger.error(s"Exception ${sw.toString}")
      InternalServerError(sw.toString)
  }
}
