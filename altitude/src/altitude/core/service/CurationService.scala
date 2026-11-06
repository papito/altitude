package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.CurationFrame
import altitude.core.models.CurationSet
import altitude.core.models.RestoreResult
import altitude.core.models.SequenceMembers
import altitude.core.transactions.TransactionManager
import altitude.core.util.CurationRule
import altitude.core.util.Query

/**
 * Reads the Curation sets: the queue of Sequences and the frames of one Sequence or of a selection, each with its Faces and its
 * memberships, sorted by `CurationRule` as they are read, so nothing the rule decides is stored. A set's reads share one
 * snapshot. A set's outcome is written by [[resolve]], and undone by [[reopen]].
 */
class CurationService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /** The context repository's Sequences with enough frames out of the trash to compare, the oldest first capture first */
  def queue(): List[SequenceMembers] =
    txManager.asReadOnly {
      dao.getSequencesToCurate()
    }

  /** How many Sequences [[queue]] holds */
  def countQueue(): Int =
    txManager.asReadOnly {
      dao.countSequencesToCurate()
    }

  /**
   * The set of a Sequence's frames still out of the trash, whatever a search shows of them; nothing with too few of them left. It
   * reads a window, the first `MAX_SEQUENCE_FRAMES` in capture order, and says how many it holds back: a set that holds part of a
   * Sequence, as a selection can, whose Picks stay in it and which stays in the queue ([[resolve]] decides by all its frames).
   */
  def sequenceSet(sequenceId: String): Option[CurationSet] =
    txManager.asReadOnly {
      val window = dao.getSequenceFrames(sequenceId, C.Curation.MAX_SEQUENCE_FRAMES)
      // Only a full window can hold a frame back
      val heldBack = if window.size < C.Curation.MAX_SEQUENCE_FRAMES then 0 else dao.countSequenceFrames(sequenceId) - window.size
      assess(window).map(_.copy(heldBack = heldBack))
    }

  /**
   * The set of the still images among the IDs, in the context repository and out of the trash; nothing with too few of them left.
   * The cap on a selection is the route's, as a page's size is `SearchResultsController`'s: what is refused never reaches a
   * service.
   */
  def selectionSet(assetIds: Set[String]): Option[CurationSet] =
    if assetIds.size < C.Curation.MIN_FRAMES then return None

    txManager.asReadOnly {
      assess(liveAmong(assetIds).filter(_.isStillImage))
    }

  /**
   * Keeps the Picks and recycles the others, in one transaction. Each of the Picks' Sequences is decided on its own: when the set
   * holds all its frames still out of the trash, its Picks leave it, so the set does not return to the queue however many Picks
   * it has; when it does not, they stay, to be compared with the other frames from the queue. The recycled frames keep their
   * Sequence, as any recycled frame does. Keep all is this with nothing to recycle. A Pick that is no live asset of the
   * repository is refused, as the set would otherwise be recycled with nothing kept. Answers with each Pick that left its
   * Sequence and that Sequence, for [[reopen]].
   */
  def resolve(keepIds: Set[String], recycleIds: Set[String]): Map[String, String] =
    requireOutcome(keepIds, recycleIds)

    val setIds = keepIds ++ recycleIds
    val (cleared, recycled) = txManager.withTransaction {
      dao.lockSequences()
      val live = liveAmong(setIds)
      val picks = live.filter(asset => keepIds.contains(asset.persistedId))
      if picks.size < keepIds.size then throw IllegalOperationException("A Pick is no live asset of the repository")

      // The members are locked, so a move or a recycle meanwhile is settled in what they say; a Pick moved after the read above is
      // no longer among them
      val members = dao.getLiveSequenceMembers(picks.flatMap(_.sequenceId).toSet)
      val cleared = members
        .groupBy(_.sequenceId.get)
        .toList
        .flatMap {
          (sequenceId, frames) =>
            val frameIds = frames.map(_.persistedId).toSet
            if frameIds.subsetOf(setIds) then frameIds.intersect(keepIds).toList.map(_ -> sequenceId) else Nil
        }
        .toMap

      app.service.library.recycleAssets(recycleIds)
      dao.clearSequence(cleared.keySet): Unit
      (cleared, live.size - picks.size)
    }

    logger.info(
      s"Resolved a Curation set: ${keepIds.size} kept, $recycled recycled, " +
        s"${cleared.size} out of Sequences ${cleared.values.toSet}")
    cleared

  /**
   * Undoes a [[resolve]], in one transaction: restores the recycled frames and puts the Picks back in the Sequences it answered
   * with. It reports as a restore does, and fails as one does: a frame whose content was imported again stays in the trash, and
   * an ID that is no asset of the repository changes nothing. A Pick moved into a folder since stays sorted, as `setSequence`
   * writes only the rows still in triage, and a sorted asset never carries a Sequence.
   */
  def reopen(keepIds: Set[String], recycleIds: Set[String], sequenceOf: Map[String, String]): RestoreResult =
    requireOutcome(keepIds, recycleIds)
    if !sequenceOf.keySet.subsetOf(keepIds) then throw IllegalOperationException("Only a Pick goes back into a Sequence")

    val (result, stamped) = txManager.withTransaction {
      dao.lockSequences()
      val result = app.service.library.restoreRecycledAssets(recycleIds)
      val stamped =
        sequenceOf.groupMap(_._2)(_._1).toList.map((sequenceId, assetIds) => dao.setSequence(assetIds.toSet, sequenceId)).sum
      (result, stamped)
    }

    logger.info(
      s"Reopened a Curation set: ${result.restored.size} restored, ${result.duplicates.size} left in the trash as duplicates, " +
        s"$stamped of ${sequenceOf.size} back in Sequences ${sequenceOf.values.toSet}")
    result

  private def requireOutcome(keepIds: Set[String], recycleIds: Set[String]): Unit =
    if keepIds.isEmpty then throw IllegalOperationException("A Curation set needs a Pick")
    if keepIds.intersect(recycleIds).nonEmpty then throw IllegalOperationException("A Pick cannot be recycled")

  /** The completed assets among the IDs, in the context repository and out of the trash */
  private def liveAmong(assetIds: Set[String]): List[Asset] =
    val query = new Query().add(FieldConst.ID -> Query.IN(assetIds.toSet[Any]), FieldConst.Asset.IS_PIPELINE_PROCESSED -> true)
    dao.queryNotRecycled(query).records

  private def assess(assets: List[Asset]): Option[CurationSet] =
    Option.when(assets.size >= C.Curation.MIN_FRAMES) {
      val assetIds = assets.map(_.persistedId).toSet
      val faces = app.service.person.getFacesOfAssets(assetIds).groupBy(_.assetId)
      val heldElsewhere = app.service.album.getAssetsInAnyAlbum(assetIds) ++ app.service.location.getAssetsInAnyLocation(assetIds)
      val histograms = dao.getHistograms(assetIds)
      val frames = assets.map {
        asset =>
          CurationFrame(
            asset,
            faces.getOrElse(asset.id, Nil),
            heldElsewhere.contains(asset.persistedId),
            histograms.get(asset.persistedId))
      }
      val set = CurationRule.assess(frames, app.settings.current)
      logger.debug(s"Read a Curation set of ${frames.size} frames: ${set.bundles.map(_.kind)}, ${set.contenders.size} Contenders")
      set
    }
