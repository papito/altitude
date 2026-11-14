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

  /**
   * How many times a render that fails is queued again, each after twice the delay of the try before (from twice the debounce);
   * after the last, the Edit is left for the next startup
   */
  val MAX_RETRIES = 5

  /** What a render of an Edit came to */
  enum Outcome:
    /** The Edit's edited images are stored under the revision */
    case Rendered(editVersion: Int, rev: Int)

    /** The Edit is the identity: the asset shows its original again, and has no edited images */
    case Original(editVersion: Int)

    /** The Edit was saved again while the render ran: nothing is stored, and the asset is queued again */
    case Discarded

    /** The Edit is rendered at its edit version already: nothing to do */
    case UpToDate

    /** The asset or its Edit is gone (purged, or being purged): nothing to do */
    case Gone

    /**
     * The Edit cannot be rendered: its original is missing or cannot be decoded. It is recorded rendered with no edited images,
     * so the asset shows its original and the next startup does not try it again; a later save is rendered anew.
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
 * Renders an asset's Edit into the edited Preview and the Display image (`CONTEXT.md`), on the `edit-render` queue.
 * `AssetEditService` calls [[enqueue]] once a save has committed, in the request's context; the queue takes the asset 1.5 s after
 * the last such call ([[C.Edit.RENDER_DEBOUNCE_MS]]), so a run of saves is rendered once, at the edit version it ends on. A
 * render reads the Edit, renders and stages its images outside any transaction, and publishes them only if the Edit is still at
 * the edit version it read ([[render]]); otherwise it queues the asset again. One render of an asset runs at a time, and a render
 * that fails is queued again after a growing delay, [[EditRenderService.MAX_RETRIES]] times. The asset has one edited image of
 * each kind, replaced by each render under a revision (`asset.edit_rev`) that is never used twice for the asset, so a content URL
 * naming the revision a page knows is new for each render. What was pending when the server stopped is queued again at startup
 * ([[requeuePendingRenders]]).
 */
class EditRenderService(app: Altitude):
  import EditRenderService.Made
  import EditRenderService.Outcome

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: AssetEditDao = app.DAO.assetEdit
  private val assetDao: AssetDao = app.DAO.asset
  private val txManager: TransactionManager = app.txManager

  private given system: ActorSystem[AltitudeActorSystem.Command] = app.actorSystem

  /** An asset's render scheduled by [[enqueue]], to be offered once its delay is over unless a later call replaced it */
  final private class Scheduled(val context: PipelineContext):
    @volatile var timer: Option[Cancellable] = None

  /** The delay of [[enqueue]]; a test that renders an Edit itself lengthens it, so the queue never races it */
  @volatile var debounce: FiniteDuration = EditRenderService.DEBOUNCE

  /** Runs before each of the queue's renders, with the asset's ID; a test throws there, as a render failing in any way may */
  @volatile private[core] var beforeQueuedRender: String => Unit = _ => ()

  // Set by shutdown, after which no render is queued: the next startup queues what is left
  @volatile private var stopping = false

  // The renders under way, which shutdown waits for
  private val underWay = ConcurrentHashMap.newKeySet[Future[Unit]]()

  // By asset: the render waiting out its delay
  private val scheduled = ConcurrentHashMap[String, Scheduled]()

  // The assets being rendered by the queue: one render of an asset at a time, as its images are staged under one name each
  private val rendering = ConcurrentHashMap.newKeySet[String]()

  // By asset: how many renders in a row have failed, for the delay before the next try
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
    // What the queue holds is dropped at shutdown and found again at the next start, so it holds no more than are rendered at once
    bufferSize = app.editRenderParallelism,
    shutdownTimeout = () => app.settings.current.pipelineShutdownTimeout.seconds,
    restartOnFailure = app.isPipelineRestartEnabled
  )

  /**
   * Queues the asset's Edit, in the context repository, to be rendered `delay` from now ([[C.Edit.RENDER_DEBOUNCE_MS]] unless a
   * failed render's retry sets a longer one), which puts off a render already waiting for the asset: each call starts the delay
   * again
   */
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
   * Queues, in every repository, the Edits whose edit version is not rendered: the server stopped before their render, or it
   * failed. They are offered at once, one after another under the queue's backpressure, with no account: no request queued them.
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

  /**
   * Takes no more renders, drops the queued ones and waits, up to the shutdown timeout, for those under way: an edit version left
   * unrendered is queued again at the next start ([[requeuePendingRenders]])
   */
  def shutdown(): Unit =
    stopping = true
    scheduled.values.forEach(_.timer.foreach(_.cancel()))
    scheduled.clear()
    queue.stop()
    val timeout = app.settings.current.pipelineShutdownTimeout.seconds
    try Await.ready(Future.sequence(underWay.asScala.toList)(using implicitly, system.executionContext), timeout): Unit
    catch case _: TimeoutException => logger.warn(s"Renders of Edits still under way after $timeout are left as they are")

  /**
   * The queue's render of the asset on the render dispatcher, a future that completes whatever the render throws. `Future.apply`
   * would rethrow a fatal error (an `OutOfMemoryError` reading a huge original) on the dispatcher's thread and leave its future
   * pending, losing the queue's slot for good, and Pekko's handler of an uncaught fatal error stops the JVM; so the render runs
   * in a `try` that takes any `Throwable`, logs it and completes the future: the element is done, its render failed, and its edit
   * version is left unrendered for the next startup ([[renderOrLog]] has tried again what is not fatal).
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
   * The queue's render of the asset, unless one is under way, after which this one is queued again. A render that throws (its
   * images not staged, published or committed) is queued again after twice the delay of the try before, up to
   * [[EditRenderService.MAX_RETRIES]] times, so images published by a failed commit are not served under the old revision for
   * long; after that it is left for the next startup.
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
   * Renders the asset's Edit, in the context repository, in three steps: the Edit and its edit version are read in a read
   * transaction; the edited images are rendered and staged (written beside the images and flushed to the disk) outside any
   * transaction; then, with the asset and its Edit locked, they are published, each by a rename over the asset's, only if the
   * Edit is still at the edit version read, under the next revision (`last_rev + 1`), which the asset's `edit_rev` then names.
   * The identity, or an Edit that cannot be rendered, sets `edit_rev` to 0, and once that has committed, the asset's edited
   * images are deleted. Either way the edit version is then rendered. An Edit saved again meanwhile throws the result away,
   * staged images included, and queues the asset again.
   *
   * `beforeWrite` runs between the render and the write, and `beforeDelete` between the commit and the delete of the edited
   * images, for a test to save the Edit there.
   */
  def render(assetId: String, beforeWrite: () => Unit = () => (), beforeDelete: () => Unit = () => ()): Outcome =
    val started = System.nanoTime()
    // The asset's Edit (None once the asset is gone, which takes its Edit with it), and its edit version and Edit unless it is
    // rendered already
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
          // What was staged and not published goes: published, the files are no longer there
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

  /** The edited Preview and the Display image of the Edit, as WebP bytes, in that order */
  private def editedImagesOf(original: java.nio.file.Path, recipe: EditRecipe): (Array[Byte], Array[Byte]) =
    val image: Mat = EditRenderer.render(original, recipe, Some(C.Edit.DISPLAY_LONG_EDGE))
    try
      (
        ImageUtil.makeImageThumbnail(image, 1, C.AssetView.PREVIEW_BOX_PIXELS),
        ImageUtil.encodeWebp(image, C.Edit.DISPLAY_WEBP_QUALITY))
    finally image.release()

  /**
   * The write of a render, under the locks of the asset and of its Edit (in that order, the order a purge's delete takes them),
   * if the Edit is still at `editVersion`: the staged edited images published over the asset's under the revision after every one
   * it has used, which the asset's `edit_rev` then names, so the files are in place before the commit that names it; none for the
   * identity or an Edit that cannot be rendered, with `edit_rev` 0, whose edited images are deleted once that has committed
   * ([[deleteUnshownImages]]). The edit version is then the rendered one.
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
   * Deletes the asset's edited images while it shows its original (`edit_rev` 0), under the asset's lock: a render that stored
   * new images after the commit that set it to 0 has set it again, and its images stay. Nothing for an asset being purged, whose
   * purge deletes them.
   */
  private def deleteUnshownImages(assetId: String): Unit =
    txManager.withTransaction {
      if assetDao.lockUnpurged(assetId) && assetDao.getById(assetId).editRev == 0 then
        app.service.fileStore.deleteEditedImages(assetId)
        logger.debug(s"Edit of asset [$assetId]: its edited images deleted")
    }
