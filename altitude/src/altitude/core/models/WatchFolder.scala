package altitude.core.models

import java.time.Instant
import java.time.LocalDateTime

/**
 * A Watch folder: a directory on the server (`path`, absolute) that the app scans for one Library, importing the files that
 * arrive in it; never a folder of the Library. `destFolderId` is the folder its files are sorted into, the directory's tree
 * mirrored under it, none for Triage. `deletesImported` says a file is deleted from the directory once it is imported or is a
 * duplicate of an asset of the Library. `accountId` is its owner, the Admin who added it or last edited it, who owns the assets
 * it imports. `isPaused` is an Admin's pause, or the pause of a Watch folder whose owner was deactivated or is no Admin any more,
 * which `statusReason` then says; for a Watch folder that is not paused, `statusReason` is why its directory is unavailable, as
 * its last scan found it. `lastScanAt` is when its directory was last walked.
 */
case class WatchFolder(
    id: Option[String] = None,
    name: String,
    path: String,
    destFolderId: Option[String] = None,
    deletesImported: Boolean = false,
    isPaused: Boolean = false,
    accountId: String,
    statusReason: Option[String] = None,
    lastScanAt: Option[LocalDateTime] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel

/** What an Admin enters of a Watch folder, to add one or to edit one */
case class WatchFolderSpec(name: String, path: String, destFolderId: Option[String] = None, deletesImported: Boolean = false)

/** What a Watch folder is doing, as its card says it: the chip's word, and the name of the chip's class */
enum WatchFolderStatus(val label: String, val cssClass: String):
  case Watching extends WatchFolderStatus("Watching", "watching")
  case Paused extends WatchFolderStatus("Paused", "paused")
  case Unavailable extends WatchFolderStatus("Unavailable", "unavailable")

/**
 * A Watch folder as the Import page's card shows it: its status with the reason beside it, the path of its destination folder
 * (none for Triage), the moment of its last scan, whether a scan of it is running now, and the imported and failed files of its
 * Imports in the Import history
 */
case class WatchFolderCard(
    watchFolder: WatchFolder,
    status: WatchFolderStatus,
    reason: Option[String],
    destination: Option[String],
    lastScanAt: Option[Instant],
    isScanning: Boolean,
    imported: Int,
    failed: Int)

/** What a Watch folder's memory holds of a file. Persist `dbValue`, never the enum name. */
enum WatchFileState(val dbValue: String):
  /** Not read yet, or changed since it was: read once a scan finds its size and modified time as the scan before did */
  case Seen extends WatchFileState("seen")

  /** Imported, or a duplicate of an asset of the Library: not read again */
  case Handled extends WatchFileState("handled")

  /** Of an unsupported type, or an image or video that cannot be decoded: not read again until the file changes */
  case Final extends WatchFileState("final")

  /** Failed, fewer times than a file is tried: read again by a later scan */
  case Retry extends WatchFileState("retry")

  /** Failed as many times as a file is tried: left alone until the file changes or an Admin retries the failed files */
  case Parked extends WatchFileState("parked")

object WatchFileState:
  def fromDbValue(value: String): WatchFileState =
    values.find(_.dbValue == value).getOrElse(throw IllegalArgumentException(s"Unknown Watch folder file state: $value"))

/**
 * A Watch folder's memory of one file of its directory, by the file's path under it (`relPath`, its names separated by "/"): the
 * size and the modified time (milliseconds since the epoch) the last scan saw, what became of the file, how many times its import
 * failed, and when a scan last found it, to the day
 */
case class WatchFolderFile(
    id: Option[String] = None,
    watchFolderId: String,
    relPath: String,
    sizeBytes: Long,
    modifiedAt: Long,
    state: WatchFileState = WatchFileState.Seen,
    attempts: Int = 0,
    lastSeenAt: Option[LocalDateTime] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
