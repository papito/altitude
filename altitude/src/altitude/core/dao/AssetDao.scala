package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Histogram
import altitude.core.models.QueuedSequence
import altitude.core.models.SequenceMembers
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

trait AssetDao extends BaseDao[Asset]:
  /** The asset's user metadata; an asset of another repository, or none, is a `NotFoundException` */
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

  /**
   * Sets the completion flag of the context repository's asset and, in the same update, stores the Image quality, the perceptual
   * hash, the Histogram and the Scene embeddings the model carries, each absent one as NULL (a Video, an animated image, a still
   * image the pipeline did not measure or a model could not embed); how many rows changed. An embedding not of its model's
   * dimensions is refused (`IllegalArgumentException`), as PostgreSQL's column would refuse it, so SQLite's BLOB never holds one.
   */
  def markAsCompleted(asset: Asset): Int

  /**
   * The Histograms of the context repository's assets among the IDs, by asset ID, for those that have one: the one read that
   * selects the column
   */
  def getHistograms(assetIds: Set[String]): Map[String, Histogram]

  /*
   * Sequences: the `sequence_id` the still images of one burst share while in triage. `SequenceService` and `CurationService`
   * read and write it through these; the rule that pairs two images is `SequenceRule`.
   */

  /**
   * The context repository's completed, triaged still images other than the asset, in the trash or not but not purge-pending,
   * with a hash and a camera-clock capture time within `gapMs` of the asset's either way, read through the triage capture-day
   * index and locked until the transaction ends; nothing for an asset without a capture time
   */
  def getSequenceCandidates(asset: Asset, gapMs: Int): List[Asset]

  /**
   * The context repository's completed, triaged still images other than the asset, in the trash or not but not purge-pending,
   * with a hash and no capture time, at the asset's width and height, the newest `limit` imports first, walked backwards through
   * `asset_triage_undated` and judged one by one: each is kept while `keep` holds and dropped otherwise, so at most the matches'
   * embeddings are alive at once. The kept ones in that order, with how many rows were read, so a caller can tell when the limit
   * was reached. No row locks: the matches are re-read through [[lockTriaged]] before they are stamped.
   */
  def getUndatedSequenceMatches(asset: Asset, limit: Int)(keep: Asset => Boolean): BaseDao.Collected[Asset]

  /**
   * The context repository's completed, triaged assets among the IDs that are not purge-pending, in the trash or not, with the
   * Sequence each carries now, locked in ID order until the transaction ends (`FOR UPDATE` on PostgreSQL): how the undated
   * matches, read without row locks, are re-read before they are stamped, so one a move sorted meanwhile is left out
   */
  def lockTriaged(assetIds: Set[String]): List[Asset]

  /** How many members each of the Sequences has and when its first member was captured, for the ones that have any */
  def countSequenceMembers(sequenceIds: Set[String]): List[SequenceMembers]

  /**
   * The queue of Curation: the context repository's Sequences with at least `Const.Curation.MIN_FRAMES` frames completed and out
   * of the trash, counted over those frames alone, the oldest first capture first, a Sequence with none (an undated one) last on
   * both engines, and then by ID, each with its cover, the first of those frames in capture order. Read through `asset_sequence`.
   */
  def getSequencesToCurate(): List[QueuedSequence]

  /** How many Sequences [[getSequencesToCurate]] holds, read from the database */
  def countSequencesToCurate(): Int

  /**
   * The first `limit` of a Sequence's frames in the context repository, completed and out of the trash, in capture order (a frame
   * with no capture time last, then by import time and ID, as `CurationRule` orders them), through `asset_sequence`
   */
  def getSequenceFrames(sequenceId: String, limit: Int): List[Asset]

  /** How many frames of a Sequence are in the context repository, completed and out of the trash, through `asset_sequence` */
  def countSequenceFrames(sequenceId: String): Int

  /**
   * Makes the context repository's writers of Sequences (`SequenceService.assign`, `CurationService.resolve` and `reopen`) take
   * turns, until the transaction ends. Row locks cannot do it: a frame completing is in no row a resolve's locking read finds
   * until it commits, however long that statement waits on another row, and neither writer locks all its rows in one ordered pass
   * (a merge repoints members outside its candidates, and a selection recycles frames outside its Picks' Sequences), so the two
   * could deadlock.
   */
  def lockSequences(): Unit

  /**
   * The Sequences' frames in the context repository, completed and out of the trash, through `asset_sequence`, in ID order and
   * locked until the transaction ends
   */
  def getLiveSequenceMembers(sequenceIds: Set[String]): List[Asset]

  /** Takes the assets out of whatever Sequence they are in; how many rows changed */
  def clearSequence(assetIds: Set[String]): Int

  /** Puts the assets still in triage in the Sequence; how many rows changed */
  def setSequence(assetIds: Set[String], sequenceId: String): Int

  /** Moves every member of the Sequences to the one; how many rows changed */
  def repointSequences(fromIds: Set[String], toId: String): Int
