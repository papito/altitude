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
import altitude.core.models.EditRecipe
import altitude.core.models.EditState
import altitude.core.models.MimedPreviewData
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.EditRenderer

/**
 * An asset's Edit (`CONTEXT.md`): the one Edit the server keeps for the asset, replaced by each save. The original is never
 * written. Every save is built on the edit version the client saw (`base`) and is a conflict carrying the current state when the
 * edit version has moved since; it moves the edit version on by one, so a client's earlier Edit (its Undo) is saved as any other.
 * Only a still image outside the trash is edited ([[AssetEditService.whyNotEditable]]). A save has the Edit rendered once it
 * commits (`EditRenderService`), and is logged then.
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

  /**
   * The asset's Edit, with the size of its upright original; version 0 and the identity for an asset never edited, and
   * `NotFoundException` for no such asset
   */
  def get(assetId: String): EditState =
    val upright = uprightSizeIfKnown(app.service.asset.getById(assetId))
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId), upright = upright)
    }

  /**
   * The asset's Edit read in one snapshot, without the upright size: version 0 for an asset never edited, and `NotFoundException`
   * for no such asset. What a client polls until the version is rendered.
   */
  def status(assetId: String): EditState =
    txManager.asReadOnly {
      stateOf(assetDao.getById(assetId), dao.get(assetId))
    }

  /**
   * The Edit of an asset read in the context repository, with the size of its upright original, or None for an asset never
   * edited: what the asset's detail carries
   */
  def editOf(asset: Asset): Option[EditState] =
    txManager.asReadOnly(dao.get(asset.persistedId)).map {
      edit =>
        val upright = uprightSizeIfKnown(asset)
        txManager.asReadOnly(stateOf(asset, Some(edit), upright = upright))
    }

  /**
   * Saves `recipe` as the asset's Edit, built on the version `base` the client saw, and answers the new state: the version one
   * on, whether the Edit is edited and its edited size. A recipe equal to the current Edit is no change: the current state,
   * nothing written. Revert to original is a save of the identity, which the client's Undo takes back as another save.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   * @throws EditConflictException
   *   when the version is not `base`, with the current state
   */
  def save(assetId: String, base: Int, recipe: EditRecipe, account: User): EditState =
    // Normalized, as a client's is, so the stored Edit and the one compared with the current one are the one answered
    val normalized = EditRecipe.parse(recipe.toJson)
    val upright = uprightSizeOf(assetId)

    txManager.withTransaction {
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
          // An asset never edited has no Edit row to lock; the asset's lock, which every save takes first, keeps another first
          // save out until this one ends, and that one then finds the row at a later edit version: a conflict
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
   * The asset's edited image of `kind` (`Const.DataStore.PREVIEW` or `DISPLAY`) asked for under the revision `rev`: the one
   * stored, when `rev` is from 1 to the asset's revision (`asset.edit_rev`). The asset has one edited image of each kind,
   * replaced by each render under the next revision, so a revision below the current one, which no page builds any more, is
   * answered the current image; harmless, since a page that held it showed an Edit as old or older.
   *
   * @throws NotFoundException
   *   for a revision above the asset's, which may name other bytes later, for 0, for an asset with no edited images (its Edit is
   *   the identity, or cannot be rendered), and for an asset that is not the Library's
   */
  def editedImage(assetId: String, rev: Int, kind: String): MimedPreviewData =
    val current = txManager.asReadOnly(assetDao.getById(assetId).editRev)
    if rev < 1 || rev > current then
      throw NotFoundException(s"Asset [$assetId] has no edited $kind at revision [$rev]; its revision is [$current]")
    if rev < current then logger.debug(s"Edited $kind of asset [$assetId] asked for at revision [$rev], served at [$current]")
    app.service.fileStore.getEditedImage(assetId, kind)

  /**
   * Auto-straighten: the Straighten that levels the lines of the asset's original as `recipe` turns and mirrors it, in tenths of
   * a degree, or nothing when no level or plumb lines agree on one (`EditRenderer.autoStraightenAngle`). Nothing is stored. The
   * original is decoded on the render dispatcher, which bounds how many are decoded at once, renders and auto-straightens
   * together.
   *
   * @throws IllegalOperationException
   *   for an asset that is not a still image, is in the trash, or whose original is missing
   */
  def autoStraighten(assetId: String, recipe: EditRecipe): Option[Double] =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    val original = app.service.fileStore.assetFile(assetId)
    if !Files.isRegularFile(original) then throw IllegalOperationException(C.Msg.Err.ORIGINAL_MISSING)
    val reading = Future(EditRenderer.autoStraightenAngle(original, recipe))(using app.editRenderDispatcher)
    val angle = Await.result(reading, AssetEditService.AUTO_STRAIGHTEN_TIMEOUT)
    logger.debug(s"Edit of asset [$assetId]: auto-straighten answers [$angle]")
    angle

  /**
   * The size of the asset's upright original, read before a save's transaction opens, so that reading the original's metadata
   * holds no lock (on SQLite, the one write connection). The asset must be editable now; the save checks it again under its lock.
   */
  private def uprightSizeOf(assetId: String): (Int, Int) =
    val asset = app.service.asset.getById(assetId)
    AssetEditService.whyNotEditable(asset).foreach(why => throw IllegalOperationException(why))
    app.service.asset.uprightSize(asset)

  /**
   * The size of the asset's upright original for a state's answer, read outside any transaction as [[uprightSizeOf]] reads it;
   * None for an asset that is no still image or whose original is missing, which the answer can do without
   */
  private def uprightSizeIfKnown(asset: Asset): Option[(Int, Int)] =
    if !asset.isStillImage then None
    else
      try Some(app.service.asset.uprightSize(asset))
      catch
        case ex: IllegalOperationException =>
          logger.debug(s"No upright size for asset [${asset.persistedId}]: ${ex.getMessage}")
          None

  /**
   * The asset's Edit locked for the transaction (None for an asset never edited, which locks nothing) and the asset, read after
   * the lock so that its revision goes with the Edit's rendered version, when the asset is editable and the Edit is at `base`; a
   * conflict carrying the current state, with the `upright` size, when it is not. The asset is locked first, as a render's write
   * and a delete of the asset lock the two, so no two of them wait for each other.
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
