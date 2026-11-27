package altitude.core.models

import java.time.Instant
import java.time.LocalDateTime

/**
 * A directory on the server (`path`, absolute) scanned for one Library, importing the files that arrive. Files are sorted into
 * `destFolderId` with the directory's tree mirrored under it, or into Triage when none. `deletesImported` also deletes a file
 * found to be a duplicate. `accountId`, the Admin who added or last edited it, owns the assets it imports. `isPaused` is an
 * Admin's pause, or a forced one when the owner was deactivated or is no Admin any more, which `statusReason` then says;
 * otherwise `statusReason` is why the last scan found the directory unavailable.
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

/** What an Admin enters to add or edit a Watch folder */
case class WatchFolderSpec(name: String, path: String, destFolderId: Option[String] = None, deletesImported: Boolean = false)

/** The status chip on a Watch folder's card */
enum WatchFolderStatus(val label: String, val cssClass: String):
  case Watching extends WatchFolderStatus("Watching", "watching")
  case Paused extends WatchFolderStatus("Paused", "paused")
  case Unavailable extends WatchFolderStatus("Unavailable", "unavailable")

/**
 * A Watch folder as the Import page's card shows it. `destination` is a folder path, none for Triage; `imported` and `failed`
 * count the files of its Imports in the Import history.
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
  /** Not read yet, or changed since: read once a scan finds the size and modified time a scan saw a rest period before */
  case Seen extends WatchFileState("seen")

  /** Imported, or a duplicate: not read again */
  case Handled extends WatchFileState("handled")

  /** Of an unsupported type, or an image or video that cannot be decoded: not read again until the file changes */
  case Final extends WatchFileState("final")

  /** Failed, with attempts left: read again by a later scan */
  case Retry extends WatchFileState("retry")

  /** Out of attempts: left alone until the file changes or an Admin retries the failed files */
  case Parked extends WatchFileState("parked")

object WatchFileState:
  def fromDbValue(value: String): WatchFileState =
    values.find(_.dbValue == value).getOrElse(throw IllegalArgumentException(s"Unknown Watch folder file state: $value"))

/**
 * A Watch folder's memory of one file. `relPath` is "/"-separated; `modifiedAt` is epoch milliseconds as the last scan saw it;
 * `lastSeenAt` is when a scan last found it, to the day.
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
    createdAt: Option[LocalDateTime] = None)
  extends BaseModel
