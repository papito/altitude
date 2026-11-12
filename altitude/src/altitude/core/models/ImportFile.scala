package altitude.core.models

import java.time.LocalDateTime

/**
 * What became of an Import file. Persist `dbValue`, never the enum name; it is also the name of the Import's counter of the
 * outcome.
 */
enum ImportOutcome(val dbValue: String):
  case Pending extends ImportOutcome("pending")
  case Imported extends ImportOutcome("imported")
  // Already in the Library
  case Duplicate extends ImportOutcome("duplicate")
  case Unsupported extends ImportOutcome("unsupported")
  case Failed extends ImportOutcome("failed")

object ImportOutcome:
  def fromDbValue(value: String): ImportOutcome =
    values.find(_.dbValue == value).getOrElse(throw IllegalArgumentException(s"Unknown Import outcome: $value"))

/**
 * One file of an Import with its outcome. `relPath` is its path as it was delivered, its name for an upload of files. `reason`
 * says why it has its outcome where there is something to say: where the asset a duplicate matches is, why a file failed.
 * `assetId` is the asset an imported file became, or the asset of the Library a duplicate matches, until that asset is purged.
 */
case class ImportFile(
    id: Option[String] = None,
    importId: String,
    relPath: String,
    outcome: ImportOutcome = ImportOutcome.Pending,
    reason: Option[String] = None,
    assetId: Option[String] = None,
    sizeBytes: Long,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
