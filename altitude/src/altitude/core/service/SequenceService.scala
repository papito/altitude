package altitude.core.service

import java.time.LocalDateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.dao.AssetDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Settings
import altitude.core.transactions.TransactionManager
import altitude.core.util.SequenceRule

/**
 * Puts the still images of one burst in a Sequence as their imports complete. A Sequence has no row of its own: it is the
 * `sequence_id` its members share, and exists while some asset carries it; a frame recycled from triage keeps it. A frame with a
 * capture time pairs by time and likeness; one without, by likeness alone among the `undatedCandidates` newest undated frames of
 * its size (a per-core cap, a parameter so a test can set its own). The two kinds never share a Sequence.
 */
class SequenceService(val app: Altitude, undatedCandidates: Int):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /**
   * Assigns a just-completed asset to its burst's Sequence and returns it with that Sequence. Runs in the completion transaction,
   * one asset of a repository at a time in upload order, so earlier frames of the burst are candidates already. The matches are
   * judged with one Settings snapshot, so a Setting changed meanwhile applies whole to the next assignment. No match leaves the
   * asset alone: a lone image is never a Sequence. Matches in several Sequences merge them, since a burst is transitive.
   *
   * It takes its turn among the writers of Sequences (`AssetDao.lockSequences`) before reading, so a resolve is either settled in
   * what it reads or waits and sees this asset. The candidates are read locked (dated) or re-read locked (undated), so one a
   * concurrent move sorts is left out and a Sequence is never stamped on the asset alone.
   */
  def assign(asset: Asset): Asset =
    txManager.withTransaction {
      if !SequenceRule.isEligible(asset) then asset
      else
        dao.lockSequences()
        val settings = app.settings.current
        val matches = if SequenceRule.isDated(asset) then datedMatches(asset, settings) else undatedMatches(asset, settings)
        join(asset, matches)
    }

  /** The dated candidates of the asset that are frames of its scene */
  private def datedMatches(asset: Asset, settings: Settings): List[Asset] =
    dao.getSequenceCandidates(asset, settings.sequenceMaxGapMs).filter(SequenceRule.matches(asset, _, settings))

  /**
   * The undated candidates, up to the cap, that are frames of the asset's scene, as their rows stand once locked. The streaming
   * read takes no row locks, so on PostgreSQL it can return a candidate a concurrent move is sorting; the few matches are re-read
   * locked, which waits for such a move, keeping those still in triage with the Sequence they carry now.
   */
  private def undatedMatches(asset: Asset, settings: Settings): List[Asset] =
    val collected = dao.getUndatedSequenceMatches(asset, undatedCandidates)(SequenceRule.matches(asset, _, settings))
    // The cap bounds what one import costs: older undated frames of the size, if any, went uncompared
    if collected.scanned == undatedCandidates then
      logger.debug(
        s"Asset [${asset.persistedId}] was compared with the $undatedCandidates newest undated frames of " +
          s"${asset.width}x${asset.height}; older undated frames of that size, if any, were not")
    dao.lockTriaged(collected.kept.map(_.persistedId).toSet)

  /** Puts the asset in the Sequence of its matches, if it has any, and returns it with that Sequence */
  private def join(asset: Asset, matches: List[Asset]): Asset =
    if matches.isEmpty then asset
    else
      val sequenceId = sequenceOf(matches)
      // A match already in the surviving Sequence keeps its row; one in another Sequence was repointed with that Sequence
      val joining = (asset.persistedId :: matches.filter(_.sequenceId.isEmpty).map(_.persistedId)).toSet
      val joined = dao.setSequence(joining, sequenceId)
      // Every match is locked from its read to this write, so a shortfall is not expected; it is logged rather than failing the
      // import, as the asset's own row is written either way
      if joined < joining.size then
        logger.debug(s"${joining.size - joined} of ${joining.size} assets left triage before joining Sequence [$sequenceId]")
      logger.debug(s"Asset [${asset.persistedId}] is in Sequence [$sequenceId] with ${matches.map(_.persistedId)}")
      asset.copy(sequenceId = Some(sequenceId))

  /**
   * The Sequence the matches end up in: a new one when none has one, the one they share, else the largest of theirs, the others'
   * members repointed to it
   */
  private def sequenceOf(matches: List[Asset]): String =
    matches.flatMap(_.sequenceId).distinct match
      case Nil => BaseDao.genId
      case one :: Nil => one
      case several =>
        given Ordering[LocalDateTime] = Ordering.fromLessThan(_.isBefore(_))
        // Most members, then earliest capture, then ID. An undated Sequence's first capture is `None`, but a merge never mixes
        // dated and undated Sequences, so among undated ones the ID decides. An empty count (every member left triage meanwhile)
        // starts a new Sequence rather than failing the import
        val survivor = dao
          .countSequenceMembers(several.toSet)
          .minByOption(sequence => (-sequence.members, sequence.firstCapturedAt, sequence.sequenceId))
          .fold(BaseDao.genId)(_.sequenceId)
        val merged = several.toSet - survivor
        val repointed = dao.repointSequences(merged, survivor)
        logger.info(s"Merged Sequences $merged into [$survivor]: $repointed assets repointed")
        survivor
