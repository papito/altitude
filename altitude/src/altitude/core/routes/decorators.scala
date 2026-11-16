package altitude.core.routes
import cask.model.Cookie
import cask.model.FormEntry
import cask.model.Response
import cask.model.Response.Raw
import cask.model.Status
import cask.router.Result
import io.undertow.server.handlers.form.FormParserFactory
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
   * cask's gzip decorator, except for a response that offers byte ranges (a range names raw bytes, which a compressed body would
   * not match) and for an image (deflated already: gzip costs CPU, saves nothing and drops Content-Length). A response that may
   * be gzipped says so in `Vary`, so a cache keeps a copy per coding; a 304 varies as its 200 does.
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

  /**
   * cask's `postForm`, except for a body it cannot read as a form (no content type, a content type that is no form's, a multipart
   * body with no boundary, a broken body). cask answers that 400 with the parser's exception and its stack trace, whatever the
   * debug mode; this answers the bare 400 a request that cannot be bound gets outside dev, in every environment. A body that is a
   * form goes to the route as cask's own endpoint passes it.
   */
  class formPost(path: String) extends cask.postForm(path):
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      formOf(req) match {
        case Some(form) => delegate(req, form)
        case None => Result.Success(Response(s"Error 400: ${Status.codesToStatus(400).reason}", statusCode = 400))
      }

    /** The form's fields as cask reads them, each name with its values; none when the body is no form */
    private def formOf(req: cask.Request): Option[Map[String, Seq[FormEntry]]] =
      try
        // Undertow has a parser for a form's content types alone, and none for a multipart body that names no boundary
        val parser = Option(FormParserFactory.builder().withDefaultCharset("utf-8").build().createParser(req.exchange))
        if parser.isEmpty then logger.debug(s"A POST to ${req.exchange.getRequestPath} is no form: its content type is not one")
        parser.map {
          parser =>
            val data = parser.parseBlocking()
            data.iterator().asScala.map(name => name -> data.get(name).asScala.map(FormEntry.fromUndertow).toSeq).toMap
        }
      catch
        case NonFatal(e) =>
          logger.debug(s"A POST to ${req.exchange.getRequestPath} is no form: its body cannot be parsed", e)
          None

  private val AUTH_HEADER_NAME = "Authorization"
  private val BEARER_PREFIX = "Bearer "

  /** `renewedToken` replaces the request's stale one as the answer's cookie */
  final case class Login(claims: TokenClaims, renewedToken: Option[String])

  /** `cookies` clears the session cookie when its token was refused, so the browser stops sending it */
  final case class NoLogin(cookies: Seq[Cookie])

  /**
   * The dev user, or the token of the Bearer header or else the session cookie. A stale cookie token is rebuilt to be replaced; a
   * stale Bearer token cannot be replaced, so it is refused as an invalid, expired or revoked one is.
   */
  def authenticate(req: cask.Request): Either[NoLogin, Login] =
    // Locally, the dev user bypasses the login, so a hot-reload restart (a new token key) does not log the developer out
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
   * Whether a WebSocket handshake comes from a page of this server or from a non-browser client, so a page of another site cannot
   * open the socket with the visitor's session. No Origin passes: every browser sends one, so only another kind of client,
   * holding the token itself, omits it. Otherwise the Origin's host and port must equal the Host header's, case aside; a browser
   * omits a default port from both, and a reverse proxy that keeps Host keeps them equal.
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

  /** A path naming a repository that `repoContext` did not find is answered as not found */
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
   * Requires a login. Without one, the answer also clears a session cookie whose token will never be good again. A repository in
   * the path the account cannot see is answered as not found, as any foreign entity is, and the gate reads only the token, no
   * database. The token's Permissions, grants and expiry go into `RequestContext`, and a rebuilt token becomes the answer's
   * cookie.
   */
  class requireLogin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      authenticate(req) match
        case Right(login) =>
          val answer =
            accessibleRepositoryOnly(req, login.claims)(delegate(req, Map("request" -> req, "user" -> login.claims.account)))
          val cookies = renewedCookies(login)
          // An answer that sets the session cookie itself keeps it: a password change mints its token after the rebuild, which
          // the change made stale
          def setsSession(v: Raw) = v.cookies.exists(_.name == SessionController.AUTH_COOKIE_NAME)
          if cookies.isEmpty then answer
          else answer.transform { case v: Raw => if setsSession(v) then v else v.copy(cookies = v.cookies ++ cookies) }
        case Left(noLogin) => handleUnauthenticated(req, noLogin.cookies)

    /** A repository the token gives no access to and one that does not exist are both not found, so they cannot be told apart */
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
     * An htmx request gets a 401 with `HX-Redirect` to the login, coming back to the page it was sent from: a plain redirect
     * would be followed by the fetch and the login page swapped in as the partial
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

  /** The path and query of `HX-Current-URL`; None when it is missing, no URL, or has no absolute path */
  private def pageOf(req: cask.Request): Option[String] =
    Option(req.exchange.getRequestHeaders.getFirst("HX-Current-URL")).flatMap {
      url =>
        try
          val uri = java.net.URI(url)
          Option(uri.getRawPath).filter(_.startsWith("/")).map(path => path + Option(uri.getRawQuery).fold("")("?" + _))
        catch case _: java.net.URISyntaxException => None
    }

  /**
   * For an endpoint a signed URL may open without a login. A request with both `exp` and `sig` is answered by the signature
   * alone: served with no account when it verifies for the path, else a plain-text 403, since a share-link holder has no account
   * to log in to. Any other request is `requireLogin`'s, so the logged-in UI builds plain URLs to the same route.
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
        // The answer does not say why; the log does, never with the signature. An expired link is routine, so DEBUG; a mismatch
        // otherwise is a tampered or forged link or a retired key, so WARN, as an invalid login token is
        if exp.toLongOption.exists(_ <= now.getEpochSecond) then
          logger.debug(s"Signed URL for [$requested] refused: expired at [$exp]")
        else logger.warn(s"Signed URL for [$requested] refused: the signature does not match")
        Result.Success(
          Response(
            "Forbidden: the link is invalid or has expired",
            statusCode = 403,
            headers = Seq("Content-Type" -> "text/plain")))

  /** The account `requireLogin` put in the context; throws when it did not run first */
  private def loggedIn(req: cask.Request, decorator: String): User =
    RequestContext.account.value.getOrElse(
      throw IllegalStateException(s"$decorator on ${req.exchange.getRequestPath} ran without requireLogin before it"))

  /**
   * Runs the endpoint when the logged-in account is `allowed`, else a 403 saying `why`: there is nowhere to redirect it to. Every
   * access decorator below is this, applied after `requireLogin`.
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

  /** The one 403 for lack of a Permission, whether from a decorator, a route or a service's `ForbiddenException` */
  def forbidden(why: String, json: Boolean): Response[String] =
    if json then Response(ujson.write(ujson.Obj("error" -> why)), 403, Seq("Content-Type" -> "application/json"))
    else Response(s"Forbidden: $why", 403, Seq("Content-Type" -> "text/plain"))

  class requireAdmin extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireAdmin", Const.Msg.Err.FORBIDDEN_ADMIN)(_.accountType == AccountType.Admin)

  /**
   * Needs any one of `permissions` in the path's Library; an Admin holds every one, a Guest none. A rule that depends on the
   * asset (Curate own, recycling a sorted or a triaged one) is the service's (`AssetRules`). `RouteTableTests` reads `anyOf` off
   * the route.
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
   * For a view a Guest does not see that needs no Permission (the Triage partials): a Guest sees Browse alone. A view that does
   * need one is `requirePermission`'s, which refuses a Guest too.
   */
  class requireMember extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireMember", Const.Msg.Err.FORBIDDEN_GUEST)(_.accountType != AccountType.Guest)

  /**
   * For the Users page and adding an account: an Admin, or an account holding Create Users or Create Guests in any Library, not
   * only the path's. `UserService.create` holds what it may give to the creator's ceiling.
   */
  class requireAccountCreator extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireAccountCreator", Const.Msg.Err.FORBIDDEN_ACCOUNT_CREATOR)(
        _ => RequestContext.createsAccounts)

  class requireLibraryCreator extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      allowedOnly(req, delegate, "requireLibraryCreator", Const.Msg.Err.FORBIDDEN_LIBRARY_CREATOR)(mayCreateLibraries)

  /**
   * For the routes that take the view as a parameter, which no decorator reads: a Guest sees Browse alone, without the triaged
   * assets `triaged=true` adds
   */
  def maySeeView(view: String, triaged: Option[Boolean] = None): Boolean =
    RequestContext.isMember || (view == Const.Search.View.DEFAULT && !triaged.contains(true))

  /** A Guest's 403 for a view it does not see ([[maySeeView]]); None for a view the account sees */
  def refusedView(view: String, triaged: Option[Boolean], json: Boolean = false): Option[Response[String]] =
    Option.unless(maySeeView(view, triaged)) {
      logger.warn(s"Account [${RequestContext.getAccount.persistedId}] refused the view [$view] (triaged: $triaged): a Guest")
      forbidden(Const.Msg.Err.FORBIDDEN_GUEST, json)
    }

  /** A Guest sees Browse alone, so no triaged or recycled asset; a request with no account (a signed URL) may open any */
  def maySeeAsset(asset: Asset): Boolean = !RequestContext.isGuest || (!asset.isTriaged && !asset.isRecycled)

  /** An asset the account may not open is a `NotFoundException`, as an unknown ID is */
  def visibleOnly(asset: Asset): Asset =
    if maySeeAsset(asset) then asset
    else
      logger.debug(s"Asset [${asset.persistedId}] not shown to Guest [${RequestContext.getAccount.persistedId}]: Browse alone")
      throw NotFoundException(s"Asset [${asset.persistedId}] not found")

  def mayCreateLibraries(user: User): Boolean = user.accountType == AccountType.Admin || user.canCreateLibraries

  /**
   * For a full page of a repository: once it answers 200, the repository is recorded as the account's last active one, which
   * login and `/` open next. Partials and API routes do not carry it, so a nav reload or a search never moves where the user
   * lands. The page is rendered already, so a failure to record is only logged.
   */
  class rememberRepository extends cask.RawDecorator:
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] =
      // Fills the endpoint's extra parameter list, as in `allowedOnly`
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
   * For files whose names stay when their content changes (vendored libraries, fonts, JS modules): `no-cache` with an ETag, so an
   * unchanged file answers 304. The ETag hashes the bytes on every request, so a file refreshed under a running dev server is
   * never answered as unchanged; it is weak since `compress` may gzip the same bytes.
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
