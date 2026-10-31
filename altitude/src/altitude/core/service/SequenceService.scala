package altitude.core.service

import java.time.LocalDateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.dao.AssetDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.transactions.TransactionManager
import altitude.core.util.SequenceRule

/**
 * Puts the still images of one burst in a Sequence as their imports complete. A Sequence has no row of its own: it is the
 * `sequence_id` its members share, and it exists exactly while some asset in triage carries it.
 */
class SequenceService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: AssetDao = app.DAO.asset
  protected val txManager: TransactionManager = app.txManager

  /**
   * Assigns a just-completed asset to the Sequence of its burst, if it has one, and returns it with that Sequence. Called inside
   * the completion transaction, one asset of a repository at a time in upload order, so an earlier upload of the same burst is a
   * candidate already. Nothing happens for an asset `SequenceRule.isEligible` refuses. The candidates are the repository's
   * completed, live, triaged still images captured within `sequence.max_gap_ms` of it; the matches among them are decided by
   * `SequenceRule.matches` with both Settings from one snapshot. No match leaves the asset alone: a lone image is never a
   * Sequence. Matches with no Sequence get a new one with the asset; matches in one Sequence join the asset to it; matches in
   * several merge them, since a burst is transitive, into the one with the most members (ties to the one captured first).
   */
  def assign(asset: Asset): Asset =
    txManager.withTransaction {
      if !SequenceRule.isEligible(asset) then asset
      else
        val settings = app.settings.current
        val matches = dao
          .getSequenceCandidates(asset, settings.sequenceMaxGapMs)
          .filter(candidate => SequenceRule.matches(asset, candidate, settings))

        if matches.isEmpty then asset
        else
          val sequenceId = sequenceOf(matches)
          // A match already in the surviving Sequence keeps its row; one in another Sequence was repointed with that Sequence
          val joining = (asset.persistedId :: matches.filter(_.sequenceId.isEmpty).map(_.persistedId)).toSet
          val joined = dao.setSequence(joining, sequenceId)
          // The candidates were locked, so a shortfall is a row a move sorted before the lock was taken, which stays out
          if joined < joining.size then
            logger.debug(s"${joining.size - joined} of ${joining.size} assets left triage before joining Sequence [$sequenceId]")
          logger.debug(s"Asset [${asset.persistedId}] is in Sequence [$sequenceId] with ${matches.map(_.persistedId)}")
          asset.copy(sequenceId = Some(sequenceId))
    }

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
        // Most members first, then the earliest capture, then the id; a count that comes back empty (the members all left
        // triage meanwhile) starts a new Sequence rather than failing the import
        val survivor = dao
          .countSequenceMembers(several.toSet)
          .minByOption(sequence => (-sequence.members, sequence.firstCapturedAt, sequence.sequenceId))
          .fold(BaseDao.genId)(_.sequenceId)
        val merged = several.toSet - survivor
        val repointed = dao.repointSequences(merged, survivor)
        logger.info(s"Merged Sequences $merged into [$survivor]: $repointed assets repointed")
        survivor
