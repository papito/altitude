package altitude.core.service

import java.nio.file.Files
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.EditConflictException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.dao.AssetDao
import altitude.core.dao.AssetEditDao
import altitude.core.models.Asset
import altitude.core.models.AssetEdit
import altitude.core.models.AssetEditStep
import altitude.core.models.EditRecipe
import altitude.core.models.EditState
import altitude.core.models.MimedPreviewData
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.EditRenderer

/**
 * An asset's Edit and its History (`CONTEXT.md`): every saved change is a step, and Undo, Redo and a jump from the History move
 * the head. Step 0 is the Original, which changes nothing and has no row; a change made after an undo drops the steps after the
 * head, and only the newest `Const.Edit.MAX_STEPS` are kept. Every write is built on the head the client saw (`base`) and is a
 * conflict carrying the current state when the head has moved since. Only a still image outside the trash is edited
 * ([[AssetEditService.whyNotEditable]]). A write that moves the head has the Edit rendered at its new head once it commits
 * (`EditRenderService`), and is logged then.
 */
object AssetEditService:

  // The longest auto-straighten waits for its turn on the render dispatcher and its read of the original
  private val AUTO_STRAIGHTEN_TIMEOUT = 2.minutes

  /** Why an Edit may not change the asset, None when it may: it is a still image outside the trash */
  def whyNotEditable(asset: Asset): Option[String] =
    if !asset.isStillImage then Some(C.Msg.Err.EDIT_NOT_STILL)
    else if asset.isRecycled then Some(C.Msg.Err.EDIT_RECYCLED)
    else None

class AssetEditService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetEditDao = app.DAO.assetEdit
  private val assetDao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /** The asset's Edit and its History; the Original's for an asset never edited, and `NotFoundException` for no such asset */
  def get(assetId: String): EditState =
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId))
    }

  /**
   * The asset's Edit at its head, without its History, read in one snapshot: the Original's for an asset never edited, and
   * `NotFoundException` for no such asset. What a client polls until the head is rendered.
   */
  def status(assetId: String): EditState =
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId), withSteps = false)
    }

  /**
   * The Edit of an asset read in the context repository, at its head and without its History, or None for an asset never edited:
   * what the asset's detail carries
   */
  def headOf(asset: Asset): Option[EditState] =
    txManager.asReadOnly {
      dao.get(asset.persistedId).map(edit => stateOf(asset, Some(edit), withSteps = false))
    }

  /**
   * Saves a change of the asset's Edit as a step after `base`, the head the client built it on, and moves the head to it. A
   * recipe equal to the head's is no change: the current state, nothing written. Otherwise the steps after the head are dropped,
   * the step is added with the label of its change, the oldest steps past the cap are dropped, and the head moves to it, with
   * whether it is edited and its edited size. Revert to original is such a step of the identity, so it can be undone.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   * @throws EditConflictException
   *   when the head is not `base`, with the current state
   */
  def addStep(assetId: String, base: Int, recipe: EditRecipe, account: User): EditState =
    // Normalized, as a client's is, so the stored Edit and the one compared with the head are the one answered
    val normalized = EditRecipe.parse(recipe.toJson)
    val upright = uprightSizeOf(assetId)

    txManager.withTransaction {
      val (asset, edit) = lockAt(assetId, base)
      val head = edit.fold(0)(_.headSeq)
      val headRecipe =
        recipeAt(assetId, head).getOrElse(throw IllegalStateException(s"Asset [$assetId] has no head step [$head]"))

      if normalized == headRecipe then
        logger.debug(s"Edit of asset [$assetId]: the step is the head [$head], nothing to save")
        stateOf(asset, edit)
      else
        // An asset never edited has no row to lock: inserting it holds it. When another transaction inserted it meanwhile (on
        // PostgreSQL the insert waits for that one to end), that row is locked and its head checked again
        val locked = if edit.isEmpty && !dao.create(assetId) then lockAt(assetId, base)._1 else asset

        val seq = head + 1
        val label = EditRecipe.describeChange(headRecipe, normalized)
        val after = dao.deleteStepsAfter(assetId, head)
        // A rendered step among those dropped is no step of the Edit any more, and its seq is about to be used again
        edit.foreach(e => if e.renderedSeq.exists(_ > head) then dao.setRendered(assetId, None, e.lastRev))
        dao.insertStep(AssetEditStep(assetId, seq, normalized, label, Some(account.persistedId)))
        val dropped = dao.deleteOldestBeyond(assetId, C.Edit.MAX_STEPS)
        moveTo(assetId, seq, normalized, upright, account)
        txManager.afterCommit {
          logger.info(
            s"Edit of asset [$assetId]: step [$seq] [$label] by account [${account.persistedId}]" +
              s"${if after > 0 then s", dropping the $after after the head" else ""}" +
              s"${if dropped > 0 then s", dropping the oldest $dropped" else ""}")
        }
        stateOf(locked, dao.get(assetId))
    }

  /**
   * Moves the head of the asset's Edit from `base` to the step `seq`, for Undo, Redo and a jump from the History: step 0 is the
   * Original. A move to the head itself is no change.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   * @throws EditConflictException
   *   when the head is not `base`, with the current state
   * @throws NotFoundException
   *   for a step the Edit does not have, never had or has dropped
   */
  def moveHead(assetId: String, base: Int, seq: Int, account: User): EditState =
    val upright = uprightSizeOf(assetId)

    txManager.withTransaction {
      val (asset, edit) = lockAt(assetId, base)

      if seq == base then stateOf(asset, edit)
      else
        val recipe =
          recipeAt(assetId, seq).getOrElse(throw NotFoundException(C.Msg.Err.EDIT_STEP_NOT_FOUND.format(seq)))
        moveTo(assetId, seq, recipe, upright, account)
        txManager.afterCommit {
          logger.info(s"Edit of asset [$assetId]: head moved from [$base] to [$seq] by account [${account.persistedId}]")
        }
        stateOf(asset, dao.get(assetId))
    }

  /**
   * The asset's edited rendition of `kind` (`Const.DataStore.PREVIEW` or `DISPLAY`) at the revision `rev`, read from its file
   * with no database read. Once a later revision has replaced it, the asset's current one, or None when the asset has none (its
   * Edit is at the Original, or cannot be rendered). A revision is never used twice for an asset, so the answer under `rev` stays
   * good to cache.
   *
   * @throws NotFoundException
   *   for a revision the asset has not used yet, which may name other bytes later, and for an asset that is not the Library's
   */
  def rendition(assetId: String, rev: Int, kind: String): Option[MimedPreviewData] =
    try Some(app.service.fileStore.getEditRendition(assetId, rev, kind))
    catch
      case _: NotFoundException =>
        val (lastRev, current) = txManager.asReadOnly {
          (dao.get(assetId).fold(0)(_.lastRev), assetDao.getById(assetId).editRev)
        }
        if rev > lastRev then throw NotFoundException(s"Asset [$assetId] has used no revision [$rev] yet")
        logger.debug(s"Rendition [$kind] of asset [$assetId] at revision [$rev] is gone; the current is [$current]")
        Option.when(current > 0)(app.service.fileStore.getEditRendition(assetId, current, kind))

  /**
   * Auto-straighten: the Straighten that levels the lines of the asset's original as `recipe` turns and mirrors it, in tenths of
   * a degree, 0 when it finds too few lines (`EditRenderer.autoStraightenAngle`). Nothing is stored. The original is decoded on
   * the render dispatcher, which bounds how many are decoded at once, renders and auto-straightens together.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   */
  def autoStraighten(assetId: String, recipe: EditRecipe): Double =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    val original = app.service.fileStore.assetFile(assetId)
    if !Files.isRegularFile(original) then throw IllegalOperationException(C.Msg.Err.ORIGINAL_MISSING)
    val reading = Future(EditRenderer.autoStraightenAngle(original, recipe))(using app.editRenderDispatcher)
    val angle = Await.result(reading, AssetEditService.AUTO_STRAIGHTEN_TIMEOUT)
    logger.debug(s"Edit of asset [$assetId]: auto-straighten answers [$angle]")
    angle

  /**
   * The size of the asset's upright original, read before a write's transaction opens, so that reading the original's metadata
   * holds no lock (on SQLite, the one write connection). The asset must be editable now; the write checks it again under its
   * lock.
   */
  private def uprightSizeOf(assetId: String): (Int, Int) =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    app.service.asset.uprightSize(asset)

  /**
   * The asset's Edit locked for the transaction (None for an asset never edited, which locks nothing) and the asset, read after
   * the lock so that its rendition revision goes with the Edit's rendered step, when the asset is editable and the head is
   * `base`; a conflict carrying the current state when it is not. The asset is locked first, as a render's write and a delete of
   * the asset lock the two, so no two of them wait for each other.
   */
  private def lockAt(assetId: String, base: Int): (Asset, Option[AssetEdit]) =
    assetDao.lockUnpurged(assetId): Unit
    val edit = dao.getForUpdate(assetId)
    val asset = assetDao.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    if edit.fold(0)(_.headSeq) != base then
      logger.info(s"Edit of asset [$assetId]: a change built on head [$base] refused, the head has moved since")
      throw EditConflictException(stateOf(asset, edit))
    (asset, edit)

  /** The Edit at the step `seq`: the identity at 0, None for a step the Edit does not have */
  private def recipeAt(assetId: String, seq: Int): Option[EditRecipe] =
    if seq == 0 then Some(EditRecipe.Identity) else dao.step(assetId, seq).map(_.recipe)

  /**
   * Moves the head to the step `seq` holding `recipe`, its edited size measured in the `upright` original, and has the Edit
   * rendered once the transaction commits
   */
  private def moveTo(assetId: String, seq: Int, recipe: EditRecipe, upright: (Int, Int), account: User): Unit =
    val (width, height) = recipe.editedSize(upright._1, upright._2)
    dao.setHead(assetId, seq, !recipe.isIdentity, width, height, account.persistedId)
    txManager.afterCommit(app.service.editRender.enqueue(assetId))

  private def stateOf(asset: Asset, edit: Option[AssetEdit], withSteps: Boolean = true): EditState =
    val assetId = asset.persistedId
    val head = edit.fold(0)(_.headSeq)
    val headStep = Option.when(head > 0)(dao.step(assetId, head)).flatten
    val original = EditState.Step(0, EditState.ORIGINAL, None, None, None)
    EditState(
      head = head,
      recipe = headStep.fold(EditRecipe.Identity)(_.recipe),
      label = headStep.fold(EditState.ORIGINAL)(_.label),
      rendered = edit.flatMap(_.renderedSeq),
      rev = asset.editRev,
      edited = edit.exists(_.isEdited),
      width = edit.flatMap(_.width),
      height = edit.flatMap(_.height),
      steps = if withSteps then dao.steps(assetId) :+ original else Nil
    )
