package altitude.core
import cask.router.Decorator
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.handlers.GracefulShutdownHandler
import java.time.ZoneId
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.routes.api.AlbumController
import altitude.core.routes.api.AssetController
import altitude.core.routes.api.CurationController
import altitude.core.routes.api.EditController
import altitude.core.routes.api.FolderController
import altitude.core.routes.api.HealthController
import altitude.core.routes.api.LocationController
import altitude.core.routes.api.MapController
import altitude.core.routes.decorators
import altitude.core.routes.web.ContentViewController
import altitude.core.routes.web.ImportController
import altitude.core.routes.web.IndexController
import altitude.core.routes.web.SessionController
import altitude.core.routes.web.SettingsController
import altitude.core.routes.web.SetupController
import altitude.core.routes.web.StaticController
import altitude.core.routes.web.StyleGuideController
import altitude.core.routes.web.UsersController
import altitude.core.routes.web.partial.AccountInfoController
import altitude.core.routes.web.partial.AlbumActionController
import altitude.core.routes.web.partial.AssetActionController
import altitude.core.routes.web.partial.CurationActionController
import altitude.core.routes.web.partial.FolderActionController
import altitude.core.routes.web.partial.ImportHistoryController
import altitude.core.routes.web.partial.LibraryActionController
import altitude.core.routes.web.partial.LocationActionController
import altitude.core.routes.web.partial.NavController
import altitude.core.routes.web.partial.OwnPasswordController
import altitude.core.routes.web.partial.PeopleActionController
import altitude.core.routes.web.partial.SearchPanelController
import altitude.core.routes.web.partial.SearchResultsController
import altitude.core.routes.web.partial.SettingsFormController
import altitude.core.routes.web.partial.SetupFormController
import altitude.core.routes.web.partial.TrashActionController
import altitude.core.routes.web.partial.TriageController
import altitude.core.routes.web.partial.UserActionController
import altitude.core.routes.web.partial.ViewSettingsActionController
import altitude.core.routes.web.partial.WatchFolderController

object App extends cask.Main:
  given logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The instance, built and started by the first to ask for it: `main`, or a test's server.
   *
   * Not in this object's initializer. While a class initializes, every other thread that calls into it waits for the initializer
   * to end, and the stop's cleanup, which runs on a shutdown thread, is a function of this object: a SIGTERM during the startup
   * tasks would drain nothing until they had ended.
   */
  lazy val altitude: Altitude = start()

  /** The HTTP server, once [[main]] has built it: the stop's last task stops it after the cleanup */
  @volatile private var server: Option[Undertow] = None

  /** Builds the instance, registers its cleanup for the stop, and runs the startup tasks */
  private def start(): Altitude =
    // Before anything is built: logback is logging by the default in place of a logging variable it could not take
    requireLogSettings()

    // The code uses the org.opencv API, but its native library is loaded through org.bytedeco: the usual
    // `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)` does not link it. https://stackoverflow.com/a/58064096/53687
    Loader.load(classOf[opencv_java])

    val instance = new Altitude()

    // From the moment there is an instance to clean up, and not from `main`, which comes after the startup tasks: those can
    // take long (a large Library's stats, requeued purges and renders, a Watch folder's first scan), and a SIGTERM during them
    // drains the queues and closes the database as any other does. The functions take the local instance, never `altitude`,
    // which a shutdown thread would wait for while the start is still under way.
    ServerShutdown.registerCleanup(instance.actorSystem, cleanup = () => instance.cleanup(), stopServer = () => stopServer())

    // Cached in memory: whether the instance is past setup
    instance.setIsInitializedState()

    // Before anything is offered to the import queue: what is pending now was cut off when the server last stopped
    instance.service.imports.startup()
    instance.service.library.pruneDanglingAssets()
    instance.service.library.requeuePurgePending()
    instance.service.editRender.requeuePendingRenders()
    instance.service.library.reconcileStats()
    // After the interrupted Imports are failed, so no Watch folder scan's Import is taken for one of them
    instance.service.watchFolder.start()

    instance

  /** The address the server binds: `server.host` and `server.port` */
  override def host: String = altitude.config.getString(Const.Conf.SERVER_HOST)

  override def port: Int = altitude.config.getInt(Const.Conf.SERVER_PORT)

  /** In debug mode cask answers an error with its stack trace, which holds SQL and paths: dev alone */
  override def debugMode: Boolean = Environment.isDev

  /**
   * Builds and starts the HTTP server itself rather than through cask's `main`, which registers a shutdown hook that stops the
   * server at once. The stop is `ServerShutdown`'s instead: new requests refused, those under way given time, the instance
   * cleaned up, and only then the server stopped.
   */
  override def main(args: Array[String]): Unit =
    if !verbose then cask.main.Main.silenceJboss()

    // The whole start, before there is a server: no request arrives before the startup tasks are done
    val system = altitude.actorSystem

    val requests = GracefulShutdownHandler(defaultHandler)
    val undertow = Undertow.builder
      .addHttpListener(port, host)
      // Without it the stop waits without limit for a request still held
      .setServerOption[Integer](UndertowOptions.SHUTDOWN_TIMEOUT, ServerShutdown.SERVER_STOP_TIMEOUT.toMillis.toInt)
      .setHandler(requests)
      .build
    server = Some(undertow)
    ServerShutdown.registerRequests(system, requests)

    // A stop that began during the start has cleaned the instance up, or is doing so: nothing is served on it
    if ServerShutdown.hasBegun(system) then logger.info("The stop began during the start: the HTTP server is not started")
    else
      undertow.start()
      logStart()

  /** Stops the HTTP server, if [[main]] has built one, and cask's execution context; whether there was a server to stop */
  private def stopServer(): Boolean =
    val built = server
    built.foreach(_.stop())
    // cask's own, which runs the WebSocket actors
    executionContext.shutdown()
    built.isDefined

  /**
   * Fails the start when `ALTITUDE_LOG_FORMAT` or `ALTITUDE_LOG_LEVEL` has an unknown value, in any environment. The refusal
   * names the variable and its accepted values. It is logged as well as thrown: thrown from the start, it reaches the console as
   * an uncaught exception's stack trace, on stderr alone.
   */
  private def requireLogSettings(): Unit =
    try LogSettings.requireValid(sys.props.toMap, sys.env)
    catch
      case refusal: IllegalArgumentException =>
        logger.error(refusal.getMessage)
        throw refusal

  /** What a person starting the server needs to see: where it listens, where its files are, and what is unsafe about it */
  private def logStart(): Unit =
    // An IPv6 literal is bracketed in a URL
    val address = s"http://${if host.contains(":") then s"[$host]" else host}:$port"
    logger.info(s"Altitude is listening on $address (environment: ${Environment.CURRENT})")
    logger.info(s"Data directory: ${altitude.dataPath}")
    logger.info(s"Models directory: ${altitude.models.root}")
    // Date Imported's day is a day in this zone
    logger.info(s"Time zone: ${ZoneId.systemDefault}")

    if !altitude.secureCookies then
      logger.warn(
        s"${Const.Conf.SERVER_SECURE_COOKIES} is false: the session cookie is sent over plain HTTP, where the network can read " +
          "it. This is for a LAN or VPN address alone; anywhere else, serve HTTPS and set it back to true.")

    if !altitude.isInitialized then
      logger.warn(
        s"Altitude is not set up yet and no Admin exists: the first visitor to $address runs Setup and becomes the Admin. " +
          "Run Setup now, before anyone else can reach this address.")

  override def mainDecorators: Seq[Decorator[?, ?, ?, ?]] =
    Seq(decorators.compress(), decorators.requestResponseLogger(), decorators.repoContext())

  override def allRoutes: Seq[cask.Routes] = appRoutes ++ devRoutes

  /** Routes that exist only in dev; anywhere else their paths answer 404 */
  private def devRoutes: Seq[cask.Routes] =
    if Environment.isDev then
      logger.info("Style guide enabled at /style-guide/r/<repoId>")
      Seq(new StyleGuideController)
    else Seq.empty

  private def appRoutes: Seq[cask.Routes] = Seq(
    new HealthController,
    new AlbumController,
    new LocationController,
    new MapController,
    new AssetController,
    new CurationController,
    new EditController,
    new FolderController,
    new IndexController,
    new StaticController,
    new SessionController,
    new SetupController,
    new ImportController,
    new SettingsController,
    new UsersController,
    new ContentViewController,

    // partials
    new SetupFormController,
    new SettingsFormController,
    new NavController,
    new LibraryActionController,
    new UserActionController,
    new OwnPasswordController,
    new AccountInfoController,
    new AlbumActionController,
    new LocationActionController,
    new AssetActionController,
    new CurationActionController,
    new FolderActionController,
    new PeopleActionController,
    new SearchResultsController,
    new SearchPanelController,
    new TrashActionController,
    new TriageController,
    new ImportHistoryController,
    new WatchFolderController,
    new ViewSettingsActionController
  )
