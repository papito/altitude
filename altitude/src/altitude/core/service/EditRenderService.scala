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

  /** How long after the last change of an Edit's head its render is offered to the queue */
  val DEBOUNCE: FiniteDuration = C.Edit.RENDER_DEBOUNCE_MS.millis

  /** What a render of an Edit came to */
  enum Outcome:
    /** The head's renditions are stored under the revision */
    case Rendered(seq: Int, rev: Int)

    /** The head is the Original: the asset shows its original again, and has no renditions */
    case Original(seq: Int)

    /** The head moved while the render ran: nothing is stored, and the asset is queued again */
    case Discarded

    /** The head is rendered already: nothing to do */
    case UpToDate

    /** The asset or its Edit is gone (purged, or being purged): nothing to do */
    case Gone

    /**
     * The head cannot be rendered: its original is missing or cannot be decoded. It is recorded rendered with no renditions, so
     * the asset shows its original and the next startup does not try it again; a later change of the head is rendered anew.
     */
    case Unrenderable(seq: Int)

  /** What a render made of the head, for its write */
  private[service] enum Made:
    /** The edited Preview and the Display image, as WebP bytes */
    case Images(preview: Array[Byte], display: Array[Byte])

    /** Nothing: the head is the Original */
    case Original

    /** Nothing: the head cannot be rendered, and why */
    case Failed(why: String)

/**
 * Renders an asset's Edit at its head into the edited Preview and the Display image (`CONTEXT.md`), on the `edit-render` queue.
 * `AssetEditService` calls [[enqueue]] once a change of an Edit's head has committed, in the request's context; the queue takes
 * the asset 1.5 s after the last such call ([[C.Edit.RENDER_DEBOUNCE_MS]]), so a run of changes is rendered once, at the head it
 * ends on. A render reads the head, renders outside any transaction, and stores the result only if the head is still what it read
 * ([[render]]); otherwise it queues the asset again. Each stored rendition carries a revision (`asset.edit_rev`) that is never
 * reused for the asset, so a content URL naming it never changes. What was pending when the server stopped is queued again at
 * startup ([[requeuePendingRenders]]).
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

  // Set by shutdown, after which no render is queued: the next startup queues what is left
  @volatile private var stopping = false

  // The renders under way, which shutdown waits for
  private val underWay = ConcurrentHashMap.newKeySet[Future[Unit]]()

  // By asset: the render waiting out its delay
  private val scheduled = ConcurrentHashMap[String, Scheduled]()

  private val queue = QueuedPipeline[(String, PipelineContext)](
    "edit-render",
    Flow[(String, PipelineContext)].mapAsyncUnordered(app.editRenderParallelism) {
      (assetId, context) =>
        val rendering = Future(withContext(context)(renderOrLog(assetId)))(using app.editRenderDispatcher)
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
   * Queues the asset's Edit, in the context repository, to be rendered at its head [[C.Edit.RENDER_DEBOUNCE_MS]] from now, which
   * puts off a render already waiting for the asset: each call starts the delay again
   */
  def enqueue(assetId: String): Unit =
    if stopping then
      logger.debug(s"Render of the Edit of asset [$assetId] not queued: the server is stopping")
      return
    val next = Scheduled(
      PipelineContext(RequestContext.getRepository, RequestContext.account.value, RequestContext.permissions.value))
    scheduled.compute(
      assetId,
      (_, previous) =>
        Option(previous).flatMap(_.timer).foreach(_.cancel())
        next.timer = Some(system.scheduler.scheduleOnce(debounce, () => fire(assetId, next))(using system.executionContext))
        next
    ): Unit
    logger.debug(s"Render of the Edit of asset [$assetId] queued in $debounce")

  // The delay is over: the render is offered unless a later call replaced it, whose own delay runs on
  private def fire(assetId: String, mine: Scheduled): Unit =
    if scheduled.remove(assetId, mine) && !stopping then offer(assetId, mine.context): Unit

  private def offer(assetId: String, context: PipelineContext): Future[Unit] =
    given ExecutionContext = system.executionContext
    queue
      .offer((assetId, context))
      .recover {
        // The queue logged why; the head stays unrendered, which the next startup queues again
        case NonFatal(_) => logger.warn(s"The Edit of asset [$assetId] is not rendered until the next start")
      }

  /**
   * Queues, in every repository, the Edits whose head is not rendered: the server stopped before their render, or it failed. They
   * are offered at once, one after another under the queue's backpressure, with no account: no request queued them.
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
   * Takes no more renders, drops the queued ones and waits, up to the shutdown timeout, for those under way: a head left
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

  private def renderOrLog(assetId: String): Unit =
    try render(assetId): Unit
    catch
      case NonFatal(ex) =>
        logger.error(s"The Edit of asset [$assetId] could not be rendered; it is tried again at the next start", ex)

  /**
   * Renders the asset's Edit at its head, in the context repository, in three steps: the head, its Edit and the asset are read in
   * a read transaction; the renditions are rendered outside any transaction; then, with the asset and its Edit locked, they are
   * stored only if the head is still the one read (its seq and its Edit, since a seq dropped by a change after an undo is used
   * again). An edited head takes the next revision (`last_rev + 1`), which the asset's `edit_rev` then names; the Original sets
   * `edit_rev` to 0. Either way the head is then the rendered step, and once that has committed, the renditions of the revisions
   * before go. A head that moved meanwhile throws the result away and queues the asset again.
   *
   * `beforeWrite` runs between the render and the write, for a test to move the head there.
   */
  def render(assetId: String, beforeWrite: () => Unit = () => ()): Outcome =
    val started = System.nanoTime()
    // The asset's Edit (None once the asset is gone, which takes its Edit with it), and its head and the head's Edit unless that
    // is rendered already
    val read = txManager.asReadOnly {
      dao.get(assetId).map {
        edit =>
          Option.unless(edit.renderedSeq.contains(edit.headSeq)) {
            val recipe = headRecipe(assetId, edit.headSeq).getOrElse(
              throw IllegalStateException(s"The Edit of asset [$assetId] has no head step [${edit.headSeq}]"))
            (edit.headSeq, recipe)
          }
      }
    }

    read match
      case None =>
        logger.debug(s"Asset [$assetId] is gone: its Edit is not rendered")
        Outcome.Gone
      case Some(None) =>
        logger.debug(s"The Edit of asset [$assetId] is rendered at its head already")
        Outcome.UpToDate
      case Some(Some((head, recipe))) =>
        val original = app.service.fileStore.assetFile(assetId)
        val made =
          if recipe.isIdentity then Made.Original
          else if !Files.isRegularFile(original) then Made.Failed("its original is missing")
          else
            try
              val (preview, display) = renditionsOf(original, recipe)
              Made.Images(preview, display)
            catch case NonFatal(ex) => Made.Failed(s"its original cannot be rendered: $ex")
        beforeWrite()
        val outcome = store(assetId, head, recipe, made)
        val ms = (System.nanoTime() - started) / 1000000
        outcome match
          case Outcome.Rendered(seq, rev) =>
            logger.info(s"Edit of asset [$assetId]: step [$seq] rendered as revision [$rev] in $ms ms")
          case Outcome.Original(seq) =>
            logger.info(s"Edit of asset [$assetId]: step [$seq] is the original, its renditions dropped in $ms ms")
          case Outcome.Unrenderable(seq) =>
            val why = made match
              case Made.Failed(why) => why
              case _ => ""
            logger.warn(s"Edit of asset [$assetId]: step [$seq] cannot be rendered, $why; the asset shows its original")
          case Outcome.Discarded =>
            logger.debug(s"Edit of asset [$assetId]: the head moved from step [$head] during its render, queued again")
            enqueue(assetId)
          case other => logger.debug(s"Edit of asset [$assetId]: $other")
        outcome

  /** The edited Preview and the Display image of the Edit, as WebP bytes, in that order */
  private def renditionsOf(original: java.nio.file.Path, recipe: EditRecipe): (Array[Byte], Array[Byte]) =
    val image: Mat = EditRenderer.render(original, recipe, Some(C.Edit.DISPLAY_LONG_EDGE))
    try
      (
        ImageUtil.makeImageThumbnail(image, 1, C.AssetView.PREVIEW_BOX_PIXELS),
        ImageUtil.encodeWebp(image, C.Edit.DISPLAY_WEBP_QUALITY))
    finally image.release()

  /**
   * The write of a render, under the locks of the asset and of its Edit (in that order, the order a purge's delete takes them),
   * if the head is still `head` holding `recipe`: the renditions stored under a revision past every one the asset has used and
   * every one on disk (a render whose transaction failed may have left one), which the asset's `edit_rev` then names; none for
   * the Original or a head that cannot be rendered, with `edit_rev` 0. The head is then the rendered step, and once that has
   * committed, the renditions of the revisions before go.
   */
  private def store(assetId: String, head: Int, recipe: EditRecipe, made: Made): Outcome =
    txManager.withTransaction {
      val edit = if assetDao.lockUnpurged(assetId) then dao.getForUpdate(assetId) else None
      edit match
        case None => Outcome.Gone
        case Some(edit) if edit.renderedSeq.contains(edit.headSeq) => Outcome.UpToDate
        case Some(edit) if edit.headSeq != head || !headRecipe(assetId, head).contains(recipe) => Outcome.Discarded
        case Some(edit) =>
          val next = Math.max(edit.lastRev, app.service.fileStore.highestEditRendition(assetId)) + 1
          val (rev, outcome) = made match
            case Made.Images(preview, display) =>
              app.service.fileStore.addEditRendition(assetId, next, C.DataStore.PREVIEW, preview)
              app.service.fileStore.addEditRendition(assetId, next, C.DataStore.DISPLAY, display)
              (next, Outcome.Rendered(head, next))
            case Made.Original => (0, Outcome.Original(head))
            case Made.Failed(_) => (0, Outcome.Unrenderable(head))
          dao.setRendered(assetId, Some(head), Math.max(edit.lastRev, rev))
          assetDao.updateById(assetId, Map(FieldConst.Asset.EDIT_REV -> rev))
          txManager.afterCommit(app.service.fileStore.purgeEditRenditions(assetId, below = if rev > 0 then rev else next))
          outcome
    }

  // The Edit at the step, as stored now: the identity at 0, and None for a step the Edit no longer has
  private def headRecipe(assetId: String, seq: Int): Option[EditRecipe] =
    if seq == 0 then Some(EditRecipe.Identity) else dao.step(assetId, seq).map(_.recipe)
