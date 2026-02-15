package altitude.core.routes
import altitude.core.App
import altitude.core.routes.web.SessionController
import altitude.core.util.Util
import cask.model.Response
import cask.model.Response.Raw
import cask.router.Result
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import java.lang.System.currentTimeMillis

object decorators {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  private val AUTH_HEADER_NAME = "Authorization"
  private val BEARER_PREFIX = "Bearer "

  /**
   * Extracts the authentication token from the request.
   * Checks both the Authorization header (Bearer token) and cookies.
   */
  def extractToken(req: cask.Request): Option[String] = {
    // First check Authorization header
    val authHeader = Option(req.exchange.getRequestHeaders.getFirst(AUTH_HEADER_NAME))
    authHeader.filter(_.startsWith(BEARER_PREFIX)).map(_.substring(BEARER_PREFIX.length)) match {
      case Some(token) => Some(token)
      case None =>
        // Fall back to cookie
        val cookies = req.exchange.getRequestCookies
        Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)
    }
  }

  /**
   * Decorator that requires a valid PASETO token for the endpoint.
   * If the token is invalid or missing, returns a 401 Unauthorized response.
   * For web requests, redirects to the login page.
   */
  class requireLogin extends cask.RawDecorator {
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] = {
      extractToken(req) match {
        case Some(token) =>
          App.altitude.service.user.getUserFromToken(token) match {
            case Some(user) =>
              logger.info(s"User authenticated: ${user.email}")
                delegate(req, Map("request" -> req, "user" -> user))
            case None =>
              logger.warn("Invalid or expired token")
              handleUnauthenticated(req)
          }
        case None =>
          logger.debug("No authentication token found")
          handleUnauthenticated(req)
      }
    }

    private def handleUnauthenticated(req: cask.Request): Result[Raw] = {
      // Check if this is an API request (Accept: application/json or API path)
      val acceptHeader = Option(req.exchange.getRequestHeaders.getFirst("Accept")).getOrElse("")
      val isApiRequest = acceptHeader.contains("application/json") ||
        req.exchange.getRequestPath.startsWith("/api/")

      if (isApiRequest) {
        Result.Success(Response(
          """{"error": "Unauthorized", "message": "Authentication required"}""",
          statusCode = 401,
          headers = Seq("Content-Type" -> "application/json")
        ))
      } else {
        // Redirect to login page for web requests, preserving the original URL
        val requestPath = req.exchange.getRequestPath
        val queryString = Option(req.exchange.getQueryString).map(qs => s"?$qs").getOrElse("")
        val originalUrl = java.net.URLEncoder.encode(s"$requestPath$queryString", "UTF-8")
        Result.Success(Response(
          "",
          statusCode = 302,
          headers = Seq("Location" -> s"/login?redirect=$originalUrl")
        ))
      }
    }
  }

  class requestResponseLogger extends cask.RawDecorator {
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] = {

      if (req.exchange.getRequestPath.startsWith("/static/")) {
        // skip logging for static file requests
        return delegate(req, Map())
      }

      val startTime = currentTimeMillis
      val requestId = Util.randomStr(size = 6)

      // this is used by logback pattern layout
      MDC.put("REQUEST_ID", requestId)

      val pathInfo = s"${req.exchange.getRequestPath}, ${req.exchange.getRequestMethod}?${req.exchange.getQueryParameters}"

      logger.info(s"Request START - $pathInfo")

      println(req)
      delegate(req, Map()) match {
        case cask.router.Result.Success(response: cask.endpoints.WsHandler) =>
          // WebSocket connection - log connection initiation only
          logger.info(s"WebSocket connected - $pathInfo in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(response)
        case cask.router.Result.Success(response) =>
          // Regular HTTP response
          logger.info(s"Request END [${response.statusCode}] - $pathInfo in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(response)
        case error =>
          logger.error(error.toString)
          error
      }
    }
  }

  private val RepoPath = """.*/r/([^/?#]+).*""".r

  class repoContext extends cask.RawDecorator {
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] = {
      val path = req.exchange.getRequestPath

      path match {
        case RepoPath(id) => {
          App.altitude.service.repository.setContextFromRequest(Some(id))
          logger.info("Set repository context to: " + id)
        }
        case _ => // println("No repository context found in path: " + path)
      }

      delegate(req, Map("request" -> req))
    }
  }
}
