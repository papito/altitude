package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.SequenceMembers
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

trait AssetDao extends BaseDao[Asset]:
  def getUserMetadata(assetId: String): Option[UserMetadata]

  def setUserMetadata(assetId: String, metadata: UserMetadata): Unit

  def queryNotRecycled(q: Query): QueryResult[Asset]

  def queryRecycled(q: Query): QueryResult[Asset]

  def queryAll(q: Query): QueryResult[Asset]

  override def query(q: Query): QueryResult[Asset] =
    throw NotImplementedError("Can only directly query recycled and not recycled data sets")

  /*
   * The rows a library operation may change, read and locked in one select: the context repository's assets whose import is
   * complete and that are not marked for purging. An operation counts its stat changes from these rows and updates exactly them.
   */

  /** The live assets among the IDs */
  def getAssetsToRecycle(assetIds: Set[String]): List[Asset]

  /** The live and recycled assets among the IDs */
  def getAssetsToMove(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs */
  def getAssetsToRestore(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs, or the whole recycle bin */
  def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset]

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit

  /*
   * Sequences: the `sequence_id` the still images of one burst share while in triage. `SequenceService` reads and writes it
   * through these; the rule that pairs two images is `SequenceRule`.
   */

  /**
   * The context repository's completed, triaged still images other than the asset, in the trash or not but not purge-pending,
   * with a hash and a camera-clock capture time within `gapMs` of the asset's either way, read through the triage capture-day
   * index and locked until the transaction ends; nothing for an asset without a capture time
   */
  def getSequenceCandidates(asset: Asset, gapMs: Int): List[Asset]

  /** How many members each of the Sequences has and when its first member was captured, for the ones that have any */
  def countSequenceMembers(sequenceIds: Set[String]): List[SequenceMembers]

  /** Puts the assets still in triage in the Sequence; how many rows changed */
  def setSequence(assetIds: Set[String], sequenceId: String): Int

  /** Moves every member of the Sequences to the one; how many rows changed */
  def repointSequences(fromIds: Set[String], toId: String): Int
