package software.altitude.core.controllers

import org.scalatra.InternalServerError
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import software.altitude.core.Context
import software.altitude.core.SingleApplication
import software.altitude.core.models.Repository
import software.altitude.core.models.User

import java.io.PrintWriter
import java.io.StringWriter
import scala.compat.Platform

abstract class BaseController extends AltitudeStack with SingleApplication {
  private final val log = LoggerFactory.getLogger(getClass)

  final def user: User = request.getAttribute("user").asInstanceOf[User]

  final def repository: Repository = request.getAttribute("repository").asInstanceOf[Repository]

  implicit lazy val context: Context = new Context(repo = repository, user = user)

  before() {
    val requestId = software.altitude.core.Util.randomStr(size = 6)
    request.setAttribute("request_id", requestId)
    MDC.put("REQUEST_ID", s"[$requestId]")
    request.setAttribute("startTime", Platform.currentTime)
    logRequestStart()
    setUser()
    setRepository()
  }

  after() {
    logRequestEnd()
    MDC.clear()
  }

  protected def logRequestStart(): Unit = {
    log.info(s"Request START: ${request.getRequestURI} args: {${request.getParameterMap}}")
  }

  protected def logRequestEnd(): Unit = {
    val startTime: Long = request.getAttribute("startTime").asInstanceOf[Long]
    log.info(s"Request END: ${request.getRequestURI} in ${Platform.currentTime - startTime}ms")
  }

  protected def setUser(): Unit = {
    val userId =
      Option(request.getAttribute("user_id").asInstanceOf[String]) orElse params.get("user_id")

    if (userId.isEmpty) {
      throw new IllegalStateException("Request contains no USER ID as either attribute or a parameter")
    }

    log.debug(s"Request user id [${userId.get}]")

    val user: User = app.service.user.getUserById(userId.get)
    MDC.put("USER", s"[$user]")
    request.setAttribute("user", user)
  }

  protected def setRepository(): Unit = {
    val repositoryId =
      Option(request.getAttribute("repository_id").asInstanceOf[String]) orElse params.get("repository_id")

    if (repositoryId.isEmpty) {
      throw new IllegalStateException("Request contains no REPOSITORY ID as either attribute or a parameter")
    }

    log.debug(s"Request repo id [${repositoryId.get}]")

    val repository: Repository = app.service.repository.getRepositoryById(repositoryId.get)
    MDC.put("REPO", s"[$repository]")
    request.setAttribute("repository", repository)
  }

  error {
    case ex: Exception =>
      ex.printStackTrace()
      val sw: StringWriter = new StringWriter()
      val pw: PrintWriter = new PrintWriter(sw)
      ex.printStackTrace(pw)
      log.error(s"Exception ${sw.toString}")
      InternalServerError(sw.toString)
  }
}
