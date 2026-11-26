package altitude.core
import cask.router.Decorator
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.handlers.GracefulShutdownHandler
import java.time.ZoneId
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.opencv.opencv_java
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

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
   * The start's outcome: the instance, built and started by the first to ask for it (`main`, or a test's server), or what the
   * start failed with. A start that failed stays failed: asking again answers the same failure, and never starts a second time
   * over what the first left running.
   *
   * Not in this object's initializer. While a class initializes, every other thread that calls into it waits for the initializer
   * to end, and the stop's cleanup, which runs on a shutdown thread, is a function of this object: a SIGTERM during the startup
   * tasks would drain nothing until they had ended.
   */
  private lazy val started: Either[Throwable, Altitude] =
    try Right(start())
    catch case failure: Throwable => Left(failure)

  /** The instance. Where the start failed, this throws what it failed with, each time it is asked. */
  lazy val altitude: Altitude = started.fold(failure => throw failure, identity)

  /** The instance the start built, whether or not the start then finished: its stop may have begun meanwhile */
  @volatile private var built: Option[Altitude] = None

  /** The HTTP server, once [[main]] has built it: the stop's last task stops it after the cleanup */
  @volatile private var server: Option[Undertow] = None

  /** Builds the instance, registers its cleanup for the stop, and runs the startup tasks */
  private def start(): Altitude =
    // First of all, before anything can spool to it: what an earlier run left in a temp folder that is the server's own
    TempFolder.prepare()

    // Before anything is built: logback is logging by the default in place of a logging variable it could not take. An unknown
    // `ALTITUDE_LOG_FORMAT` or `ALTITUDE_LOG_LEVEL` fails the start in any environment, naming the variable and its accepted
    // values, and `main` logs the refusal as it logs any failed start.
    LogSettings.requireValid(sys.props.toMap, sys.env)

    // Before ONNX Runtime or sqlite-jdbc loads, and before a database connection loads sqlite-vector: a native jar's start points
    // their loaders at its libraries, unpacked once into the per-user cache, instead of the temp folder. Elsewhere it does nothing.
    NativeLibraries.prepare()

    // The code uses the org.opencv API, but its native library is loaded through org.bytedeco: the usual
    // `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)` does not link it. https://stackoverflow.com/a/58064096/53687
    Loader.load(classOf[opencv_java])
    // FFmpeg's libraries, all of them, now rather than at the first Video: on Linux `avdevice` links ALSA and XCB, and without
    // them the start fails naming the packages to install (`SystemLibraries`), where a Video's import would fail on its own
    FFmpegFrameGrabber.tryLoad()

    val instance = new Altitude()
    built = Some(instance)

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
  override def host: String = altitude.serverHost

  override def port: Int = altitude.serverPort

  /** In debug mode cask answers an error with its stack trace, which holds SQL and paths: dev alone */
  override def debugMode: Boolean = Environment.isDev

  /**
   * Starts the server, and ends the process when the start fails, with status 1 and the reason in an ERROR line.
   *
   * A failed start cannot be left to end by itself. Once the instance's actor system exists, its threads, which are no daemon
   * threads, keep the JVM up with no HTTP server: a container then sits `starting` until its health check's start period is over,
   * and no restart policy ever fires. That holds for whatever fails from there on: a Scene model that does not open, a startup
   * task, an address that cannot be bound. The exit runs the shutdown hooks, so an instance that was built is cleaned up by the
   * stop's task.
   */
  override def main(args: Array[String]): Unit =
    try serve()
    catch
      // A start that fails because the stop is cleaning its instance up is no failed start: the process is ending already
      case failure: Throwable if built.exists(instance => ServerShutdown.hasBegun(instance.actorSystem)) =>
        logger.info(s"The start ended with the stop that began during it (${reasonOf(failure)})")
      case failure: Throwable =>
        logger.error(s"Altitude could not start: ${reasonOf(failure)}", failure)
        System.exit(1)

  /**
   * What a start failed with, in a line: the message of the refusal itself, where an initializer's error wraps it, or, for a
   * system library that OpenCV or FFmpeg could not load, its name and the packages to install
   */
  private def reasonOf(failure: Throwable): String =
    SystemLibraries.missingFrom(failure, sys.props.getOrElse("os.name", "")).getOrElse {
      val refusal = failure match
        // What an object throws as it initializes (`Environment`, of a value that names no environment) arrives as the cause
        case wrapped: ExceptionInInitializerError if wrapped.getCause != null => wrapped.getCause
        case other => other
      Option(refusal.getMessage).getOrElse(refusal.toString)
    }

  /**
   * Builds and starts the HTTP server itself rather than through cask's `main`, which registers a shutdown hook that stops the
   * server at once. The stop is `ServerShutdown`'s instead: new requests refused, those under way given time, the instance
   * cleaned up, and only then the server stopped.
   */
  private def serve(): Unit =
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
      // Undertow's own failure names neither the address nor, in its message, more than the cause's class
      try undertow.start()
      catch
        case NonFatal(e) =>
          val cause = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).toSeq.last
          throw IllegalStateException(s"The HTTP server cannot listen on $host, port $port: ${cause.getMessage}", e)
      logStart()

  /** Stops the HTTP server, if [[main]] has built one, and cask's execution context; whether there was a server to stop */
  private def stopServer(): Boolean =
    val built = server
    built.foreach(_.stop())
    // cask's own, which runs the WebSocket actors
    executionContext.shutdown()
    built.isDefined

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
