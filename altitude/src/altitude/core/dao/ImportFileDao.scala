package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome

trait ImportFileDao extends BaseDao[ImportFile]:
  /** In path order, only those of `outcome` when given */
  def ofImport(importId: String, outcome: Option[ImportOutcome], limit: Int): List[ImportFile]

  /** Those of the Imports that have a file of `outcome` with `reason` */
  def havingReason(importIds: Set[String], outcome: ImportOutcome, reason: String): Set[String]

  /** 0 for a file no longer pending, or not there */
  def settle(fileId: String, outcome: ImportOutcome, reason: Option[String], assetId: Option[String]): Int

  /** Fails every pending file of the Imports in progress; returns how many */
  def failPending(reason: String): Int
