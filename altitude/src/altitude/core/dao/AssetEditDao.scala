package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AssetEdit
import altitude.core.models.EditRecipe

/** Assets' Edits, in the context repository */
trait AssetEditDao extends BaseDao[AssetEdit]:

  /** None when the asset was never edited */
  def get(assetId: String): Option[AssetEdit]

  /** The Edit locked for the transaction (`FOR UPDATE` on PostgreSQL), so its writers take turns; None locks nothing */
  def getForUpdate(assetId: String): Option[AssetEdit]

  /**
   * The asset's first Edit. The caller holds the asset's lock (`AssetDao.lockUnpurged`), which every save takes first, so no
   * other first save inserts it meanwhile.
   */
  def insert(edit: AssetEdit, accountId: String): Unit

  def save(
      assetId: String,
      editVersion: Int,
      recipe: EditRecipe,
      isEdited: Boolean,
      width: Int,
      height: Int,
      accountId: String): Unit

  /** Records the edit version whose edited images are stored, and the highest image revision the asset has used */
  def setRendered(assetId: String, editVersion: Int, lastRev: Int): Unit

  /** The assets whose Edit's edit version is not rendered */
  def pendingRenders(): List[String]
