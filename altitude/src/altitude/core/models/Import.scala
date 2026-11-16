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
 * One delivery of files to a Library: a browser upload, or one scan of a Watch folder that found files. `name` is the uploaded
 * folder's or Watch folder's name, none for an upload of files; `destFolderId` is none for Triage. It is in progress until
 * closed, which happens once it is marked complete (no more files will be added) and none of its files is pending.
 * `expectedFiles` is how many files it was opened to hold, if told, and counts only while it still takes files.
 */
case class Import(
    id: Option[String] = None,
    source: ImportSource,
    name: Option[String] = None,
    watchFolderId: Option[String] = None,
    accountId: Option[String] = None,
    destFolderId: Option[String] = None,
    expectedFiles: Option[Int] = None,
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

  def count(outcome: ImportOutcome): Int = outcome match
    case ImportOutcome.Pending => pending
    case ImportOutcome.Imported => imported
    case ImportOutcome.Duplicate => duplicate
    case ImportOutcome.Unsupported => unsupported
    case ImportOutcome.Failed => failed

  def total: Int = ImportOutcome.values.map(count).sum

  /** The files it is to hold in all, as far as it knows: once marked complete, just the files it has */
  def expectedTotal: Int = if isComplete then total else expectedFiles.fold(total)(_.max(total))

  /** Its pending files plus, while it still takes files, the ones it expects and has not been given */
  def remaining: Int = pending + expectedTotal - total
