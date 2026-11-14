package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AssetEdit
import altitude.core.models.EditRecipe

/** An asset's Edit, in the context repository (`asset_edit`) */
trait AssetEditDao extends BaseDao[AssetEdit]:

  /** The asset's Edit, None when it was never edited */
  def get(assetId: String): Option[AssetEdit]

  /**
   * The asset's Edit, locked for the transaction (`FOR UPDATE` on PostgreSQL), so two writers of one asset's Edit take turns;
   * None when it was never edited, which locks nothing
   */
  def getForUpdate(assetId: String): Option[AssetEdit]

  /**
   * Inserts the asset's first Edit, as saved by the account now. The caller holds the asset's lock (`AssetDao.lockUnpurged`),
   * which every save takes first, so no other first save inserts it meanwhile.
   */
  def insert(edit: AssetEdit, accountId: String): Unit

  /**
   * Replaces the asset's Edit with `recipe` at `editVersion`, with whether it is edited and its edited size, as saved by the
   * account now
   */
  def save(
      assetId: String,
      editVersion: Int,
      recipe: EditRecipe,
      isEdited: Boolean,
      width: Int,
      height: Int,
      accountId: String): Unit

  /** Records the edit version whose edited images are stored and the highest revision of edited images the asset has used */
  def setRendered(assetId: String, editVersion: Int, lastRev: Int): Unit

  /** The assets whose Edit's edit version is not rendered, in the context repository: what the startup queues to render */
  def pendingRenders(): List[String]
