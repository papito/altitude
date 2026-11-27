package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AccountSnapshot
import altitude.core.models.Asset
import altitude.core.models.Histogram
import altitude.core.models.QueuedSequence
import altitude.core.models.SequenceMembers
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

object AssetDao:
  /** One live copy of a content per repository */
  val LIVE_CHECKSUM_INDEX: BaseDao.UniqueIndex = BaseDao.UniqueIndex("asset_01", "asset.repository_id, asset.checksum")

trait AssetDao extends BaseDao[Asset]:
  /** Empty when the asset has none */
  def getUserMetadata(assetId: String): UserMetadata

  /** How many rows changed: 0 for an asset of another repository, or none */
  def setUserMetadata(assetId: String, metadata: UserMetadata): Int

  def queryNotRecycled(q: Query): QueryResult[Asset]

  def queryRecycled(q: Query): QueryResult[Asset]

  def queryAll(q: Query): QueryResult[Asset]

  override def query(q: Query): QueryResult[Asset] =
    throw NotImplementedError("Can only directly query recycled and not recycled data sets")

  /*
   * The rows a library operation may change, read and locked in one select: completed assets not marked for purging. An
   * operation counts its stat changes from these rows and updates exactly them.
   */

  /** The live assets among the IDs */
  def getAssetsToRecycle(assetIds: Set[String]): List[Asset]

  /** The live and recycled assets among the IDs */
  def getAssetsToMove(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs */
  def getAssetsToRestore(assetIds: Set[String]): List[Asset]

  /** The recycled assets among the IDs, or the whole recycle bin */
  def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset]

  /** The IDs of the assets in any of the folders, recycled or not, not locked */
  def getIdsInFolders(folderIds: Set[String]): Set[String]

  /**
   * The assets marked for purging, not locked and without the bulky metadata: the purge queue reads an asset's ID alone, and a
   * Trash emptied of thousands of assets would otherwise be read whole
   */
  def getPurgePending: List[Asset]

  /**
   * Locks the asset's row unless it is marked for purging; whether it is there and unmarked. `FOR NO KEY UPDATE` on PostgreSQL: a
   * purge mark or a delete waits for it, but a referencing row (its Edit's) is written without waiting. An Edit write takes it
   * before the Edit's row, the order a delete of the asset takes them in.
   */
  def lockUnpurged(assetId: String): Boolean

  /**
   * Share-locks the assets among the IDs that are out of the trash, in ID order (`FOR KEY SHARE` on PostgreSQL), as an add to an
   * album or a Location does before it writes. A library operation's `FOR UPDATE` of one then waits for the add, which it then
   * sees; or the add waits for the operation, and once that commits a recycled asset is no longer among the rows.
   */
  def shareLockLive(assetIds: Set[String]): Unit

  /**
   * Locks the asset's row while it is marked for purging (`FOR UPDATE` on PostgreSQL); whether it is there. The purge's delete
   * takes it before the people of its Faces, as a library operation locks its assets before their people: an account write may
   * hold the row, rewriting the importer records or the owner of the account's assets, marked ones included.
   */
  def lockPurged(assetId: String): Boolean

  /** Merges the metadata into the asset's, dropping `deletedFields`; how many rows changed */
  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Int

  /**
   * Sets the completion flag and, in the same update, the measurements and Scene embeddings the model carries, each absent one as
   * NULL; how many rows changed. An embedding of the wrong dimensions is refused, as PostgreSQL's column would, so SQLite's BLOB
   * never holds one.
   */
  def markAsCompleted(asset: Asset): Int

  /** Asset ID to Histogram, for those that have one: the one read that selects the column */
  def getHistograms(assetIds: Set[String]): Map[String, Histogram]

  /* Sequences: the `sequence_id` the still images of one burst share while in triage (`SequenceRule` pairs them) */

  /**
   * Completed, triaged still images other than the asset, in the trash or not but not purge-pending, with a hash and a capture
   * time within `gapMs` of the asset's; nothing for an asset without a capture time. No row locks: the matches are locked through
   * [[lockSequenceFrames]] before they are stamped.
   */
  def getSequenceCandidates(asset: Asset, gapMs: Int): List[Asset]

  /**
   * As [[getSequenceCandidates]] but with no capture time, at the asset's size: the newest `limit` imports, streamed through
   * `keep` so only the matches' embeddings are held. Returns the kept ones and how many rows were read, so a caller can tell when
   * the limit was reached. No row locks: the matches are locked through [[lockSequenceFrames]] before they are stamped.
   */
  def getUndatedSequenceMatches(asset: Asset, limit: Int)(keep: Asset => Boolean): BaseDao.Collected[Asset]

  /**
   * The completed, triaged, not purge-pending assets among the IDs or in the Sequences, in the trash or not, with their current
   * Sequence, locked in one pass in ID order, as a library operation locks the assets it changes. Re-reads a Sequence
   * assignment's unlocked matches, with the members a merge of their Sequences repoints, before they are stamped, so one a move
   * sorted meanwhile is left out.
   */
  def lockSequenceFrames(assetIds: Set[String], sequenceIds: Set[String]): List[Asset]

  /**
   * The completed, not purge-pending assets among the IDs, in the trash or not, locked in ID order: a Curation reopen locks the
   * Picks it stamps with the frames it restores, in one pass, as a move locks them
   */
  def lockCompleted(assetIds: Set[String]): List[Asset]

  /** Each Sequence's member count and first capture time, for those with members */
  def countSequenceMembers(sequenceIds: Set[String]): List[SequenceMembers]

  /**
   * The Curation queue: Sequences with at least `Const.Curation.MIN_FRAMES` completed frames out of the trash, oldest first
   * capture first, undated ones last on both engines, then by ID, each with its first frame as cover. With an `owner`, only that
   * account's frames count and cover, for an account that curates its own assets alone.
   */
  def getSequencesToCurate(owner: Option[String] = None): List[QueuedSequence]

  /** How many Sequences [[getSequencesToCurate]] holds */
  def countSequencesToCurate(owner: Option[String] = None): Int

  /**
   * A Sequence's completed frames out of the trash, in capture order as `CurationRule` orders them (undated last, then by import
   * time and ID); with an `owner`, that account's alone
   */
  def getSequenceFrames(sequenceId: String, limit: Int, owner: Option[String] = None): List[Asset]

  /** A Sequence's completed frames out of the trash; with an `owner`, that account's alone */
  def countSequenceFrames(sequenceId: String, owner: Option[String] = None): Int

  /** [[countSequenceFrames]] of several Sequences in one statement, by Sequence ID; a Sequence with none is absent */
  def countOwnedSequenceFrames(sequenceIds: Set[String], owner: String): Map[String, Int]

  /**
   * Makes the repository's Sequence writers take turns. Row locks cannot: a resolve's locking read misses a frame completing
   * until it commits, and each writer picks the rows it locks by a read made before its locks (an assignment's matches, a
   * resolve's Picks), which another writer's changes would make stale. A library operation does not take it; each writer locks
   * its rows in one pass in ID order, as those operations do, so neither waits on the other in a cycle.
   */
  def lockSequences(): Unit

  /**
   * The completed frames out of the trash of the Sequences and among `assetIds`, locked in one pass in ID order: a resolve's
   * Sequence members with the frames it recycles
   */
  def lockLiveFrames(sequenceIds: Set[String], assetIds: Set[String] = Set.empty): List[Asset]

  /** How many rows changed */
  def clearSequence(assetIds: Set[String]): Int

  /** Only assets still in triage; how many rows changed */
  def setSequence(assetIds: Set[String], sequenceId: String): Int

  /** Moves every member of the Sequences, but one marked for purging, to the one; how many rows changed */
  def repointSequences(fromIds: Set[String], toId: String): Int

  /*
   * An account's assets in every Library: the only statements on a Library's table with no `repository_id`, each in the
   * transaction of the account write it serves. Nothing else may reach `asset` without its Library. The two writes lock their
   * rows in ID order before writing them, as a library operation does.
   */

  /** Recycled ones included: what a delete of the account moves */
  def countOwnedBy(accountId: String): Int

  /** For a delete of the account; how many rows changed */
  def reassignOwner(fromAccountId: String, toAccountId: String): Int

  /** Rewrites `original_owner` wherever it names the account, whoever owns the asset now; how many rows changed */
  def rewriteImporterRecords(snapshot: AccountSnapshot): Int
