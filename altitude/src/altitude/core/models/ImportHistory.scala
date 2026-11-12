package altitude.core.models

import java.time.Instant

/**
 * An Import as the Import history shows it. `openedAt` is the moment it was opened; `deliveredBy` the name of the account that
 * delivered it, none for a Watch folder's scan and once that account is deleted; `destination` the path of the folder its files
 * were sorted into, none for Triage; `isInterrupted` whether a restart failed files of it.
 */
case class ImportEntry(
    anImport: Import,
    openedAt: Option[Instant],
    deliveredBy: Option[String],
    destination: Option[String],
    isInterrupted: Boolean)

/** The top of a Library's Import history, the Imports in progress first, then the newest first, of `total` Imports in all */
case class ImportHistory(entries: List[ImportEntry], total: Int):
  def inProgress: List[ImportEntry] = entries.filter(_.anImport.isInProgress)

  def earlier: List[ImportEntry] = entries.filterNot(_.anImport.isInProgress)

/** The first `files` of an Import in path order, those of one outcome when `outcome` is given */
case class ImportFiles(anImport: Import, outcome: Option[ImportOutcome], files: List[ImportFile]):
  /** How many files the Import has under the filter, by its counters */
  def total: Int = outcome.fold(anImport.total)(anImport.count)
