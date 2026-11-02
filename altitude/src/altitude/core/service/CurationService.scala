package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.CurationFrame
import altitude.core.models.CurationSet
import altitude.core.models.SequenceMembers
import altitude.core.transactions.TransactionManager
import altitude.core.util.CurationRule
import altitude.core.util.Query

/**
 * Reads the Curation sets: the queue of Sequences and the frames of one Sequence or of a selection, each with its Faces and its
 * memberships, sorted by `CurationRule` as they are read, so nothing the rule decides is stored. A set's reads share one
 * snapshot.
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

  /** The set of a Sequence's frames still out of the trash, whatever a search shows of them; nothing with too few of them left */
  def sequenceSet(sequenceId: String): Option[CurationSet] =
    txManager.asReadOnly {
      assess(dao.getSequenceFrames(sequenceId))
    }

  /**
   * The set of the still images among the IDs, in the context repository and out of the trash; nothing with too few of them left.
   * The cap on a selection is the route's, as a page's size is `SearchResultsController`'s: what is refused never reaches a
   * service.
   */
  def selectionSet(assetIds: Set[String]): Option[CurationSet] =
    if assetIds.size < C.Curation.MIN_FRAMES then return None

    txManager.asReadOnly {
      val selected =
        new Query().add(FieldConst.ID -> Query.IN(assetIds.toSet[Any]), FieldConst.Asset.IS_PIPELINE_PROCESSED -> true)
      assess(dao.queryNotRecycled(selected).records.filter(_.isStillImage))
    }

  private def assess(assets: List[Asset]): Option[CurationSet] =
    Option.when(assets.size >= C.Curation.MIN_FRAMES) {
      val assetIds = assets.map(_.persistedId).toSet
      val faces = app.service.person.getFacesOfAssets(assetIds).groupBy(_.assetId)
      val heldElsewhere = app.service.album.getAssetsInAnyAlbum(assetIds) ++ app.service.location.getAssetsInAnyLocation(assetIds)
      val frames =
        assets.map(asset => CurationFrame(asset, faces.getOrElse(asset.id, Nil), heldElsewhere.contains(asset.persistedId)))
      val set = CurationRule.assess(frames, app.settings.current)
      logger.debug(s"Read a Curation set of ${frames.size} frames: ${set.bundles.map(_.kind)}, ${set.contenders.size} Contenders")
      set
    }
