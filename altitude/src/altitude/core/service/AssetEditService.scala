package altitude.core.service

import java.nio.file.Files
import java.util.concurrent.Semaphore
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.BusyException
import altitude.core.EditConflictException
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.NotFoundException
import altitude.core.dao.AssetDao
import altitude.core.dao.AssetEditDao
import altitude.core.models.Asset
import altitude.core.models.AssetEdit
import altitude.core.models.EditRecipe
import altitude.core.models.EditState
import altitude.core.models.MimedPreviewData
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.EditRenderer

/**
 * One Edit per asset, replaced by each save; the original is never written. A save names the edit version it was built on
 * (`base`) and conflicts if that has moved, so a client's Undo is simply another save.
 */
object AssetEditService:

  /** The answer to a request for an edited image at a revision */
  enum EditedImage:
    /** The image stored now, under the asset's revision */
    case Current(image: MimedPreviewData)

    /** An earlier revision: the asset's edited images are under `rev` now */
    case Moved(rev: Int)

  /** Why the asset cannot be edited, None when it can */
  def whyNotEditable(asset: Asset): Option[String] =
    if !asset.isStillImage then Some(C.Msg.Err.EDIT_NOT_STILL)
    else if asset.isRecycled then Some(C.Msg.Err.EDIT_RECYCLED)
    else None

class AssetEditService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetEditDao = app.DAO.assetEdit
  private val assetDao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /** As many as renders but apart from them, so an auto-straighten never waits behind the render queue; tests take them all */
  private[core] val autoStraightenSlots = Semaphore(app.editRenderParallelism)

  /** The asset's Edit with its upright original's size; version 0 and the identity for an asset never edited */
  def get(assetId: String): EditState =
    val upright = uprightSizeIfKnown(app.service.asset.getById(assetId))
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId), upright = upright)
    }

  /** [[get]] without the upright size: what a client polls until its version is rendered */
  def status(assetId: String): EditState =
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId))
    }

  /**
   * The Edit for the asset's detail, None if never edited. The asset is re-read with the Edit in one snapshot: a render
   * committing since `asset` was read would otherwise pair the new rendered version with the old revision.
   */
  def editOf(asset: Asset): Option[EditState] =
    val (current, edit) = txManager.asReadOnly((assetDao.getById(asset.persistedId), dao.get(asset.persistedId)))
    edit.map(edit => stateOf(current, Some(edit), upright = uprightSizeIfKnown(current)))

  /**
   * Saves `recipe` on top of version `base`. A recipe equal to the current Edit writes nothing; Revert to original saves the
   * identity.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   * @throws EditConflictException
   *   when the version is not `base`, carrying the current state
   */
  def save(assetId: String, base: Int, recipe: EditRecipe, account: User): EditState =
    // Normalized as a client's is, so the Edit compared, stored and answered is the same one
    val normalized = EditRecipe.parse(recipe.toJson)
    val upright = uprightSizeOf(assetId)

    txManager.withTransaction {
      // The saver first, whom the Edit's row references, as an import locks its importer: an account write, which locks the
      // account and then rewrites its assets, waits for this save or is waited for
      app.service.user.lockActor(account.persistedId): Unit
      val (asset, edit) = lockAt(assetId, base, upright)

      if normalized == edit.fold(EditRecipe.Identity)(_.recipe) then
        logger.debug(s"Edit of asset [$assetId]: the save is the Edit at version [$base], nothing to save")
        stateOf(asset, edit, upright = Some(upright))
      else
        val editVersion = base + 1
        val (width, height) = normalized.editedSize(upright._1, upright._2)
        val isEdited = !normalized.isIdentity
        edit match
          case Some(_) => dao.save(assetId, editVersion, normalized, isEdited, width, height, account.persistedId)
          // No Edit row to lock yet: the asset lock every save takes first holds off a concurrent first save, which then finds
          // a later version and conflicts
          case None =>
            dao.insert(
              AssetEdit(assetId, normalized, editVersion, isEdited = isEdited, width = width, height = height),
              account.persistedId)
        txManager.afterCommit {
          logger.info(s"Edit of asset [$assetId]: edit version [$editVersion] saved by account [${account.persistedId}]")
          app.service.editRender.enqueue(assetId)
        }
        stateOf(asset, dao.get(assetId), upright = Some(upright))
    }

  /**
   * Only the current revision's edited image is stored, so an earlier `rev` (a page built before the latest render) is `Moved`
   * rather than answered with bytes it did not name.
   *
   * @throws NotFoundException
   *   for revision 0, one above the asset's (it may name other bytes later), or an asset with no edited images
   */
  def editedImage(assetId: String, rev: Int, kind: String): AssetEditService.EditedImage =
    val current = txManager.asReadOnly(assetDao.getById(assetId).editRev)
    if rev < 1 || rev > current then
      throw NotFoundException(s"Asset [$assetId] has no edited $kind at revision [$rev]; its revision is [$current]")
    if rev < current then
      logger.debug(s"Edited $kind of asset [$assetId] asked for at revision [$rev], moved to [$current]")
      AssetEditService.EditedImage.Moved(current)
    else AssetEditService.EditedImage.Current(app.service.fileStore.getEditedImage(assetId, kind))

  /**
   * The Straighten that levels the original's lines as `recipe` turns and mirrors it, if they agree on one. Nothing is stored.
   * Runs on the request's thread in one of [[autoStraightenSlots]]; with none free it is refused rather than queued.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing or no image reader decodes
   * @throws BusyException
   *   when every slot is taken
   */
  def autoStraighten(assetId: String, recipe: EditRecipe): Option[Double] =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    val original = app.service.fileStore.assetFile(assetId)
    if !Files.isRegularFile(original) then throw IllegalOperationException(C.Msg.Err.ORIGINAL_MISSING)
    if !autoStraightenSlots.tryAcquire() then
      logger.info(s"Auto-straighten of asset [$assetId] refused: every slot is taken")
      throw BusyException(C.Msg.Err.AUTO_STRAIGHTEN_BUSY)
    try
      val angle = EditRenderer.autoStraightenAngle(original, recipe)
      logger.debug(s"Edit of asset [$assetId]: auto-straighten answers [$angle]")
      angle
    catch
      case ex: ImageException =>
        logger.warn(s"Auto-straighten of asset [$assetId]: its original cannot be decoded: ${ex.getMessage}")
        throw IllegalOperationException(C.Msg.Err.ORIGINAL_UNREADABLE)
    finally autoStraightenSlots.release()

  /**
   * Read before a save's transaction opens, so reading the original's metadata holds no lock (on SQLite, the one write
   * connection). The save checks editability again under its lock.
   */
  private def uprightSizeOf(assetId: String): (Int, Int) =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    app.service.asset.uprightSize(asset)

  /** None for no still image or a missing original: a state's answer can do without it */
  private def uprightSizeIfKnown(asset: Asset): Option[(Int, Int)] =
    if !asset.isStillImage then None
    else
      try Some(app.service.asset.uprightSize(asset))
      catch
        case ex: IllegalOperationException =>
          logger.debug(s"No upright size for asset [${asset.persistedId}]: ${ex.getMessage}")
          None

  /**
   * Reads the asset after the lock so its revision matches the Edit's rendered version. The asset is locked before the Edit, the
   * order a render's write and an asset delete use, so none of them deadlock.
   */
  private def lockAt(assetId: String, base: Int, upright: (Int, Int)): (Asset, Option[AssetEdit]) =
    assetDao.lockUnpurged(assetId): Unit
    val edit = dao.getForUpdate(assetId)
    val asset = assetDao.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    if edit.fold(0)(_.editVersion) != base then
      logger.info(s"Edit of asset [$assetId]: a save built on version [$base] refused, the Edit has moved on since")
      throw EditConflictException(stateOf(asset, edit, upright = Some(upright)))
    (asset, edit)

  private def stateOf(asset: Asset, edit: Option[AssetEdit], upright: Option[(Int, Int)] = None): EditState =
    EditState(
      editVersion = edit.fold(0)(_.editVersion),
      recipe = edit.fold(EditRecipe.Identity)(_.recipe),
      renderedVersion = edit.flatMap(_.renderedVersion),
      rev = asset.editRev,
      edited = edit.exists(_.isEdited),
      width = edit.map(_.width),
      height = edit.map(_.height),
      upright = upright
    )
