package altitude.core.routes.web

import altitude.core.{App, Const}
import cask.model.Cookie
import cask.Request
import org.slf4j.Logger
import play.api.libs.json.Json

object SessionController {
  val AUTH_COOKIE_NAME = "auth_token"
  val COOKIE_MAX_AGE_SECONDS: Int = Const.Security.MEMBER_ME_COOKIE_EXPIRATION_DAYS * 24 * 60 * 60 // 7 days
}

class SessionController(using logger: Logger) extends cask.Routes:
  /**
   * Display the login page
   */
  @cask.get("/login")
  def loginPage(redirect: Option[String] = None): cask.Response[String] = {
    val payload = "<!doctype html>" + html.login(redirect)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }

  /**
   * Process login form submission
   */
  @cask.postForm("/login")
  def doLogin(login: String, password: String, redirect: Option[String] = None): cask.Response[String] = {
    logger.info(s"Login attempt for user: $login")

    App.altitude.service.user.loginAndGetUser(login, password) match {
      case Some((user, token)) =>
        logger.info(s"User logged in successfully: ${user.email}")

        // Determine where to redirect after successful login
        val redirectUrl = redirect.map(java.net.URLDecoder.decode(_, "UTF-8"))
          .getOrElse(s"/r/${user.lastActiveRepoId.get}")

        // Redirect to the original URL or home page with auth cookie set
        cask.Response(
          "",
          statusCode = 302,
          headers = Seq("Location" -> redirectUrl),
          cookies = Seq(Cookie(
            name = SessionController.AUTH_COOKIE_NAME,
            value = token,
            path = "/",
            maxAge = SessionController.COOKIE_MAX_AGE_SECONDS,
            httpOnly = true
          ))
        )

      case None =>
        logger.warn(s"Failed login attempt for user: $login")
        // Return to login page with error, preserving the redirect parameter
        val payload = "<!doctype html>" + html.login(redirect)
        cask.Response(
          payload,
          statusCode = 401,
          headers = Seq(("Content-Type", "text/html"))
        )
    }
  }

  /**
   * API endpoint for login (returns JSON with token)
   */
  @cask.postJson("/api/login")
  def apiLogin(login: String, password: String)(using request: Request): cask.Response[String] = {
    logger.info(s"API login attempt for user: $login")

    App.altitude.service.user.loginAndGetUser(login, password) match {
      case Some((user, token)) =>
        logger.info(s"User logged in successfully via API: ${user.email}")

        val responseJson = Json.obj(
          "success" -> true,
          "token" -> token,
          "user" -> Json.obj(
            "id" -> user.persistedId,
            "email" -> user.email,
            "name" -> user.name,
            "accountType" -> user.accountType.toString
          )
        )

        cask.Response(
          responseJson.toString(),
          statusCode = 200,
          headers = Seq(("Content-Type", "application/json"))
        )

      case None =>
        logger.warn(s"Failed API login attempt for user: $login")
        val responseJson = Json.obj(
          "success" -> false,
          "error" -> "Invalid credentials"
        )
        cask.Response(
          responseJson.toString(),
          statusCode = 401,
          headers = Seq(("Content-Type", "application/json"))
        )
    }
  }

  /**
   * Logout - clears the auth cookie and invalidates the token
   */
  @cask.post("/logout")
  def doLogout()(using request: Request): cask.Response[String] = {
    // Extract token from cookie to invalidate it
    val cookies = request.exchange.getRequestCookies
    val tokenOpt = Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)

    tokenOpt.foreach { token =>
      logger.info("Logging out user")
      App.altitude.service.user.logout(token)
    }

    // Clear the cookie and redirect to log in
    cask.Response(
      "",
      statusCode = 302,
      headers = Seq("Location" -> "/login"),
      cookies = Seq(Cookie(
        name = SessionController.AUTH_COOKIE_NAME,
        value = "",
        path = "/",
        maxAge = 0, // Expire immediately
        httpOnly = true
      ))
    )
  }

  /**
   * API endpoint for logout (returns JSON)
   */
  @cask.post("/api/logout")
  def apiLogout()(using request: Request): cask.Response[String] = {
    // Extract token from Authorization header or cookie
    val authHeader = Option(request.exchange.getRequestHeaders.getFirst("Authorization"))
    val tokenFromHeader = authHeader.filter(_.startsWith("Bearer ")).map(_.substring(7))
    val cookies = request.exchange.getRequestCookies
    val tokenFromCookie = Option(cookies.get(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)
    val tokenOpt = tokenFromHeader.orElse(tokenFromCookie)

    tokenOpt.foreach { token =>
      logger.info("API logout")
      App.altitude.service.user.logout(token)
    }

    val responseJson = Json.obj("success" -> true, "message" -> "Logged out successfully")
    cask.Response(
      responseJson.toString(),
      statusCode = 200,
      headers = Seq(("Content-Type", "application/json")),
      cookies = Seq(Cookie(
        name = SessionController.AUTH_COOKIE_NAME,
        value = "",
        path = "/",
        maxAge = 0,
        httpOnly = true
      ))
    )
  }

  initialize()



