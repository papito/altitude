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
   * what it reads or waits and sees this asset. The candidates are read unlocked and the matches re-read locked ([[locked]]), so
   * one a concurrent move sorts is left out and a Sequence is never stamped on the asset alone.
   */
  def assign(asset: Asset): Asset =
    txManager.withTransaction {
      if !SequenceRule.isEligible(asset) then asset
      else
        dao.lockSequences()
        val settings = app.settings.current
        val matches = if SequenceRule.isDated(asset) then datedMatches(asset, settings) else undatedMatches(asset, settings)
        join(asset, locked(matches))
    }

  /** The dated candidates of the asset that are frames of its scene */
  private def datedMatches(asset: Asset, settings: Settings): List[Asset] =
    dao.getSequenceCandidates(asset, settings.sequenceMaxGapMs).filter(SequenceRule.matches(asset, _, settings))

  /** The undated candidates, up to the cap, that are frames of the asset's scene */
  private def undatedMatches(asset: Asset, settings: Settings): List[Asset] =
    val collected = dao.getUndatedSequenceMatches(asset, undatedCandidates)(SequenceRule.matches(asset, _, settings))
    // The cap bounds what one import costs: older undated frames of the size, if any, went uncompared
    if collected.scanned == undatedCandidates then
      logger.debug(
        s"Asset [${asset.persistedId}] was compared with the $undatedCandidates newest undated frames of " +
          s"${asset.width}x${asset.height}; older undated frames of that size, if any, were not")
    collected.kept

  /**
   * The matches as their rows stand once locked. The candidates' read takes no row locks, so on PostgreSQL it can return one a
   * concurrent move is sorting; the re-read waits for such a move, keeping those still in triage with the Sequence they carry
   * now. When they are in several Sequences, which then merge, the re-read also locks every member the merge repoints, in the
   * same pass: the rows this writes are locked in one pass in ID order, as a library operation (a recycle, a move, a restore, a
   * purge) locks the rows it writes, so neither waits on the other in a cycle. The asset itself was locked by its completion,
   * before any other transaction can see it.
   */
  private def locked(matches: List[Asset]): List[Asset] =
    val matchIds = matches.map(_.persistedId).toSet
    val sequences = matches.flatMap(_.sequenceId).toSet
    // Only a Sequence writer gives a match another Sequence, and writers take turns: a move clears it, and the sorted match drops
    // out
    val merged = if sequences.size > 1 then sequences else Set.empty[String]
    dao.lockSequenceFrames(matchIds, merged).filter(frame => matchIds.contains(frame.persistedId))

  /** Puts the asset in the Sequence of its matches, if it has any, and returns it with that Sequence */
  private def join(asset: Asset, matches: List[Asset]): Asset =
    if matches.isEmpty then asset
    else
      val sequenceId = sequenceOf(matches)
      // A match already in the surviving Sequence keeps its row; one in another Sequence was repointed with that Sequence
      val joining = (asset.persistedId :: matches.filter(_.sequenceId.isEmpty).map(_.persistedId)).toSet
      val joined = dao.setSequence(joining, sequenceId)
      // Every match is locked from its re-read to this write, so a shortfall is not expected; it is logged rather than failing the
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
