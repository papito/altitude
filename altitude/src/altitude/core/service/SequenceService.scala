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
 * `sequence_id` its members share, and it exists exactly while some asset carries it: a member leaves it by a move into a folder
 * (`LibraryService.moveAssetsToFolder`), as a Pick of a Curation set that held the whole Sequence (`CurationService.resolve`) or
 * with its row's delete (a purge, the discard of a dropped import, a repository delete); one recycled from triage keeps it. A
 * frame with a capture time pairs by time and likeness; one without, by likeness alone among the `undatedCandidates` newest
 * undated frames of its size (`Altitude.undatedSequenceCandidates`, a share of `Const.Sequence.UNDATED_CANDIDATES_PER_CORE` per
 * core; a parameter so a test can build the service with a cap of its own). The two kinds never share a Sequence.
 */
class SequenceService(val app: Altitude, undatedCandidates: Int):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /** How many assets carry the Sequence, for the Detail panel: 0 once every member has left triage or been purged */
  def memberCount(sequenceId: String): Int =
    txManager.asReadOnly {
      dao.countSequenceMembers(Set(sequenceId)).headOption.fold(0)(_.members)
    }

  /**
   * Assigns a just-completed asset to the Sequence of its burst, if it has one, and returns it with that Sequence. Called inside
   * the completion transaction, one asset of a repository at a time in upload order, so an earlier upload of the same burst is a
   * candidate already. Nothing happens for an asset `SequenceRule.isEligible` refuses. The candidates of a dated frame
   * (`SequenceRule.isDated`) are the repository's completed, triaged still images captured within `sequence.max_gap_ms` of it, in
   * the trash or not (a frame recycled from triage keeps its Sequence, so the next frame of its burst joins it there); those of
   * an undated frame (`SequenceRule.isUndated`) are the repository's completed, triaged, undated still images of its width and
   * height, newest imports first, up to `undatedCandidates`, streamed so no more than the matches are held (the cap bounds what
   * one import costs whatever triage holds; when the read scanned as many rows as the cap, older undated frames of the size went
   * uncompared, which is logged at DEBUG). Both reads carry the Scene embeddings, which no other read selects; the matches among
   * the candidates are decided by `SequenceRule.matches` with each Scene model's similarity threshold, the gap and the hash
   * distance all from one Settings snapshot, so a Setting changed meanwhile applies whole to the next assignment. No match leaves
   * the asset alone: a lone image is never a Sequence. Matches with no Sequence get a new one with the asset; matches in one
   * Sequence join the asset to it; matches in several merge them, since a burst is transitive, into the one with the most members
   * (ties to the one captured first; among undated Sequences, which have no first capture, to the lowest ID). The assignment
   * takes its turn among the writers of Sequences (`AssetDao.lockSequences`) before it reads, so a resolve is either settled in
   * what it reads, or waits and sees this asset. The dated candidates are read locked, and the undated matches are re-read locked
   * before they are stamped (`AssetDao.lockTriaged`), so on either path a candidate a move sorts meanwhile is re-evaluated once
   * the move commits and left out, and a Sequence is never stamped on the asset alone.
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
   * The undated candidates of the asset, up to the cap, that are frames of its scene, as their rows stand once locked. The
   * streaming read takes no row locks, so on PostgreSQL a candidate a concurrent move is sorting still comes back a match; the
   * matches, a few rows, are re-read locked, which waits for such a move, and only those still in triage are kept, each with the
   * Sequence it carries now (the move may have cleared it).
   */
  private def undatedMatches(asset: Asset, settings: Settings): List[Asset] =
    val collected = dao.getUndatedSequenceMatches(asset, undatedCandidates)(SequenceRule.matches(asset, _, settings))
    // The read stopped at the cap, so any older undated frame of the size went uncompared; triage may also hold exactly that many
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
      // import, since the asset's own row is written either way
      if joined < joining.size then
        logger.debug(s"${joining.size - joined} of ${joining.size} assets left triage before joining Sequence [$sequenceId]")
      logger.debug(s"Asset [${asset.persistedId}] is in Sequence [$sequenceId] with ${matches.map(_.persistedId)}")
      asset.copy(sequenceId = Some(sequenceId))

  /**
   * The Sequence the matches end up in: a new one when none of them has one, the one they share when they share one, else the
   * largest of theirs, the members of the others repointed to it (one update per merge, by `sequence_id`)
   */
  private def sequenceOf(matches: List[Asset]): String =
    matches.flatMap(_.sequenceId).distinct match
      case Nil => BaseDao.genId
      case one :: Nil => one
      case several =>
        given Ordering[LocalDateTime] = Ordering.fromLessThan(_.isBefore(_))
        // Most members first, then the earliest capture, then the id; the first capture is `None` for an undated Sequence, which
        // `Ordering.Option` orders before any time without failing, and since the matches are all of the asset's kind, a merge
        // is of dated Sequences or of undated ones, so among undated Sequences the tie falls to the id. A count that comes back
        // empty (the members all left triage meanwhile) starts a new Sequence rather than failing the import
        val survivor = dao
          .countSequenceMembers(several.toSet)
          .minByOption(sequence => (-sequence.members, sequence.firstCapturedAt, sequence.sequenceId))
          .fold(BaseDao.genId)(_.sequenceId)
        val merged = several.toSet - survivor
        val repointed = dao.repointSequences(merged, survivor)
        logger.info(s"Merged Sequences $merged into [$survivor]: $repointed assets repointed")
        survivor
