# Import folder trees and Watch folders

## Goals

- Import an entire folder tree from the computer at the browser, optionally keeping its folder structure.
- Configure a Watch folder: a directory on the server that the app monitors for new assets. It picks up the files that arrived
  while it was not watched (the app was down).
- A Watch folder optionally deletes the files it imported successfully.

Requires the Import and Organize Permissions and `asset.owner_user_id`. Out of scope: importing from a server path as a one-off
action (a Watch folder with deletion off does it), resuming a browser upload across a server restart, resumable chunked
uploads, skipping known files by hashing in the browser, native file events (inotify), filtering by file extension, RAW and
sidecar files, upload size caps and the free-space check.

## How it works

**Tables.** Both `all.sql` files. A reference from one row of a Library to another is a key over
`(repository_id, <ref>_id)`, but for `import.watch_folder_id` and `import_file.asset_id`, single columns whose Library the
code checks.

| Table | Columns | Keys and indexes |
|---|---|---|
| `import` | `source` (`upload`, `watch_folder`), `name`, `watch_folder_id`, `account_id`, `dest_folder_id` (NULL = Triage), `expected_files`, `is_complete`, `closed_at`, and five counters: `pending`, `imported`, `duplicate`, `unsupported`, `failed` | `watch_folder_id` and `account_id` `ON DELETE SET NULL`; `import_01 (repository_id, created_at)`, the history by age; `import_02 (watch_folder_id)`, partial; `import_03 (repository_id, created_at) WHERE closed_at IS NULL`, the Imports in progress |
| `import_file` | `import_id`, `rel_path`, `outcome` (`pending`, `imported`, `duplicate`, `unsupported`, `failed`), `reason`, `asset_id`, `size_bytes` | The Import by cascade; `asset_id` `ON DELETE SET NULL`; `import_file_01 (import_id, outcome)`; `import_file_02 (asset_id)`, partial |
| `watch_folder` | `name`, `path` (absolute, as entered), `dest_folder_id`, `deletes_imported`, `is_paused`, `account_id` (its owner), `status_reason`, `last_scan_at` | `path` unique in the instance; `account_id` with no delete action; `watch_folder_01 (repository_id)` |
| `watch_folder_file` | `watch_folder_id`, `rel_path`, `size_bytes`, `modified_at` (milliseconds), `state` (`seen`, `handled`, `final`, `retry`, `parked`), `attempts`, `last_seen_at` | The Watch folder by cascade; `watch_folder_file_01 (watch_folder_id, rel_path)`, unique |

**Import records.** `ImportService` alone writes `import` and `import_file`: `open`, `addFile` (a pending file, refused once
the Import is complete), `settle` (the file's outcome and its Import's counters in one transaction, by statements that read
the row as they change it, so files settling at once add up), `close` (no more files will come). An Import is in progress
until `closed_at` is set: complete, and nothing pending. `expected_files` is how many files it was opened to hold, what an
upload is about to send or a scan to offer; `Import.remaining` is its pending files with the ones expected and not given yet,
so the history's bar fills as files settle rather than as they arrive. A reason is a phrase in sentence case with no full
stop. An Import's name and a file's path are logged with their control characters escaped (`Util.printable`).

**The element and the queue.** A file in the pipeline carries `ImportOrigin(importFileId, folderPath, source)` as
`AssetWithData.origin`: its Import file, the folder names under the destination it lands in, and, for a Watch folder's file,
the file in its directory. `ImportAsset` and plain fixtures carry none. The import queue is a `QueuedPipeline` that buffers
twice the core count; its offers take turns in the order made, with no thread held, so any number of uploads and scans
offering at once wait rather than fail, and a queue that is shut down or whose stream has stopped refuses
(`QueueRefusedException`), which records the file failed, "Import queue unavailable".

**Completion and settling.** `LibraryService.completeImport` is one transaction: the asset marked complete, then either
assigned to its Sequence in Triage or sorted into its Import's destination, counted in the stats, and its Import file settled
`imported` with the asset. The last working stage, `SettleImportFileFlow`, settles a dropped file after the discard
(`ImportService.settleDropped`) and then tells a Watch folder what became of its file.

| Dropped for | Outcome | Reason |
|---|---|---|
| The content of an asset whose import is complete | `duplicate`, with that asset | Where the asset is: "Triage", "Top level" or its folder's path |
| The content of a copy that is no completed asset: its own import failed, or it was recycled since | `failed` | "The copy it matched is not in the Library" |
| An unsupported media type | `unsupported` | "Unsupported media type" |
| Anything else | `failed` | The cause's name (`PipelineUtils.dropReason`), never its message |

The first of two copies in one Import wins. A file whose only copy is in the Trash is no duplicate and imports.

**Sorted on arrival.** An Import with a destination sorts each file as its import completes: `ImportService.destinationOf`
reads the destination and `FolderService.ensurePath` resolves the folder names of the file's path under it. A folder is found
by its name key among its parent's folders, live or recycled (a recycled one is restored, alone), and a missing one is added,
so a folder is born with the first asset that lands in or below it. The creators of one folder take turns on
`FolderDao.lockName`. The completion and `LibraryService.deleteFolderById` take turns on `FolderDao.lockTree`, an advisory
lock on the Library's folder tree, so nothing live is left under a deleted folder. A destination in the Trash, a folder name
the folder rule refuses and a name taken by the time the folder is added fail the completion with
`ImportDestinationException`, whose message is the file's reason; nothing of the path is created.

**Uploads.** `ImportController`, each route for a holder of Import, answering JSON:

| Route | Does |
|---|---|
| `POST /import/r/:repoId/imports` | Opens an upload's Import: optional `name` (the uploaded folder's), `destFolderId` (takes Organize too), `expectedFiles`. Answers its ID. |
| `POST …/imports/:importId/files?relPath=` | Takes one file, the request's body, streamed into staging, added to the Import and offered to the queue. Answers the Import file with its outcome. |
| `POST …/imports/:importId/failures` | Records a file the page could not send, by its `relPath` and `sizeBytes`: failed, "Upload failed". |
| `POST …/imports/:importId/close` | Marks the Import complete; cancelling an upload is this too. |

The three routes that name an Import take only an upload the account itself opened (`ImportService.ownUpload`). The files and
failures routes also take Organize again when the Import has a destination, so an account that holds it no longer is refused
from its next file on, and still closes the Import. A path is the names from what was chosen down, separated by "/"
(`util/RelPath`); a path with an empty name, a ".." or an ignored name, and an empty file, are a 400 and are not recorded.
An Import of another Library is a 404, one that is not the account's a 403, one that takes no more files a 409. A file that
was added and then cannot be described or offered is settled failed and answered as such; every file sent to an upload whose
destination went to the Trash is recorded failed on arrival, without being staged.

`util/IgnoredFiles` is the one list of ignored names, a file's or a directory's: a name starting with a dot, `Thumbs.db`,
`desktop.ini`, `@eaDir`, `#recycle`, `$RECYCLE.BIN`, `*.part`, `*.tmp`, `*.crdownload`. The page is served the list and
applies it before it sends anything.

**The Import page.** `pipeline.scala.html` is two columns: the sources (`includes/upload_sections`, then the Watch folders)
and the Import history; they stack on a narrow screen. The `importUploads` component holds File upload and Folder upload,
each a dashed drop zone with its button and, for a holder of Organize, a two-way option: "To Triage", or "Skip Triage" /
"Keep folder structure" with a destination folder field and, for a folder, a one-line example of the mapping.
`js/import/chosen-files.js` gathers what was chosen or dropped (dropped directories walked with `webkitGetAsEntry`): File
upload sends bare names; Folder upload sends each file's path, a single folder being the top (its name the Import's and no
part of the paths), several dropped items each a folder of its name. The one folder chosen or dropped alone is taken
whatever its name. `js/import/upload-run.js` opens the Import, sends three files at a time, sends a file again twice (after
1 s and 3 s) when its request gets no answer or a 5xx, then has the server record it failed, and closes the Import; Cancel
and leaving the page close it too. One upload runs at a time, with a progress bar by bytes and counts by files.

**Import history.** `ImportHistoryController`, for a holder of Import, whoever delivered an Import:
`GET /htmx/import/r/:repoId/history?shown=` and `GET …/imports/:importId/files?outcome=&shown=`. The history is a vertical
timeline, the Imports in progress under "In progress" above "Earlier", the newest first in each; an entry is its title, who
or what delivered it, when (an absolute time in the viewer's zone), where it went, a bar segmented by outcome and count
chips, and a "Files" disclosure listing its Import files under outcome filter chips. `shown` is how many are listed so far:
20 Imports a step up to 500, 50 files a step up to 500 (`Const.ImportHistory`). `ImportDao.list` reads the Imports in
progress by `import_03`, the closed ones off `import_01` from its newest end, and the total by a count.

**Status feed.** One WebSocket per Library, `/import/r/:repoId/status`, whose handshake checks the origin, the login, the
Library and Import in it. `ImportService` tells its listeners of an Import after each commit that changes it;
`ImportStatusWsActor` sends every socket of the Library the Import's status, rendered by the partials the history uses
(`includes/import_status`, `includes/import_title`): its first change at once, later ones at most one a second, and its
close at once and last. `{"watchFolders": true}` says the Library's Watch folder cards changed. `js/import-status.js` swaps a
status in, and reads the history again for an Import it does not show, after a close, and when the feed reconnects.

**Watch folders: configuration.** `WatchFolderService`; `WatchFolderController` under `/htmx/watch-folders/r/:repoId`, the
list for a holder of Import and every write and dialog an Admin's. A path is absolute and an existing directory the server
can list, writable when imported files are deleted. It overlaps no other Watch folder of any Library (the same directory,
or one inside the other, by real path; `WatchFolderDao.lockPaths` serializes two adds), and none of the app's own
directories: the data directory, staging, the SQLite database's directory and the file store down to each Library's
directories, each resolved on its own and compared by path and by the file system's identity of the directory, the path's
ancestors included. The context Admin owns the Watch folder it adds or edits. An edit that changes the path forgets the old
directory's files; one that switches deletion on makes the handled files files to read again. Removing one leaves its
files and its Imports.

**Watch folders: the scan.** `App` starts the scans last at startup: every Watch folder that is not paused is scanned at
once, then every `watch_folder.scan_interval` minutes (Live, default 10, from 2 to 360), and "Scan now" scans one. A scan
runs on the import dispatcher, one per Watch folder at a time. It walks the directory's real path, never following or reading
a symbolic link, leaving out ignored names (the watched directory itself is taken whatever its name), empty files and what is
no regular file, going on past a directory it cannot read, and never entering one of the app's own directories. `remember`
writes what the walk found to the memory and returns the files to read, in path order.

| State | Meaning | Read again |
|---|---|---|
| `seen` | Not read yet; a file whose size or modified time changed is `seen` again, never tried | Once it has rested: first seen as it is at least 5 seconds before the scan's walk began |
| `handled` | Imported, or a duplicate of an asset whose import is complete | No |
| `final` | Unsupported, an image or video that cannot be decoded, or a duplicate that is the stored file of the asset it matches | When it changes, or on "Retry failed" |
| `retry` | Failed, fewer than 3 attempts | By the next scan |
| `parked` | Failed 3 times | On "Retry failed" |

A file in the pipeline still is not offered again. Each file to read is copied into staging (never through a symbolic link),
added to the scan's Import, which its first file opens under the Watch folder's name, destination and owner, and offered to
the queue, one offer after another. A failure that is the Watch folder's rather than the file's (its owner unavailable, its
destination in the Trash, the queue refusing) counts as no attempt. A file absent from the directory is forgotten with the
history's prune, after as long as an Import is kept, or 30 days when the history is kept forever.

**Watch folders: status and owner.** A card says Watching, Paused or Unavailable, with a reason: paused by an Admin; paused
because its owner was deactivated or is no longer an Admin; paused while its destination folder is in the Trash, which
nothing stores, so it resumes when the folder is restored or replaced; unavailable because the path does not exist, is not
a directory, is not readable, is not writable with deletion on, overlaps the app's data or another Watch folder, or holds a
directory of the app's own data; and unavailable naming a directory that could not be read while the rest is scanned. A
change of an unavailable reason is logged at WARN. A deleted Admin's Watch folders pass to the deleting Admin with the
assets; resuming one whose owner is no active Admin gives it to the Admin who resumes it.

**Watch folders: deletion.** Off by default. With it on, a file is deleted once the write that made it `handled` has
committed: an imported file, and a duplicate of an asset whose import is complete. The file must be the one that was read:
under the directory's real path as it is now, below no symbolic link, a regular file with the size and modified time it had
when its copy was staged, and in no directory of the app's own. The directories the delete leaves empty are removed up to
the watched directory, which stays. Never deleted: an unsupported or failed file, a duplicate that is the same file as the
matched asset's stored file, and anything of a paused or removed Watch folder (a paused one reads the file again once
resumed). A delete that fails is logged at WARN and the file stays `handled`.

**Startup and prune.** `App` runs `ImportService.startup()` before anything is offered: every Import file still pending
becomes failed, "Interrupted by a restart", and every Import in progress is closed; then the history is pruned. The prune
deletes the closed Imports older than `import.history.days` (Live, default 90, 0 = forever) with their files, at startup
and daily. Staged files are not resumed; a Watch folder's files are read again by the startup scan.

## Decisions

| Concern | Decision |
|---|---|
| Words | **Import**: the stored record of one delivery, an upload or one scan of a Watch folder that found files. **Import file**: one file of it with its outcome. **Watch folder**: a server directory scanned for one Library, never a folder of the Library. "Keep folder structure" is the control's text |
| Tree source | The browser only: a folder picker (`webkitdirectory`) and a drop of folders on the Import page. No UI reads a server path except a Watch folder's |
| Structure | A choice per Import, off by default, so every file lands in Triage. File upload offers "Skip Triage": every file is sorted into the destination itself. Folder upload and a Watch folder offer "Keep folder structure": the tree is mirrored under the destination. Either way the assets arrive sorted, with no Sequence, and the choice needs Organize as well as Import |
| Organize | Checked when an upload with a destination is opened and again for every file it is given; closing needs Import alone |
| Mirroring | Contents only: the uploaded top folder (or the watched path) maps onto the destination itself, its subfolders onto folders under it. A drop of several items is its own top: each dropped folder a folder of its name under the destination. An existing folder of the same name key is reused, a recycled one restored; a missing one is created |
| Folder birth | In the transaction that completes the first asset landing in or below it. Folders of only unsupported files or only duplicates never appear. A file under a name the folder rule refuses fails with that reason |
| Folder deletes | A file's sorted arrival and a delete of a folder take turns on the Library's folder tree |
| Duplicates | Skipped and reported: the existing asset stays where it is, the first of two copies in one Import wins, and the Import file names where the existing asset is. Only an asset whose import is complete makes a file a duplicate; a copy of a file that was not imported is failed, saying so |
| Record | Every Import and every one of its files is stored. An imported Import file points at its asset until the asset is purged, and keeps its path after |
| Expected files | An Import is told how many files it is about to be given, so its bar counts the files yet to come as remaining |
| Retention | `import.history.days`, Live, default 90, 0 = forever; pruned at startup and daily. A Watch folder's memory is a table of its own, so pruning never causes a re-import |
| Upload transport | One file per request, three at a time, each with its relative path; a failed request is sent again twice, then recorded failed, and the rest go on. Cancel closes the Import. Choosing the same folder again skips the files already in the Library as duplicates |
| Queue | Offers wait their turn; none is refused for arriving with others |
| Ignored files | One list, served to the page by the server and enforced by the upload routes and the scan; empty files, and in a Watch folder symbolic links. The folder the user named, chosen or dropped alone, and the watched directory are taken whatever their names |
| Live status | The Imports in progress head the history, with a bar and counts pushed over the Library's WebSocket to every account with Import there, at most once a second per Import |
| History windows | The history and an Import's files show a step more at a time (`shown`), 20 and 50, up to 500 each; neither list is read whole |
| History times | An absolute date and time in the viewer's zone |
| Import files | Under a "Files" disclosure in the entry, filtered by outcome chips with counts; an imported file links to its asset in a new tab |
| Restart | Every pending Import file becomes failed, "Interrupted by a restart", and its Import is closed. Staged files are not resumed |
| Watch folder scope | An Admin adds, edits, pauses, scans, retries and removes; an account with Import sees the cards read-only. Several per Library. No two overlap in any Library, and none overlaps a directory the app stores into, by path or by identity |
| Watch folder target | Scanned recursively, with the same "Keep folder structure" choice and destination as an upload |
| Watch folder owner | The Admin who created or last edited it owns its assets. A deleted Admin's pass to the deleting Admin; a deactivated or demoted one's pause |
| Detection | Polling only: a scan at startup, then every `watch_folder.scan_interval` minutes (Live, 10, from 2 to 360), plus "Scan now" |
| Rest | A file is read once a scan finds it as a scan found it at least 5 seconds before, by size and modified time; two scheduled scans always span it |
| Memory | A handled file is remembered by relative path, size and modified time and not read again; a changed file is a new file. A remembered file absent from the directory is forgotten after the retention period (30 days when history is kept forever) |
| Retries | Unsupported type, undecodable image or video: final until the file changes. Any other failure of the file: three attempts in all, then parked. "Retry failed" clears both kinds |
| Deletion | Per Watch folder, off by default. Deleted: a file once its import completes, and a duplicate of a completed asset. Never touched: unsupported and failed files, the app's own files, the watched path. Emptied subdirectories are removed |
| Deletion notice | Switching deletion on in the dialog shows a red notice naming the number of files in the folder now and what is deleted, and the submit reads "Save and delete imported files"; there is no separate confirmation |
| Unavailable | A path that fails the path rules, or holds a directory of the app's own, is not scanned and says why; a directory that cannot be read is named while the rest is scanned. Tried again each interval, logged at WARN on each change |
| Lost destination | A Watch folder whose destination is in the Trash is paused, its files untouched, until the folder is restored or replaced. Deleting such a folder warns in its confirmation. An upload in that state fails its remaining files |
| Page | Two columns: File upload, Folder upload and Watch folders on the left, the Import history, wider, on the right; stacked in that order on a narrow screen |
| Upload sections | Each a dashed drop zone with its button inside and its option below, a two-way toggle. Each zone applies its own option to whatever is dropped on it. One upload runs at a time |
| Import title | A folder upload is titled by its top folder ("3 folders" for a drop of several), a scan by its Watch folder's name, an upload of files by its file count |
| Watch folders section | Cards in a grid: name, path, target, last scan, the imported and failed counts of its Imports in the history, whether it deletes imported files, and a status chip with its reason. "Scan now" on the card; Pause / Resume, Retry failed, Edit… and Remove… in a ⋯ menu |
| Watch folder dialog | Three fieldsets: Source (name, path), Destination ("Keep folder structure" and its folder), After import ("Delete imported files") |

## Changes

### A. Import records

1. `import` and `import_file` with `Import`, `ImportFile`, `ImportOutcome`, `ImportSource`, `ImportDao`, `ImportFileDao` and
   `ImportService` (`open`, `addFile`, `settle`, `close`, `history`, `filesShown`).
2. `ImportOrigin` on `AssetWithData`; `completeImport` settles an imported file in its transaction and
   `SettleImportFileFlow` a dropped one (`settleDropped`); `QueuedPipeline.offer` takes turns.
3. `ImportService.startup` (`failInterrupted`, `pruneOrLog`) called by `App`; the `import.history.days` Setting and the
   daily prune scheduled in `Altitude`.

### B. Upload by file, and folder trees

4. The four upload routes of `ImportController`; `util/RelPath`, `util/IgnoredFiles`, `StagingService.stage`;
   `ownUpload` and the Organize check per file.
5. `FolderService.ensurePath` with `FolderDao.lockName`, the destination branch of `completeImport` (`sortInto`), and
   `FolderDao.lockTree` shared with `deleteFolderById`.
6. `includes/upload_sections`, `importUploads`, `js/import/chosen-files.js`, `js/import/upload-run.js`,
   `static/css/includes/upload_sections.css` and `pages/pipeline.css`; the destination field is
   `includes/filter_combobox`.

### C. Import history

7. `ImportHistoryController` with `htmx/import_history`, `htmx/import_files`, `includes/import_status`,
   `includes/import_title`; `ImportStatusWsActor` and the socket of `ImportController.pipelineStatus`;
   `js/import-status.js`, the page's one live display.

### D. Watch folders

8. `watch_folder`, `WatchFolder`, `WatchFolderSpec`, `WatchFolderDao`, and `WatchFolderService` `add`, `update`, `remove`,
   `pause`, `resume` with the path rules (`pathProblem`, `ownDirectories`, `lockPaths`).
9. `watch_folder_file`, `WatchFolderFileDao`, `scan`, `walk`, `remember`, `take`, `settled`, `retryFailed`, and
   `forgetAbsent` in the prune.
10. `start` and `stop`, the `watch_folder.scan_interval` Setting, `scanNow`, `cards` and `statusOf`, and the owner rules in
    `UserService` (`pauseOwnedBy`, `passOn`).
11. `deletes_imported`, `deleteSource` and the removal of emptied directories, after the commit that made the file
    `handled`.
12. `WatchFolderController`, `htmx/watch_folders`, `htmx/watch_folder_form` with `watchFolderForm`,
    `includes/watch_folder_deletion_notice`, `htmx/remove_watch_folder_dialog`, `js/import/watch-folders.js`, and the
    folder Delete dialog's warning (`targeting`, `FolderActionController`).

### E. Docs

13. `altitude/AGENTS.md` (**Import Pipeline**, **Watch folders**), `altitude/views/AGENTS.md` (**Import page**),
    `CONTEXT.md` (**Import**, **Import file**, **Watch folder**), `docs/READINESS.md`, `docs/test-coverage.md`.

## Verification

- `make test` passes: the unit bundle (317), the controller bundle (306), SQLite (762, one cancelled as PostgreSQL-only)
  and PostgreSQL (761); `make lint` is clean.
- The suites, on both engines where a database is involved: `ImportServiceTests` (the record, counters under concurrent
  settles, the owner and Organize rules, the restart, the prune, the logged names, the plans of the history's statements),
  `ImportPipelineServiceTests` (outcomes, sorted arrival, the folder tree's turn-taking by `contend`, offers past the
  queue's size), `FolderServiceTests` (`ensurePath`), `WatchFolderServiceTests` (path rules, a store behind a symbolic
  link, the scan, the rest, memory, retries, statuses, owners, deletion, a hard link to a stored file, a file settling
  during the next scan's read), `ImportControllerTests` and `WatchFolderControllerTests` (the routes, their Permissions,
  the partials, an upload pushed to both sockets of its Library and to none of another), `ImportStatusWsActorTests`,
  `ImportStatusTests`, `SettingsServiceTests`, `RouteTableTests`.
- In the browser, headless Chrome against scratch instances on scratch data: a folder upload of 320 images on an instance
  held to two processors, every file imported; a folder named `.archive`, chosen in the picker and dropped on the zone,
  uploaded with the ignored names below it left out; the Import page, empty and with Imports and a Watch folder card, in
  both themes, with the dashed edges drawn from `--dashed-border-color`.
- In the browser as each part was built, the same way, and not repeated on the final tree:
  - Uploads: files to Triage and with Skip Triage; a dropped tree with Keep folder structure merging into an existing
    folder, folders made only where an asset landed and ignored files not sent; the mapping example following the picker;
    progress and Cancel; a file that fails not stopping the rest; the same tree again skipped as duplicates; a destination
    recycled mid-upload failing the remaining files on arrival; an account without Organize seeing no option controls; the
    expected count, with the bar filling and "remaining" counting down from the start; the narrow-screen stack.
  - The Import history: an upload seen live by its uploader and by a second account with Import, moving to "Earlier" at
    its close, one push a second at most; no feed for an account without Import; expand, the outcome filters, "Show more"
    and the Asset detail link, an expanded Import staying as it was through pushes and the close; a socket cut and
    restored catching up; names holding markup shown as text; the empty state; both themes.
  - Watch folders: each path refusal in the dialog, the deletion notice's count and the Save label, edit, pause and
    resume, Scan now, Retry failed, remove; scheduled scans, at the default and on a real two-minute interval; deletion on
    removing imported and duplicate files and emptied subdirectories and nothing else; Unavailable and its recovery; the
    lost destination's pause and resume; the read-only cards of an Import-only account; the folder delete confirmation's
    warning; the Setting's field and its bounds.
- By hand, in a mount namespace with the data directory mounted at a second path: a Watch folder on that path, or inside
  it, is refused; one on a directory that holds it is unavailable, naming it, and nothing of the store is read or deleted.
- Not covered:
  - The front end has no JS test harness: the upload sections, the history's live updates in an open page and the Watch
    folder dialog are exercised in the browser alone, and only as far as the checks above.
  - A directory of the app's own under a second mount has no suite test, and neither has a directory whose listing an I/O
    error ends midway.
  - Two mounts that give one directory different identities (a network share mounted twice with separate device numbers)
    are not told apart from two directories.
  - A Watch folder deletes by size and modified time, without reading the file again.
  - The server stopped in the middle of a scan, and `addToQueue` under backpressure through the real pipeline.
  - Accepted uploads are lost on a restart (reported, not resumed), and a failed import stream refuses uploads until the
    server restarts.
  - The pipeline's own log lines name an uploaded file as it was sent.
