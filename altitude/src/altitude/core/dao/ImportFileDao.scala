package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome

/** The Import files of the context repository */
trait ImportFileDao extends BaseDao[ImportFile]:
  /** The Import's files in path order, those of one outcome when `outcome` is given */
  def ofImport(importId: String, outcome: Option[ImportOutcome]): List[ImportFile]

  /** Gives a pending file its outcome; 0 for a file that is not pending any more, and for one that is not there */
  def settle(fileId: String, outcome: ImportOutcome, reason: Option[String], assetId: Option[String]): Int

  /** Fails, with `reason`, every pending file of the Imports still in progress; how many it failed */
  def failPending(reason: String): Int
