package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Import
import altitude.core.models.ImportOutcome
import altitude.core.util.QueryResult

/**
 * The Imports of the context repository. An Import's counters and its closing are written by single statements that read the row
 * as they change it, so the writes of files settling at once add up.
 */
trait ImportDao extends BaseDao[Import]:
  /** The first `shown` Imports with the total of them all: the ones in progress first, then the closed ones, each the newest first */
  def list(shown: Int): QueryResult[Import]

  /** Counts one more pending file of an Import still taking files; 0 for one marked complete, and for one that is not there */
  def addPending(importId: String): Int

  /**
   * Moves one file of the Import from pending to `outcome` in its counters, and closes the Import when it is marked complete and
   * that file was its last pending one; 0 for an Import that is not there
   */
  def fileSettled(importId: String, outcome: ImportOutcome): Int

  /** Marks the Import complete, and closes it when none of its files is pending; 0 for an Import that is not there */
  def markComplete(importId: String): Int

  /** Closes every Import still in progress, counting its pending files as failed; how many it closed */
  def closeInterrupted(): Int

  /** The imported and the failed files of each Watch folder's Imports, by the Watch folder's ID; none for one with no Import */
  def countsByWatchFolder(): Map[String, (Int, Int)]

  /** Deletes the closed Imports created before `cutoff`, with their Import files; how many Imports it deleted */
  def deleteClosedBefore(cutoff: OffsetDateTime): Int
