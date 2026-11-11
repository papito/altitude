package altitude.core.routes
import cask.model.Cookie
import cask.model.Response
import cask.model.Response.Raw
import cask.router.Result
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.time.Instant
import java.util.zip.GZIPOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import altitude.core.App
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.models.Permissions
import altitude.core.models.TokenClaims
import altitude.core.routes.web.SessionController
import altitude.core.service.UserService.TokenCheck
import altitude.core.util.MurmurHash
import altitude.core.util.Util

object decorators:
  val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * cask's gzip decorator, except for a response that offers byte ranges and for an image. A stored original is a compressed
   * format already, and a range names its raw bytes, which a compressed body would not match. An image is deflated already, so a
   * gzip costs CPU on both ends, saves nothing, and discards its Content-Length. Whether a response is gzipped depends on the
   * request's Accept-Encoding, so a response that may be says so in `Vary`: a cache keeps a copy per coding rather than handing a
   * gzipped body to a client that cannot read it. A 304 has no body to code, but varies as its 200 does.
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
        case v: Raw if !v.headers.contains(RangeStreaming.ACCEPT_RANGES) && !isImage(v) =>
          val varied = v.copy(headers = v.headers :+ ("Vary" -> "Accept-Encoding"))
          if acceptsGzip && v.statusCode != 304 then gzip(varied) else varied
        case v: Raw => v
      }

    private def gzip(v: Raw): Raw =
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

    private def isImage(response: Raw): Boolean =
      response.headers.exists((name, value) => name.equalsIgnoreCase("Content-Type") && value.startsWith("image/"))

  private val AUTH_HEADER_NAME = "Authorization"
  private val BEARER_PREFIX = "Bearer "

  /** A request's login: its claims, and the token that replaces the request's stale one, which the answer sets as the cookie */
  final case class Login(claims: TokenClaims, renewedToken: Option[String])

  /**
   * A request with no login, and the cookies the answer that refuses it sets: the session cookie cleared when it held a token
   * that was refused, so the browser stops sending it
   */
  final case class NoLogin(cookies: Seq[Cookie])

  /**
   * The request's login, its account made the request's: the dev user's (`UserService.getDevUser`), or that of the token of the
   * Authorization header (Bearer) or, failing one, of the session cookie (`UserService.checkToken`). A stale cookie's token is
   * rebuilt, to be replaced; a stale Bearer token, which the server cannot replace, is refused, as an invalid, expired or revoked
   * token is, and the request has no login.
   */
  def authenticate(req: cask.Request): Either[NoLogin, Login] =
    // Locally, the dev user bypasses the login, so a code change that the hot reload restarts the server for (a new token key)
    // does not log the developer out
    App.altitude.service.user.getDevUser match
      case Some(claims) => Right(Login(claims, None))
      case None =>
        val bearer = Option(req.exchange.getRequestHeaders.getFirst(AUTH_HEADER_NAME))
          .filter(_.startsWith(BEARER_PREFIX))
          .map(_.substring(BEARER_PREFIX.length))
        val cookie = Option(req.exchange.getRequestCookie(SessionController.AUTH_COOKIE_NAME)).map(_.getValue)
        // A Bearer header is the client's to send, so only a cookie's token can be replaced
        bearer.map(_ -> false).orElse(cookie.map(_ -> true)) match
          case None =>
            logger.debug("No authentication token found")
            Left(NoLogin(Nil))
          case Some((token, fromCookie)) =>
            App.altitude.service.user.checkToken(token, renewable = fromCookie) match
              case TokenCheck.Current(claims) =>
                logger.trace(s"User authenticated: ${claims.account.persistedId}")
                Right(Login(claims, None))
              case TokenCheck.Renewed(claims, renewed) => Right(Login(claims, Some(renewed)))
              case TokenCheck.Refused =>
                logger.warn("Invalid, expired, revoked or stale token")
                Left(NoLogin(if fromCookie then Seq(SessionController.clearedAuthCookie) else Nil))

  /**
   * Whether a WebSocket handshake comes from a page of this server, or from a client that is no browser: true when it has no
   * Origin (every browser sends one on a WebSocket handshake, so only another kind of client omits it, and it holds the session
   * token itself), and when its Origin is an http or https URL whose host and port, as written, are the request's Host header's,
   * case aside. A browser omits a scheme's default port from both, and a reverse proxy that keeps the Host header keeps them
   * equal. Anything else (another site, another port, `null`, a malformed Origin) is false: a page of another site cannot open
   * the socket with the visitor's session.
   */
  def isSameOriginOrNone(req: cask.Request): Boolean =
    Option(req.exchange.getRequestHeaders.getFirst("Origin")) match
      case None => true
      case Some(origin) =>
        val host = Option(req.exchange.getRequestHeaders.getFirst("Host"))
        val authority =
          try
            val uri = java.net.URI(origin)
            Option
              .when(Set("http", "https").contains(Option(uri.getScheme).getOrElse("").toLowerCase))(uri.getRawAuthority)
              .flatMap(Option(_))
          catch case _: java.net.URISyntaxException => None
        authority.exists(a => host.exists(_.equalsIgnoreCase(a)))

  /** The session cookie that replaces the request's, when its token was rebuilt */
  def renewedCookies(login: Login): Seq[Cookie] =
    login.renewedToken.toSeq.map(token => SessionController.authCookie(token, login.claims.expiresAt))

  // An API request (one that accepts JSON, or an /api/ path) is answered in JSON
  private def isApiRequest(req: cask.Request): Boolean =
    Option(req.exchange.getRequestHeaders.getFirst("Accept")).getOrElse("").contains("application/json") ||
      req.exchange.getRequestPath.startsWith("/api/")

  /** Runs `endpoint` unless the path names a repository that does not exist (`repoContext` found none), answered as not found */
  private def existingRepositoryOnly(req: cask.Request)(endpoint: => Result[Raw]): Result[Raw] =
    if RequestContext.repository.value.isEmpty && RepoPath.matches(req.exchange.getRequestPath) then
      logger.debug(s"No repository found for [${req.exchange.getRequestURI}]")
      handleNotFound(req)
    else endpoint

  // A person reads the plain text, which names a Library as the UI does; the JSON is an API client's
  private def handleNotFound(req: cask.Request): Result[Raw] =
    if isApiRequest(req) then
      Result.Success(
        Response(
          """{"error": "Not Found", "message": "Repository not found"}""",
          statusCode = 404,
          headers = Seq("Content-Type" -> "application/json")
        ))
    else
      Result.Success(Response(Const.Msg.Err.LIBRARY_NOT_FOUND, statusCode = 404, headers = Seq("Content-Type" -> "text/plain")))

  /**
   * Decorator that requires a login (`authenticate`) for the endpoint. Without one, an API request is answered 401 and any other
   * redirected to the login page, either answer clearing a session cookie whose token was refused. A repository in the path that
   * the account cannot see is answered as not found, as any foreign entity is: an Admin sees every one, anyone else one its token
   * carries a grant in, so the gate reads no database. The token's Permissions in the path's Library (every one for an Admin;
   * none for anyone else with no Library in the path) are put in `RequestContext.permissions`. A token rebuilt from the database
   * is set as the cookie of the answer, whatever it is.
   */
  class requireLogin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      authenticate(req) match
        case Right(login) =>
          val answer =
            accessibleRepositoryOnly(req, login.claims)(delegate(req, Map("request" -> req, "user" -> login.claims.account)))
          val cookies = renewedCookies(login)
          if cookies.isEmpty then answer else answer.transform { case v: Raw => v.copy(cookies = v.cookies ++ cookies) }
        case Left(noLogin) => handleUnauthenticated(req, noLogin.cookies)

    /**
     * Runs `endpoint` unless the path names a repository that the token gives no access to or that does not exist: both are
     * answered as not found, so the two cannot be told apart
     */
    private def accessibleRepositoryOnly(req: cask.Request, claims: TokenClaims)(endpoint: => Result[Raw]): Result[Raw] =
      val account = claims.account
      RequestContext.repository.value match
        case Some(repo) =>
          claims.permissionsIn(repo.persistedId) match
            case Some(permissions) =>
              RequestContext.permissions.value = permissions
              endpoint
            case None =>
              logger.warn(s"User [${account.persistedId}] denied repository [${repo.persistedId}]: no access")
              handleNotFound(req)
        case None =>
          RequestContext.permissions.value =
            if account.accountType == AccountType.Admin then Permissions.all else Permissions.none
          existingRepositoryOnly(req)(endpoint)

    private def handleUnauthenticated(req: cask.Request, cookies: Seq[Cookie]): Result[Raw] =
      if isApiRequest(req) then
        Result.Success(
          Response(
            """{"error": "Unauthorized", "message": "Authentication required"}""",
            statusCode = 401,
            headers = Seq("Content-Type" -> "application/json"),
            cookies = cookies
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
            headers = Seq("Location" -> s"/login?redirect=$originalUrl"),
            cookies = cookies
          ))

  /**
   * Decorator for an endpoint that a signed URL may open without a login. A request with both `exp` and `sig` query parameters is
   * answered by the signature alone: one that [[altitude.core.service.ContentSigner.verify]] accepts for the request path is
   * served with no account, since the signature is the grant, unless the repository does not exist (a 404, as for
   * `requireLogin`), and any other is a plain-text 403 (a share-link holder has no account to log in to). Any other request,
   * including one with only one of the two parameters, is `requireLogin`'s, so the logged-in UI builds plain URLs to the same
   * route.
   */
  class requireLoginOrSignature extends cask.RawDecorator:
    private val login = requireLogin()

    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      def param(name: String): Option[String] = req.queryParams.get(name).flatMap(_.headOption)

      (param("exp"), param("sig")) match
        case (Some(exp), Some(sig)) => signedOnly(req, exp, sig)(delegate(req, Map("request" -> req)))
        case _ => login.wrapFunction(req, delegate)

    private def signedOnly(req: cask.Request, exp: String, sig: String)(endpoint: => Result[Raw]): Result[Raw] =
      val path = req.exchange.getRequestPath
      // The path as requested, still percent-encoded, is what is logged: the decoded one may carry a line break that forges a
      // log line, and `:repoId` binds any text
      val requested = req.exchange.getRequestURI
      val now = Instant.now

      if App.altitude.service.contentSigner.verify(path, exp, sig, now) then
        logger.trace(s"Signed URL accepted for [$requested]")
        existingRepositoryOnly(req)(endpoint)
      else
        // The answer does not say why; the log does, never with the signature. An expired link is the routine end of one, so DEBUG
        // whatever its signature. A signature that does not match, with an expiry still ahead or a malformed one, is a tampered or
        // forged link, or one signed under a key the server no longer holds, so WARN, as an invalid login token is.
        if exp.toLongOption.exists(_ <= now.getEpochSecond) then
          logger.debug(s"Signed URL for [$requested] refused: expired at [$exp]")
        else logger.warn(s"Signed URL for [$requested] refused: the signature does not match")
        Result.Success(
          Response(
            "Forbidden: the link is invalid or has expired",
            statusCode = 403,
            headers = Seq("Content-Type" -> "text/plain")))

  /**
   * Decorator for an Admin-only endpoint, applied after `requireLogin`, which has put the account in the context. A User or a
   * Guest is answered with 403, JSON for an API request and plain text otherwise; there is nowhere to redirect them to.
   */
  class requireAdmin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      val user = RequestContext.account.value.getOrElse(
        throw IllegalStateException(s"requireAdmin on ${req.exchange.getRequestPath} ran without requireLogin before it"))

      // The innermost decorator's map fills the endpoint's extra parameter list, so the request travels on from here too
      if user.accountType == AccountType.Admin then delegate(req, Map("request" -> req))
      else
        logger.warn(s"Account [${user.persistedId}] (${user.accountType}) refused ${req.exchange.getRequestPath}: Admin only")
        if isApiRequest(req) then
          Result.Success(
            Response(
              """{"error": "Forbidden", "message": "Admin only"}""",
              statusCode = 403,
              headers = Seq("Content-Type" -> "application/json")))
        else Result.Success(Response("Forbidden: Admin only", statusCode = 403, headers = Seq("Content-Type" -> "text/plain")))

  /**
   * For a full page of a repository, applied after `requireLogin`, whose access check has refused a foreign repository: once the
   * page answers 200, the request's repository is recorded as the account's last active one
   * (`UserService.rememberLastActiveRepository`), which login and `/` open next. Partials and API routes do not carry it, so a
   * nav reload or a search never moves where the user lands, and neither does a page that is not found. The page is rendered
   * already, so a failure to record is logged at WARN and the page is served: the next login lands where the last recorded load
   * says.
   */
  class rememberRepository extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      // The innermost decorator's map fills the endpoint's extra parameter list, so the request travels on from here too
      delegate(req, Map("request" -> req)).transform {
        case v: Raw if v.statusCode == 200 =>
          for
            user <- RequestContext.account.value
            repo <- RequestContext.repository.value
          do
            try App.altitude.service.user.rememberLastActiveRepository(user, repo.persistedId)
            catch
              case NonFatal(e) =>
                logger.warn(
                  s"Could not record [${repo.persistedId}] as the last active repository of user [${user.persistedId}]",
                  e)
          v
        case v: Raw => v
      }

  /**
   * For files under content-hashed names: the bytes under a name never change, so a found file may be cached by anyone for good.
   * A miss carries no Cache-Control, since the name may be served once a build produces that content again.
   */
  class cachedForever extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      delegate(req, Map()).transform {
        case v: Raw if v.statusCode == 200 =>
          v.copy(headers = v.headers :+ ("Cache-Control" -> "public, max-age=31536000, immutable"))
        case v: Raw => v
      }

  /**
   * For files whose names stay when their content changes (the vendored libraries, the fonts and the JS modules under /static/):
   * the browser keeps a file but asks before each use (`no-cache`), and an unchanged one answers 304 with no body. The ETag is a
   * hash of the bytes, taken on every request, so a file refreshed under a running dev server is never answered as unchanged. It
   * is weak, since `compress` may send the same bytes gzipped.
   */
  class revalidated extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      delegate(req, Map()).transform {
        case v: Raw if v.statusCode == 200 =>
          val out = ByteArrayOutputStream()
          v.data.write(out)
          val bytes = out.toByteArray
          val etag = s"W/\"${Integer.toHexString(MurmurHash.hash32(bytes))}-${bytes.length}\""
          val headers = v.headers ++ Seq("ETag" -> etag, "Cache-Control" -> "no-cache")
          // A comma-separated list of the ETags the browser holds
          val held = Option(req.exchange.getRequestHeaders.getFirst("If-None-Match")).toSeq.flatMap(_.split(",")).map(_.trim)

          if held.contains(etag) then Response("", 304, headers)
          else v.copy(data = bytes, headers = headers)
        case v: Raw => v
      }

  class requestResponseLogger extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =

      val path = req.exchange.getRequestPath
      if path.startsWith("/static/") || path.startsWith("/assets/") || path.startsWith("/content") then
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
        case cask.router.Result.Success(refused: cask.endpoints.WebsocketResult.Response[?]) =>
          // A WebSocket handshake answered with a response rather than an upgrade, which is no Raw
          logger.debug(s"WebSocket refused [${refused.value.statusCode}] - $pathInfo in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(refused)
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
          logger.trace(s"Repository context from [${req.exchange.getRequestURI}]")
        case _ => // println("No repository context found in path: " + path)
      }

      delegate(req, Map("request" -> req))
