package altitude.core.routes
import cask.model.Response
import cask.model.Response.Raw
import cask.router.Result
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.util.zip.GZIPOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import scala.jdk.CollectionConverters._

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.models.User
import altitude.core.routes.web.SessionController
import altitude.core.util.Util

object decorators:
  val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * cask's gzip decorator, except for a response that offers byte ranges: a stored original is a compressed format already, and a
   * range names its raw bytes, which a compressed body would not match.
   */
  class compress extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      // A comma-separated list of codings, each with an optional `;q=` weight
      val acceptsGzip = Option(req.exchange.getRequestHeaders.get("Accept-Encoding")).toSeq
        .flatMap(_.asScala)
        .flatMap(_.split(","))
        .map(_.takeWhile(_ != ';').trim)
        .exists(_.equalsIgnoreCase("gzip"))

      delegate(req, Map()).transform {
        case v: Raw if acceptsGzip && !v.headers.contains(RangeStreaming.ACCEPT_RANGES) =>
          val gzipped = new Response.Data {
            override def write(out: OutputStream): Unit =
              val wrap = new GZIPOutputStream(out)
              v.data.write(wrap)
              wrap.flush()
              wrap.close()
            // The compressed length is not known ahead of time
            override def headers: Seq[(String, String)] = v.data.headers.filter(_._1 != "Content-Length")
          }
          Response(gzipped, v.statusCode, v.headers :+ ("Content-Encoding" -> "gzip"), v.cookies)
        case v: Raw => v
      }

  private val AUTH_HEADER_NAME = "Authorization"
  private val BEARER_PREFIX = "Bearer "

  /** Extracts the authentication token from the request. Checks both the Authorization header (Bearer token) and cookies. */
  def extractToken(req: cask.Request): Option[String] =
    // First check Authorization header
    val authHeader = Option(req.exchange.getRequestHeaders.getFirst(AUTH_HEADER_NAME))
    authHeader.filter(_.startsWith(BEARER_PREFIX)).map(_.substring(BEARER_PREFIX.length)) match {
      case Some(token) => Some(token)
      case None =>
        // Fall back to cookie
        val cookies = req.exchange.getRequestCookies
        Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)
    }

  /**
   * Decorator that requires a valid PASETO token for the endpoint. If the token is invalid or missing, returns a 401 Unauthorized
   * response. For web requests, redirects to the login page. A repository in the path that the user does not own is answered as
   * not found, as any foreign entity is.
   */
  class requireLogin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      // locally, we want to allow bypassing authentication with a dev user for easier testing
      // as hot reload is enabled, changes to the code will log out the dev user, so this is a way to test changes
      // without needing to log in repeatedly when working with the frontend
      val devUser = App.altitude.service.user.getDevUser

      if devUser.isDefined then
        return ownedRepositoryOnly(req, devUser.get)(delegate(req, Map("request" -> req, "user" -> devUser)))

      extractToken(req) match {
        case Some(token) =>
          App.altitude.service.user.getUserFromToken(token) match {
            case Some(user) =>
              logger.trace(s"User authenticated: ${user.email}")
              ownedRepositoryOnly(req, user)(delegate(req, Map("request" -> req, "user" -> user)))
            case None =>
              logger.warn("Invalid or expired token")
              handleUnauthenticated(req)
          }
        case None =>
          logger.debug("No authentication token found")
          handleUnauthenticated(req)
      }

    /**
     * Runs `endpoint` unless the path names a repository that `user` does not own or that does not exist: both are answered as
     * not found, so the two cannot be told apart
     */
    private def ownedRepositoryOnly(req: cask.Request, user: User)(endpoint: => Result[Raw]): Result[Raw] =
      RequestContext.repository.value match
        case Some(repo) if repo.ownerAccountId != user.persistedId =>
          logger.warn(s"User [${user.persistedId}] denied repository [${repo.persistedId}] they do not own")
          handleNotFound(req)
        case None if RepoPath.matches(req.exchange.getRequestPath) =>
          logger.debug(s"No repository found for [${req.exchange.getRequestPath}]")
          handleNotFound(req)
        case _ => endpoint

    private def handleNotFound(req: cask.Request): Result[Raw] =
      if isApiRequest(req) then
        Result.Success(
          Response(
            """{"error": "Not Found", "message": "Repository not found"}""",
            statusCode = 404,
            headers = Seq("Content-Type" -> "application/json")
          ))
      else Result.Success(Response("Repository not found", statusCode = 404, headers = Seq("Content-Type" -> "text/plain")))

    // An API request (one that accepts JSON, or an /api/ path) is answered in JSON
    private def isApiRequest(req: cask.Request): Boolean =
      Option(req.exchange.getRequestHeaders.getFirst("Accept")).getOrElse("").contains("application/json") ||
        req.exchange.getRequestPath.startsWith("/api/")

    private def handleUnauthenticated(req: cask.Request): Result[Raw] =
      if isApiRequest(req) then
        Result.Success(
          Response(
            """{"error": "Unauthorized", "message": "Authentication required"}""",
            statusCode = 401,
            headers = Seq("Content-Type" -> "application/json")
          ))
      else
        // Redirect to login page for web requests, preserving the original URL
        val requestPath = req.exchange.getRequestPath
        // Undertow reports a missing query string as empty rather than null
        val queryString = Option(req.exchange.getQueryString).filter(_.nonEmpty).map(qs => s"?$qs").getOrElse("")
        val originalUrl = java.net.URLEncoder.encode(s"$requestPath$queryString", "UTF-8")
        Result.Success(
          Response(
            "",
            statusCode = 302,
            headers = Seq("Location" -> s"/login?redirect=$originalUrl")
          ))

  class requestResponseLogger extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =

      if req.exchange.getRequestPath.startsWith("/static/") || req.exchange.getRequestPath.startsWith("/content") then
        // skip logging for static file requests
        return delegate(req, Map())

      val startTime = currentTimeMillis
      val requestId = Util.randomStr(size = 6)

      // this is used by logback pattern layout
      MDC.put("REQUEST_ID", requestId)

      val pathInfo = s"${req.exchange.getRequestPath}, ${req.exchange.getRequestMethod}?${req.exchange.getQueryParameters}"

      logger.debug(s"Request START - $pathInfo")

      delegate(req, Map()) match {
        case cask.router.Result.Success(response: cask.endpoints.WsHandler) =>
          // WebSocket connection - log connection initiation only
          logger.trace(s"WebSocket connected - $pathInfo in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(response)
        case cask.router.Result.Success(response) =>
          // Regular HTTP response
          logger.debug(s"Request END [${response.statusCode}] - $pathInfo in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(response)
        case error =>
          logger.error(error.toString)
          error
      }

  private val RepoPath = """.*/r/([^/?#]+).*""".r

  class repoContext extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      // Request threads are pooled, so a request starts with no account or repository rather than the last request's
      RequestContext.clear()
      val path = req.exchange.getRequestPath

      path match {
        case RepoPath(id) =>
          App.altitude.service.repository.setContextFromRequest(Some(id))
          logger.trace("Set repository context to: " + id)
        case _ => // println("No repository context found in path: " + path)
      }

      delegate(req, Map("request" -> req))
