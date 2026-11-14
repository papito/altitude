package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AssetEdit
import altitude.core.models.AssetEditStep
import altitude.core.models.EditState

/** An asset's Edit and its steps, in the context repository (`asset_edit`, `asset_edit_step`) */
trait AssetEditDao extends BaseDao[AssetEdit]:

  /** The asset's Edit, None when it was never edited */
  def get(assetId: String): Option[AssetEdit]

  /**
   * The asset's Edit, locked for the transaction (`FOR UPDATE` on PostgreSQL), so two writers of one asset's Edit take turns;
   * None when it was never edited, which locks nothing
   */
  def getForUpdate(assetId: String): Option[AssetEdit]

  /**
   * The asset's Edit at the Original (head 0), unless one exists: whether this inserted it. On PostgreSQL an insert racing
   * another transaction's waits for it to end and then inserts nothing.
   */
  def create(assetId: String): Boolean

  /** The asset's steps as the History lists them, newest first, each with the name of the account that made it; no Original */
  def steps(assetId: String): List[EditState.Step]

  def step(assetId: String, seq: Int): Option[AssetEditStep]

  def insertStep(step: AssetEditStep): Unit

  /** Deletes the steps after `seq`, which a change made at `seq` replaces; how many */
  def deleteStepsAfter(assetId: String, seq: Int): Int

  /** Deletes all but the newest `keep` steps; how many */
  def deleteOldestBeyond(assetId: String, keep: Int): Int

  /** Moves the head to `seq`, with whether it is edited and its edited size, as changed by the account now */
  def setHead(assetId: String, seq: Int, isEdited: Boolean, width: Int, height: Int, accountId: String): Unit

  /**
   * Records the step whose renditions are stored (None: no step's, the renditions stored being of none of the steps there are)
   * and the highest rendition revision the asset has used
   */
  def setRendered(assetId: String, seq: Option[Int], lastRev: Int): Unit

  /** The assets whose Edit's head is not rendered, in the context repository: what the startup queues to render */
  def pendingRenders(): List[String]
