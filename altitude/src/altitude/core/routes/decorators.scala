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
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.TokenClaims
import altitude.core.models.User
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
              case TokenCheck.Refused(clearsCookie) =>
                logger.warn("Invalid, expired, revoked or stale token")
                Left(NoLogin(if fromCookie && clearsCookie then Seq(SessionController.clearedAuthCookie) else Nil))

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
   * Decorator that requires a login (`authenticate`) for the endpoint. Without one, an htmx request is answered 401 with
   * `HX-Redirect` to the login, an API request 401, and any other redirected to the login page, each answer clearing a session
   * cookie whose token will never be good again (`TokenCheck.Refused`). A repository in the path that the account cannot see is
   * answered as not found, as any foreign entity is: an Admin sees every one, anyone else one its token carries a grant in, so
   * the gate reads no database. The token's Permissions in the path's Library (every one for an Admin; none for anyone else with
   * no Library in the path) are put in `RequestContext.permissions`, its grants in every Library in `RequestContext.grants`, and
   * its expiry in `RequestContext.sessionExpiresAt`. A token rebuilt from the database is set as the cookie of the answer, unless
   * the answer sets the session cookie itself: an endpoint that mints a token (a password change) mints it after the rebuild,
   * which its change made stale.
   */
  class requireLogin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      authenticate(req) match
        case Right(login) =>
          val answer =
            accessibleRepositoryOnly(req, login.claims)(delegate(req, Map("request" -> req, "user" -> login.claims.account)))
          val cookies = renewedCookies(login)
          // An answer that sets the session cookie itself (a password changed, whose new token the rebuilt one predates) keeps it
          def setsSession(v: Raw) = v.cookies.exists(_.name == SessionController.AUTH_COOKIE_NAME)
          if cookies.isEmpty then answer
          else answer.transform { case v: Raw => if setsSession(v) then v else v.copy(cookies = v.cookies ++ cookies) }
        case Left(noLogin) => handleUnauthenticated(req, noLogin.cookies)

    /**
     * Runs `endpoint` unless the path names a repository that the token gives no access to or that does not exist: both are
     * answered as not found, so the two cannot be told apart
     */
    private def accessibleRepositoryOnly(req: cask.Request, claims: TokenClaims)(endpoint: => Result[Raw]): Result[Raw] =
      val account = claims.account
      RequestContext.grants.value = claims.grants
      RequestContext.sessionExpiresAt.value = Some(claims.expiresAt)
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

    /**
     * The answer to a request with no login. An htmx request (`HX-Request`) is a 401 carrying `HX-Redirect` to the login, with
     * the page it was sent from (`HX-Current-URL`, its path and query alone) to come back to: a redirect would be followed by the
     * browser's fetch and the login page swapped in as the partial. An API request is a JSON 401, and any other a redirect to the
     * login with the request's own URL. Each carries `cookies`, a refused session's cleared cookie.
     */
    private def handleUnauthenticated(req: cask.Request, cookies: Seq[Cookie]): Result[Raw] =
      if Option(req.exchange.getRequestHeaders.getFirst("HX-Request")).contains("true") then
        val toLogin = pageOf(req).fold("/login")(page => s"/login?redirect=${java.net.URLEncoder.encode(page, "UTF-8")}")
        val (body, contentType) =
          if isApiRequest(req) then ("""{"error": "Unauthorized", "message": "Authentication required"}""", "application/json")
          else ("Unauthorized", "text/plain")
        Result.Success(Response(body, 401, Seq("Content-Type" -> contentType, "HX-Redirect" -> toLogin), cookies = cookies))
      else if isApiRequest(req) then
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
   * The page an htmx request was sent from, as its `HX-Current-URL` names it: its path and query, which the login comes back to;
   * None when the header is missing, is no URL, or has no absolute path
   */
  private def pageOf(req: cask.Request): Option[String] =
    Option(req.exchange.getRequestHeaders.getFirst("HX-Current-URL")).flatMap {
      url =>
        try
          val uri = java.net.URI(url)
          Option(uri.getRawPath).filter(_.startsWith("/")).map(path => path + Option(uri.getRawQuery).fold("")("?" + _))
        catch case _: java.net.URISyntaxException => None
    }

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

  /** The account `requireLogin` put in the context, which a decorator applied after it reads; it throws when there is none */
  private def loggedIn(req: cask.Request, decorator: String): User =
    RequestContext.account.value.getOrElse(
      throw IllegalStateException(s"$decorator on ${req.exchange.getRequestPath} ran without requireLogin before it"))

  /**
   * Runs the endpoint when the account `requireLogin` put in the context is `allowed`, else answers 403, saying why (`why`) in
   * JSON for an API request and in plain text otherwise: there is nowhere to redirect the account to. The access decorators below
   * are all this, applied after `requireLogin`.
   */
  private def allowedOnly(req: cask.Request, delegate: cask.RawDecorator#Delegate, decorator: String, why: => String)(
      allowed: User => Boolean): Result[Raw] =
    val user = loggedIn(req, decorator)
    // The innermost decorator's map fills the endpoint's extra parameter list, so the request travels on from here too
    if allowed(user) then delegate(req, Map("request" -> req))
    else
      logger.warn(s"Account [${user.persistedId}] (${user.accountType}) refused ${req.exchange.getRequestPath}: $why")
      val refusal = forbidden(why, json = isApiRequest(req))
      Result.Success(Response(refusal.data, refusal.statusCode, refusal.headers))

  /**
   * The one 403 of a refusal for lack of a Permission, from a decorator, a route or a service (`ForbiddenException` through
   * `BaseController.answering` and `answeringJson`): `{"error": why}` in JSON, the shape of every JSON route's refusal
   * (`BaseController.jsonError`), and "Forbidden: why" in plain text otherwise
   */
  def forbidden(why: String, json: Boolean): Response[String] =
    if json then Response(ujson.write(ujson.Obj("error" -> why)), 403, Seq("Content-Type" -> "application/json"))
    else Response(s"Forbidden: $why", 403, Seq("Content-Type" -> "text/plain"))

  /**
   * Decorator for an Admin-only endpoint, applied after `requireLogin`, which has put the account in the context. A User or a
   * Guest is answered with 403, JSON for an API request and plain text otherwise; there is nowhere to redirect them to.
   */
  class requireAdmin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireAdmin", Const.Msg.Err.FORBIDDEN_ADMIN)(_.accountType == AccountType.Admin)

  /**
   * Decorator for an endpoint that needs a Permission in the path's Library, any one of `permissions`, applied after
   * `requireLogin`, which has put the account's Permissions there in the context (every one for an Admin; none for a Guest, who
   * is refused every such route). An account holding none of them is answered as `requireAdmin` answers a User. A rule that
   * depends on the asset (Curate own against its owner, recycling a sorted or a triaged one) is the service's (`AssetRules`).
   * `RouteTableTests` reads `anyOf` off the route.
   */
  class requirePermission(permissions: Permission*) extends cask.RawDecorator:
    require(permissions.nonEmpty, "requirePermission names at least one Permission")
    val anyOf: Set[Permission] = permissions.toSet

    // "Curate own, Curate all or Recycle": the labels in the order of the Permissions
    private val named =
      val labels = Permission.values.toList.filter(anyOf.contains).map(_.label)
      if labels.size == 1 then labels.head else s"${labels.init.mkString(", ")} or ${labels.last}"

    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requirePermission", Const.Msg.Err.FORBIDDEN_PERMISSION.format(named)) {
        _ => anyOf.exists(RequestContext.permissions.value.has)
      }

  /**
   * Decorator for an endpoint of a view a Guest does not see and that needs no Permission (the Triage partials), applied after
   * `requireLogin`: a Guest sees Browse alone, and is answered as `requireAdmin` answers a User. A view or a route that does need
   * one (the Import page, Curation, the Trash's Purge) is `requirePermission`'s, which refuses a Guest too.
   */
  class requireMember extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireMember", Const.Msg.Err.FORBIDDEN_GUEST)(_.accountType != AccountType.Guest)

  /**
   * Decorator for the Users page and adding an account, applied after `requireLogin`: an Admin, or an account holding Create
   * Users or Create Guests in any Library, read from its token's grants (`RequestContext.createsAccounts`), not only in the
   * path's; anyone else is answered as `requireAdmin` answers a User. What the account may give is the creator's ceiling, which
   * `UserService.create` holds it to.
   */
  class requireAccountCreator extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireAccountCreator", Const.Msg.Err.FORBIDDEN_ACCOUNT_CREATOR)(
        _ => RequestContext.createsAccounts)

  /**
   * Decorator for creating a Library, applied after `requireLogin`: an Admin, or an account whose token carries the Create
   * Libraries flag (`User.canCreateLibraries`); anyone else is answered as `requireAdmin` answers a User
   */
  class requireLibraryCreator extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireLibraryCreator", Const.Msg.Err.FORBIDDEN_LIBRARY_CREATOR)(mayCreateLibraries)

  /**
   * Whether the context's account may see a view of its Library (`Const.Search.View`), for the routes that take the view as a
   * parameter, which no decorator reads: a Guest sees Browse alone, so neither Triage nor the Trash, nor the triaged assets
   * Browse adds with `triaged=true`; any other account sees every view
   */
  def maySeeView(view: String, triaged: Option[Boolean] = None): Boolean =
    RequestContext.isMember || (view == Const.Search.View.DEFAULT && !triaged.contains(true))

  /**
   * A Guest's request for a view it does not see ([[maySeeView]]), as a route taking the view as a parameter answers it: a 403
   * ([[forbidden]]), in JSON when `json`; None for a view the account sees
   */
  def refusedView(view: String, triaged: Option[Boolean], json: Boolean = false): Option[Response[String]] =
    Option.unless(maySeeView(view, triaged)) {
      logger.warn(s"Account [${RequestContext.getAccount.persistedId}] refused the view [$view] (triaged: $triaged): a Guest")
      forbidden(Const.Msg.Err.FORBIDDEN_GUEST, json)
    }

  /**
   * Whether the context's account may open the asset by its ID: a Guest sees Browse alone, so neither a triaged asset nor one in
   * the trash; any other account, and a request with no account (a signed URL, whose signature is the grant), may
   */
  def maySeeAsset(asset: Asset): Boolean = !RequestContext.isGuest || (!asset.isTriaged && !asset.isRecycled)

  /** The asset, or a `NotFoundException`, as for an unknown ID, when the context's account may not open it ([[maySeeAsset]]) */
  def visibleOnly(asset: Asset): Asset =
    if maySeeAsset(asset) then asset
    else
      logger.debug(s"Asset [${asset.persistedId}] not shown to Guest [${RequestContext.getAccount.persistedId}]: Browse alone")
      throw NotFoundException(s"Asset [${asset.persistedId}] not found")

  /** Whether the account may create a Library: an Admin by type, anyone else by the Create Libraries flag */
  def mayCreateLibraries(user: User): Boolean = user.accountType == AccountType.Admin || user.canCreateLibraries

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
