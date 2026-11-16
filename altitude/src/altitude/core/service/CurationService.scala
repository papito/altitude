package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.CurationFrame
import altitude.core.models.CurationSet
import altitude.core.models.QueuedSequence
import altitude.core.models.RestoreResult
import altitude.core.transactions.TransactionManager
import altitude.core.util.CurationRule
import altitude.core.util.Query

/**
 * Reads the Curation queue and sets, sorted by `CurationRule` as they are read, so nothing the rule decides is stored; a set's
 * reads share one snapshot. With Curate own alone an account is shown only its own frames (`AssetRules.curatedOwner`), so a set
 * never holds a frame its outcome would be refused for.
 */
class CurationService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /** Sequences with enough live frames to compare, by first capture with undated ones last, each with its first frame as cover */
  def queue(): List[QueuedSequence] =
    txManager.asReadOnly {
      dao.getSequencesToCurate(AssetRules.curatedOwner)
    }

  def countQueue(): Int =
    txManager.asReadOnly {
      dao.countSequencesToCurate(AssetRules.curatedOwner)
    }

  /**
   * How many frames Curation would compare for the asset's Sequence: its whole live size, past the window a set reads. None for
   * an asset in the trash, which Curation does not show, or a Sequence with too few frames left.
   */
  def setSizeOf(asset: Asset): Option[Int] =
    txManager.asReadOnly {
      val owner = AssetRules.curatedOwner
      asset.sequenceId
        .filterNot(_ => asset.isRecycled)
        .map(dao.countSequenceFrames(_, owner))
        .filter(_ >= C.Curation.MIN_FRAMES)
    }

  /**
   * With Curate own alone, the account's own live frames per Sequence (a Sequence with none left out); None with Curate all, when
   * the grid's own counts hold
   */
  def curatableFrameCounts(sequenceIds: Set[String]): Option[Map[String, Int]] =
    AssetRules.curatedOwner.map {
      owner =>
        txManager.asReadOnly {
          dao.countOwnedSequenceFrames(sequenceIds, owner)
        }
    }

  /**
   * A Sequence's live frames, whatever a search shows of them, read as a window of the first `MAX_SEQUENCE_FRAMES` in capture
   * order. A set holding part of a Sequence keeps its Picks in it and stays in the queue ([[resolve]] decides by all its frames).
   */
  def sequenceSet(sequenceId: String): Option[CurationSet] =
    txManager.asReadOnly {
      val owner = AssetRules.curatedOwner
      val window = dao.getSequenceFrames(sequenceId, C.Curation.MAX_SEQUENCE_FRAMES, owner)
      // Only a full window can hold a frame back
      val heldBack =
        if window.size < C.Curation.MAX_SEQUENCE_FRAMES then 0 else dao.countSequenceFrames(sequenceId, owner) - window.size
      assess(window).map(_.copy(heldBack = heldBack))
    }

  /** The live still images among the IDs; the cap on a selection's size is the route's */
  def selectionSet(assetIds: Set[String]): Option[CurationSet] =
    if assetIds.size < C.Curation.MIN_FRAMES then return None

    txManager.asReadOnly {
      val owner = AssetRules.curatedOwner
      assess(liveAmong(assetIds).filter(asset => asset.isStillImage && owner.forall(_ == asset.ownerUserId)))
    }

  /**
   * Keeps the Picks and recycles the others, in one transaction. When the set holds every live frame of a Sequence the account is
   * shown, its Picks leave that Sequence so the set does not return to the queue; otherwise they stay, to be compared with the
   * other frames. Every ID is read before any Permission is checked, so an unknown ID is not found rather than refused. A Pick
   * that is not live is refused, as the set would otherwise be recycled with nothing kept. Answers each Pick that left its
   * Sequence, with that Sequence, for [[reopen]].
   */
  def resolve(keepIds: Set[String], recycleIds: Set[String]): Map[String, String] =
    requireOutcome(keepIds, recycleIds)

    val setIds = keepIds ++ recycleIds
    val (cleared, recycled) = txManager.withTransaction {
      dao.lockSequences()
      requireKnown(setIds)
      val live = liveAmong(setIds)
      val picks = live.filter(asset => keepIds.contains(asset.persistedId))
      if picks.size < keepIds.size then throw IllegalOperationException("A Pick is no live asset of the repository")
      AssetRules.requireCurate(picks.filter(_.isTriaged))

      // The members are locked, so a move or a recycle meanwhile is settled in what they say; a Pick moved after the read above
      // is no longer among them
      val owner = AssetRules.curatedOwner
      val members = dao.getLiveSequenceMembers(picks.flatMap(_.sequenceId).toSet).filter(m => owner.forall(_ == m.ownerUserId))
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
   * Undoes a [[resolve]] in one transaction: restores the recycled frames, as a restore does, and puts the Picks back in their
   * Sequences. A Pick moved into a folder since stays sorted, as `setSequence` writes only rows still in triage. Every triaged
   * Pick, in the trash or not, takes a Curate Permission, since `setSequence` writes each.
   */
  def reopen(keepIds: Set[String], recycleIds: Set[String], sequenceOf: Map[String, String]): RestoreResult =
    requireOutcome(keepIds, recycleIds)
    if !sequenceOf.keySet.subsetOf(keepIds) then throw IllegalOperationException("Only a Pick goes back into a Sequence")

    val (result, stamped) = txManager.withTransaction {
      dao.lockSequences()
      val picks = requireKnown(keepIds ++ recycleIds).filter(asset => keepIds.contains(asset.persistedId))
      AssetRules.requireCurate(picks.filter(_.isTriaged))
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

  /** In the trash or not; read before any Permission is checked, so an unknown ID is never answered as refused */
  private def requireKnown(assetIds: Set[String]): List[Asset] =
    val found = dao.queryAll(new Query().add(FieldConst.ID -> Query.IN(assetIds.toSet[Any]))).records
    val unknown = assetIds -- found.map(_.persistedId)
    if unknown.nonEmpty then
      throw NotFoundException(s"Cannot find ${unknown.size} of the assets in the repository, [${unknown.head}] among them")
    found

  /** The completed assets among the IDs, out of the trash */
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
