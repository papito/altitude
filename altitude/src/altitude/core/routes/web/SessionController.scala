package altitude.core.routes.web

import cask.Request
import cask.model.Cookie
import java.time.Duration
import java.time.Instant
import org.slf4j.Logger

import altitude.core.App
import altitude.core.routes.BaseController
import altitude.core.util.Util

object SessionController:
  val AUTH_COOKIE_NAME = "auth_token"

  /**
   * The session cookie of `token`, kept by the browser until the token expires at `expiresAt`. Every cookie of a session is set
   * here, with the same attributes: the login's, setup's, a rebuilt token's (`decorators.requireLogin`) and the logout's, which
   * clears it.
   */
  def authCookie(token: String, expiresAt: Instant): Cookie =
    Cookie(
      name = AUTH_COOKIE_NAME,
      value = token,
      path = "/",
      maxAge = math.max(0L, Duration.between(Instant.now, expiresAt).getSeconds).toInt,
      httpOnly = true,
      secure = true,
      sameSite = "Strict"
    )

  /** The session cookie, empty and expired, so the browser drops it */
  def clearedAuthCookie: Cookie = authCookie("", Instant.EPOCH)

  /**
   * Validates that a redirect URL is safe to use (prevents open redirect vulnerabilities). Only allows relative URLs that start
   * with / and don't contain //
   */
  def isValidRedirectUrl(url: String): Boolean =
    url.nonEmpty &&
      url.startsWith("/") &&
      !url.startsWith("//") &&
      !url.contains("://") &&
      !url.contains("\\")

class SessionController(using logger: Logger) extends cask.Routes:
  /** Display the login page */
  @cask.get("/login")
  def loginPage(redirect: Option[String] = None): cask.Response[String] =
    val payload = "<!doctype html>" + html.login(redirect)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /** Process login form submission: the login is a username or an email address */
  @cask.postForm("/login")
  def doLogin(login: String, password: String, redirect: Option[String] = None)(using request: Request): cask.Response[String] =
    logger.trace(s"Login attempt for [${Util.printable(login, 64)}]")
    val clientIp = BaseController.clientIpOf(request)

    App.altitude.service.user.loginAndSetUser(login, password, clientIp) match {
      case Some((user, token, expiresAt)) =>
        logger.debug(s"User logged in successfully: ${user.email}")

        // Determine where to redirect after successful login
        // Validate redirect URL to prevent open redirect vulnerabilities
        // Without one, the Library the user lands in; `/` tells a user who can see none so
        val decodedRedirect = redirect.map(java.net.URLDecoder.decode(_, "UTF-8"))
        val redirectUrl = decodedRedirect
          .filter(SessionController.isValidRedirectUrl)
          .getOrElse(App.altitude.service.repository.landingRepositoryOf(user).fold("/")(repo => s"/r/${repo.persistedId}"))

        // Redirect to the original URL or home page with auth cookie set
        cask.Response(
          "",
          statusCode = 302,
          headers = Seq("Location" -> redirectUrl),
          cookies = Seq(SessionController.authCookie(token, expiresAt))
        )

      case None =>
        // The login as typed may carry a line break that forges a log line
        logger.warn(s"Failed login attempt for [${Util.printable(login, 64)}] from [${clientIp.getOrElse("unknown")}]")
        // Return to login page with error, preserving the redirect parameter
        val payload = "<!doctype html>" + html.login(redirect)
        cask.Response(
          payload,
          statusCode = 401,
          headers = Seq(("Content-Type", "text/html"))
        )
    }

  /** Logout - clears the auth cookie and invalidates the token */
  @cask.post("/logout")
  def doLogout()(using request: Request): cask.Response[String] =
    // Extract token from cookie to invalidate it
    val cookies = request.exchange.getRequestCookies
    val tokenOpt = Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)

    tokenOpt.foreach {
      token =>
        logger.trace("Logging out user")
        App.altitude.service.user.logout(token)
    }

    // Clear the cookie and redirect to log in
    cask.Response(
      "",
      statusCode = 302,
      headers = Seq("Location" -> "/login"),
      cookies = Seq(SessionController.clearedAuthCookie)
    )

  /** API endpoint for logout (returns JSON) */
  @cask.post("/api/logout")
  def apiLogout()(using request: Request): cask.Response[String] =
    // Extract token from Authorization header or cookie
    val authHeader = Option(request.exchange.getRequestHeaders.getFirst("Authorization"))
    val tokenFromHeader = authHeader.filter(_.startsWith("Bearer ")).map(_.substring(7))
    val cookies = request.exchange.getRequestCookies
    val tokenFromCookie = Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)
    val tokenOpt = tokenFromHeader.orElse(tokenFromCookie)

    tokenOpt.foreach {
      token =>
        logger.trace("API logout")
        App.altitude.service.user.logout(token)
    }

    val responseJson = ujson.Obj("success" -> true, "message" -> "Logged out successfully")
    cask.Response(
      responseJson.toString,
      statusCode = 200,
      headers = Seq(("Content-Type", "application/json")),
      cookies = Seq(SessionController.clearedAuthCookie)
    )

  initialize()
