package altitude.core.models

import java.time.LocalDateTime

/** How an Import's files were delivered. Persist `dbValue`, never the enum name. */
enum ImportSource(val dbValue: String):
  case Upload extends ImportSource("upload")
  case WatchFolder extends ImportSource("watch_folder")

object ImportSource:
  def fromDbValue(value: String): ImportSource =
    values.find(_.dbValue == value).getOrElse(throw IllegalArgumentException(s"Unknown Import source: $value"))

/**
 * The stored record of one delivery of files to a Library: an upload from a browser, or one scan of a Watch folder that found
 * files. `name` is the uploaded folder's or the Watch folder's name, none for an upload of files; `accountId` the account that
 * delivered it, none once that account is deleted; `destFolderId` the folder its files are sorted into, none for Triage. The five
 * counters are its Import files by outcome. It is in progress until it is closed (`closedAt`), which it is once it is marked
 * complete (`isComplete`: no more files will be added) and none of its files is pending.
 */
case class Import(
    id: Option[String] = None,
    source: ImportSource,
    name: Option[String] = None,
    watchFolderId: Option[String] = None,
    accountId: Option[String] = None,
    destFolderId: Option[String] = None,
    isComplete: Boolean = false,
    closedAt: Option[LocalDateTime] = None,
    pending: Int = 0,
    imported: Int = 0,
    duplicate: Int = 0,
    unsupported: Int = 0,
    failed: Int = 0,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  def isInProgress: Boolean = closedAt.isEmpty

  /** How many of its files have the outcome */
  def count(outcome: ImportOutcome): Int = outcome match
    case ImportOutcome.Pending => pending
    case ImportOutcome.Imported => imported
    case ImportOutcome.Duplicate => duplicate
    case ImportOutcome.Unsupported => unsupported
    case ImportOutcome.Failed => failed

  /** Its files in all, whatever became of them */
  def total: Int = ImportOutcome.values.map(count).sum
