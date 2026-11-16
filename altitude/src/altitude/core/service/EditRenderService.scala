package altitude.core.service

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Source
import org.opencv.core.Mat
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.AltitudeActorSystem
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.AssetDao
import altitude.core.dao.AssetEditDao
import altitude.core.models.EditRecipe
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineUtils.withContext
import altitude.core.pipeline.QueuedPipeline
import altitude.core.transactions.TransactionManager
import altitude.core.util.EditRenderer
import altitude.core.util.ImageUtil

object EditRenderService:

  /** How long after the last save of an Edit its render is offered to the queue */
  val DEBOUNCE: FiniteDuration = C.Edit.RENDER_DEBOUNCE_MS.millis

  /** Retries of a failed render, the delay doubling from twice the debounce; after the last, the next startup tries again */
  val MAX_RETRIES = 5

  enum Outcome:
    /** The edited images are stored under revision `rev` */
    case Rendered(editVersion: Int, rev: Int)

    /** The Edit is the identity: the asset shows its original again and has no edited images */
    case Original(editVersion: Int)

    /** The Edit was saved again while the render ran: nothing is stored, and the asset is queued again */
    case Discarded

    case UpToDate

    /** The asset or its Edit is gone (purged, or being purged) */
    case Gone

    /**
     * The original is missing or cannot be decoded. The Edit is recorded rendered with no edited images, so the asset shows its
     * original and the next startup does not retry it; a later save is rendered anew.
     */
    case Unrenderable(editVersion: Int)

  /** What a render made of the Edit, for its write */
  private[service] enum Made:
    /** The edited Preview and the Display image, as WebP bytes */
    case Images(preview: Array[Byte], display: Array[Byte])

    /** Nothing: the Edit is the identity */
    case Original

    /** Nothing: the Edit cannot be rendered, and why */
    case Failed(why: String)

/**
 * Renders an asset's Edit into its edited Preview and Display image on the `edit-render` queue. [[enqueue]] is debounced, so a
 * run of saves is rendered once, at the edit version it ends on. A render works outside any transaction and publishes only if the
 * Edit is still at the edit version it read; otherwise the asset is queued again. One render of an asset runs at a time. Each
 * render replaces the asset's edited images under a revision (`asset.edit_rev`) never reused for the asset, so a content URL
 * naming it is new for each render. What was pending at shutdown is queued again at startup ([[requeuePendingRenders]]).
 */
class EditRenderService(app: Altitude):
  import EditRenderService.Made
  import EditRenderService.Outcome

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: AssetEditDao = app.DAO.assetEdit
  private val assetDao: AssetDao = app.DAO.asset
  private val txManager: TransactionManager = app.txManager

  private given system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  /** A render scheduled by [[enqueue]], offered once its delay is over unless a later call replaced it */
  final private class Scheduled(val context: PipelineContext):
    @volatile var timer: Option[Cancellable] = None

  /** The delay of [[enqueue]]; a test that renders an Edit itself lengthens it, so the queue never races it */
  @volatile var debounce: FiniteDuration = EditRenderService.DEBOUNCE

  /** Test hook run before each queued render, with the asset's ID; a test throws there to make the render fail */
  @volatile private[core] var beforeQueuedRender: String => Unit = _ => ()

  // Set by shutdown, after which no render is queued: the next startup queues what is left
  @volatile private var stopping = false

  // The renders under way, which shutdown waits for
  private val underWay = ConcurrentHashMap.newKeySet[Future[Unit]]()

  // By asset: the render waiting out its delay
  private val scheduled = ConcurrentHashMap[String, Scheduled]()

  // The assets being rendered by the queue: one render of an asset at a time, as its images are staged under one name each
  private val rendering = ConcurrentHashMap.newKeySet[String]()

  // By asset: how many renders in a row have failed, which sets the retry delay
  private val failures = ConcurrentHashMap[String, Int]()

  private val queue = QueuedPipeline[(String, PipelineContext)](
    "edit-render",
    Flow[(String, PipelineContext)].mapAsyncUnordered(app.editRenderParallelism) {
      (assetId, context) =>
        val rendering = renderedOnDispatcher(assetId, context)
        underWay.add(rendering)
        rendering.onComplete(_ => underWay.remove(rendering))(using ExecutionContext.parasitic)
        rendering
    },
    describe = (assetId, _) => s"the Edit of asset [$assetId]",
    // What the queue holds is dropped at shutdown and found again at startup, so it need hold no more than render at once
    bufferSize = app.editRenderParallelism,
    shutdownTimeout = () => app.settings.current.pipelineShutdownTimeout.seconds,
    restartOnFailure = app.isPipelineRestartEnabled
  )

  /** Queues the asset's Edit, in the context repository, to render after `delay`; each call restarts a waiting render's delay */
  def enqueue(assetId: String, delay: FiniteDuration = debounce): Unit =
    if stopping then
      logger.debug(s"Render of the Edit of asset [$assetId] not queued: the server is stopping")
      return
    val next = Scheduled(
      PipelineContext(RequestContext.getRepository, RequestContext.account.value, RequestContext.permissions.value))
    scheduled.compute(
      assetId,
      (_, previous) =>
        Option(previous).flatMap(_.timer).foreach(_.cancel())
        next.timer = Some(system.scheduler.scheduleOnce(delay, () => fire(assetId, next))(using system.executionContext))
        next
    ): Unit
    logger.debug(s"Render of the Edit of asset [$assetId] queued in $delay")

  // The delay is over: the render is offered unless a later call replaced it, whose own delay runs on
  private def fire(assetId: String, mine: Scheduled): Unit =
    if scheduled.remove(assetId, mine) && !stopping then offer(assetId, mine.context): Unit

  private def offer(assetId: String, context: PipelineContext): Future[Unit] =
    given ExecutionContext = system.executionContext
    queue
      .offer((assetId, context))
      .recover {
        // The queue logged why; the edit version stays unrendered, which the next startup queues again
        case NonFatal(_) => logger.warn(s"The Edit of asset [$assetId] is not rendered until the next start")
      }

  /**
   * Queues, in every repository, the Edits whose edit version is not rendered (the server stopped first, or the render failed),
   * one after another under the queue's backpressure, with no account since no request queued them
   */
  def requeuePendingRenders(): Unit =
    given ExecutionContext = system.executionContext
    app.service.library.forEachRepository {
      repository =>
        val pending = txManager.asReadOnly(dao.pendingRenders())
        if pending.nonEmpty then
          logger.info(s"Queueing ${pending.size} Edits to render. Repo: ${repository.name}")
          val context = PipelineContext(repository, account = None)
          Source(pending).mapAsync(1)(offer(_, context)).run(): Unit
    }

  /** Takes no more renders, drops the queued ones and waits up to the shutdown timeout for those under way */
  def shutdown(): Unit =
    stopping = true
    scheduled.values.forEach(_.timer.foreach(_.cancel()))
    scheduled.clear()
    queue.stop()
    val timeout = app.settings.current.pipelineShutdownTimeout.seconds
    try Await.ready(Future.sequence(underWay.asScala.toList)(using implicitly, system.executionContext), timeout): Unit
    catch case _: TimeoutException => logger.warn(s"Renders of Edits still under way after $timeout are left as they are")

  /**
   * Runs the render on the render dispatcher, returning a future that completes whatever it throws. Under `Future.apply` a fatal
   * error (an `OutOfMemoryError` decoding a huge original) would leave the future pending, losing the queue's slot for good, and
   * Pekko's handler of an uncaught fatal error would stop the JVM. So any `Throwable` is logged and the edit version left for the
   * next startup; [[renderOrLog]] has already retried what is not fatal.
   */
  private def renderedOnDispatcher(assetId: String, context: PipelineContext): Future[Unit] =
    val rendered = Promise[Unit]()
    app.editRenderDispatcher.execute {
      () =>
        try withContext(context)(renderOrLog(assetId))
        catch
          case fatal: Throwable =>
            if fatal.isInstanceOf[InterruptedException] then Thread.currentThread.interrupt()
            logger.error(
              s"The Edit of asset [$assetId] could not be rendered: a fatal error; it is tried again at the next start",
              fatal)
        finally rendered.success(())
    }
    rendered.future

  /**
   * Renders the asset, or queues it again when a render of it is under way. A render that throws is retried with a doubling delay
   * up to [[EditRenderService.MAX_RETRIES]] times, so images published before a failed commit are not served under the old
   * revision for long.
   */
  private def renderOrLog(assetId: String): Unit =
    if !rendering.add(assetId) then
      logger.debug(s"The Edit of asset [$assetId] is being rendered already: queued again")
      enqueue(assetId)
      return
    try
      beforeQueuedRender(assetId)
      render(assetId): Unit
      failures.remove(assetId): Unit
    catch
      case NonFatal(ex) =>
        val tries = failures.merge(assetId, 1, _ + _)
        if tries <= EditRenderService.MAX_RETRIES then
          val delay = debounce * (1L << tries)
          logger.error(s"The Edit of asset [$assetId] could not be rendered (failure $tries); it is tried again in $delay", ex)
          enqueue(assetId, delay)
        else
          failures.remove(assetId): Unit
          logger.error(
            s"The Edit of asset [$assetId] could not be rendered $tries times; it is tried again at the next start",
            ex)
    finally rendering.remove(assetId): Unit

  /**
   * Renders the asset's Edit in three steps: reads the Edit in a read transaction; renders and stages the edited images (written
   * beside the asset's and flushed) outside any transaction; then publishes them ([[store]]) if the Edit is still at the edit
   * version read. An Edit saved again meanwhile discards the result, staged images included, and queues the asset again.
   *
   * `beforeWrite` (between the render and the write) and `beforeDelete` (between the commit and the delete of the edited images)
   * let a test save the Edit there.
   */
  def render(assetId: String, beforeWrite: () => Unit = () => (), beforeDelete: () => Unit = () => ()): Outcome =
    val started = System.nanoTime()
    // None once the asset is gone (its Edit goes with it); Some(None) when the Edit is rendered at its edit version already
    val read = txManager.asReadOnly {
      dao
        .get(assetId)
        .map(edit => Option.unless(edit.renderedVersion.contains(edit.editVersion))((edit.editVersion, edit.recipe)))
    }

    read match
      case None =>
        logger.debug(s"Asset [$assetId] is gone: its Edit is not rendered")
        Outcome.Gone
      case Some(None) =>
        logger.debug(s"The Edit of asset [$assetId] is rendered at its edit version already")
        Outcome.UpToDate
      case Some(Some((editVersion, recipe))) =>
        val original = app.service.fileStore.assetFile(assetId)
        val made =
          if recipe.isIdentity then Made.Original
          else if !Files.isRegularFile(original) then Made.Failed("its original is missing")
          else
            try
              val (preview, display) = editedImagesOf(original, recipe)
              Made.Images(preview, display)
            catch case NonFatal(ex) => Made.Failed(s"its original cannot be rendered: $ex")
        val outcome =
          try
            made match
              case Made.Images(preview, display) =>
                app.service.fileStore.stageEditedImage(assetId, C.DataStore.PREVIEW, preview)
                app.service.fileStore.stageEditedImage(assetId, C.DataStore.DISPLAY, display)
              case _ => ()
            beforeWrite()
            store(assetId, editVersion, made, beforeDelete)
          // Drops what was staged and not published; published files are no longer staged
          finally if made.isInstanceOf[Made.Images] then app.service.fileStore.discardStagedEditedImages(assetId)
        val ms = (System.nanoTime() - started) / 1000000
        outcome match
          case Outcome.Rendered(rendered, rev) =>
            logger.info(s"Edit of asset [$assetId]: edit version [$rendered] rendered as revision [$rev] in $ms ms")
          case Outcome.Original(rendered) =>
            logger.info(
              s"Edit of asset [$assetId]: edit version [$rendered] is the original, its edited images dropped in $ms ms")
          case Outcome.Unrenderable(rendered) =>
            val why = made match
              case Made.Failed(why) => why
              case _ => ""
            logger.warn(
              s"Edit of asset [$assetId]: edit version [$rendered] cannot be rendered, $why; the asset shows its original")
          case Outcome.Discarded =>
            logger.debug(s"Edit of asset [$assetId]: saved again during the render of edit version [$editVersion], queued again")
            enqueue(assetId)
          case other => logger.debug(s"Edit of asset [$assetId]: $other")
        outcome

  /** The edited Preview and the Display image of the Edit, as WebP bytes */
  private def editedImagesOf(original: java.nio.file.Path, recipe: EditRecipe): (Array[Byte], Array[Byte]) =
    val image: Mat = EditRenderer.render(original, recipe, Some(C.Edit.DISPLAY_LONG_EDGE))
    try
      (
        ImageUtil.makeImageThumbnail(image, 1, C.AssetView.PREVIEW_BOX_PIXELS),
        ImageUtil.encodeWebp(image, C.Edit.DISPLAY_WEBP_QUALITY))
    finally image.release()

  /**
   * Writes a render if the Edit is still at `editVersion`, under the locks of the asset and its Edit (in that order, as a purge's
   * delete takes them). Staged images are published by rename under a revision after every one the asset has used, before the
   * commit that names it in `edit_rev`, so the files are in place first. The identity or an unrenderable Edit sets `edit_rev` to
   * 0, and the edited images are deleted after the commit ([[deleteUnshownImages]]).
   */
  private def store(assetId: String, editVersion: Int, made: Made, beforeDelete: () => Unit): Outcome =
    txManager.withTransaction {
      val edit = if assetDao.lockUnpurged(assetId) then dao.getForUpdate(assetId) else None
      edit match
        case None => Outcome.Gone
        case Some(edit) if edit.renderedVersion.contains(edit.editVersion) => Outcome.UpToDate
        case Some(edit) if edit.editVersion != editVersion => Outcome.Discarded
        case Some(edit) =>
          val (rev, outcome) = made match
            case Made.Images(_, _) =>
              val next = edit.lastRev + 1
              app.service.fileStore.publishEditedImage(assetId, C.DataStore.PREVIEW)
              app.service.fileStore.publishEditedImage(assetId, C.DataStore.DISPLAY)
              (next, Outcome.Rendered(editVersion, next))
            case Made.Original => (0, Outcome.Original(editVersion))
            case Made.Failed(_) => (0, Outcome.Unrenderable(editVersion))
          dao.setRendered(assetId, editVersion, Math.max(edit.lastRev, rev))
          assetDao.updateById(assetId, Map(FieldConst.Asset.EDIT_REV -> rev))
          if rev == 0 then
            txManager.afterCommit {
              beforeDelete()
              deleteUnshownImages(assetId)
            }
          outcome
    }

  /**
   * Deletes the asset's edited images if, under its lock, it still shows its original (`edit_rev` 0): a render committed since
   * keeps its images. An asset being purged is skipped; the purge deletes them.
   */
  private def deleteUnshownImages(assetId: String): Unit =
    txManager.withTransaction {
      if assetDao.lockUnpurged(assetId) && assetDao.getById(assetId).editRev == 0 then
        app.service.fileStore.deleteEditedImages(assetId)
        logger.debug(s"Edit of asset [$assetId]: its edited images deleted")
    }
