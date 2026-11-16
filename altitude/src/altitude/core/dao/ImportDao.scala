package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Import
import altitude.core.models.ImportOutcome
import altitude.core.util.QueryResult

/** Counters and closing are single statements that read the row as they change it, so files settling at once add up */
trait ImportDao extends BaseDao[Import]:
  /** The first `shown` Imports and the total: in progress first, then closed, each newest first */
  def list(shown: Int): QueryResult[Import]

  /** One more pending file; 0 for an Import marked complete or not there */
  def addPending(importId: String): Int

  /** Moves one file from pending to `outcome`, closing an Import marked complete when it was the last pending one */
  def fileSettled(importId: String, outcome: ImportOutcome): Int

  /** Also closes the Import when none of its files is pending */
  def markComplete(importId: String): Int

  /** Closes every Import still in progress, counting its pending files as failed; how many it closed */
  def closeInterrupted(): Int

  /** Watch folder ID to the imported and failed file counts of its Imports */
  def countsByWatchFolder(): Map[String, (Int, Int)]

  /** With their Import files; returns how many Imports */
  def deleteClosedBefore(cutoff: OffsetDateTime): Int
