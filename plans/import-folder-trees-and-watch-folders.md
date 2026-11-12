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

## How it works now

**The one import.** The Import page (`ImportController.pipeline`, `views/pipeline.scala.html`) holds one form
(`htmx/upload_form.scala.html`, `fragments/upload-form.js`): `<input type="file" multiple>`, every chosen file sent in one
multipart POST to `/import/r/:repoId/upload/:uploadId`. The handler moves each of Undertow's temp files into `../data/staging`
(`StagingService`), describes it (`LibraryService.stagedFileToAsset`: checksum, media type by content, `isTriaged = true`,
`folderId = ""`) and offers it to the import queue (`ImportPipelineService.addToQueue`), waiting while the queue is full.
Cancelling is a process-wide map of upload IDs (`ImportController.uploadCancelRequest`).

**Where an asset lands.** Always Triage. `LibraryService.completeImport` marks the asset complete, assigns its Sequence and
counts it, in one transaction. Nothing in the pipeline's element says where the file came from: `AssetWithData` is the asset
and its staged path, `PipelineContext` the repository and the account.

**Outcomes.** A dropped asset is an `InvalidAsset` with its cause; `DiscardDroppedFlow` undoes what it wrote.
`CheckDuplicateFlow` drops a file whose checksum an asset of the Library has (`asset_01` is unique over the assets outside the
Trash). The only report is `ImportStatusWsActor`: one line per processed file over `/import/status`, formatted from the raw
file name, shown for 3 s by the page's inline script, to the account that uploaded. Nothing is stored.

**Restart.** The queue is in memory and `Altitude` startup empties staging, so an upload accepted and not yet processed is
gone, and nothing says so.

**Server-side files.** `ImportAsset` and `StagingService.stageCopy` import a file from a path, used by tests only. Nothing
scans a directory. The only scheduled work is SQLite's hourly optimize (`actorSystem.scheduler` in `Altitude`).

**Folders.** `FolderService.add(name, parentId)` trims the name; `folder_02` makes it unique among its siblings under its name
key. `LibraryService.moveAssetsToFolder` is how an asset leaves Triage: it clears the flag and the Sequence and moves the stats.

## Decisions

| Concern | Decision |
|---|---|
| Words | **Import**: the stored record of one delivery, an upload or one scan of a Watch folder that found files. **Import file**: one file of it with its outcome. **Watch folder**: a server directory scanned for one Library, never a folder of the Library. "Keep folder structure" is the control's text |
| Tree source | The browser only: a folder picker (`webkitdirectory`) and a drop of folders on the Import page. No UI reads a server path except a Watch folder's |
| Structure | A choice per Import, off by default, so every file lands in Triage. An upload of chosen files offers "Skip Triage": a destination folder is chosen and every file is sorted into it. An upload of a folder tree, and a Watch folder, offer "Keep folder structure": a destination folder is chosen and the tree is mirrored under it. Either way the assets arrive sorted, with no Sequence, and the choice needs Organize as well as Import |
| Mirroring | Contents only: the uploaded top folder (or the watched path) maps onto the destination itself, its subfolders onto folders under it. A drop of several items on Folder upload is its own top: each dropped folder maps onto a folder of its name under the destination, a dropped file onto the destination itself. An existing folder of the same name key is reused, a recycled one included (it is restored, as `restoreRecycledAsset` restores an asset's folders); a missing one is created |
| Folder birth | A folder and its missing ancestors are created in the transaction that completes the first asset landing in or below it. Empty folders, folders of only unsupported files or only duplicates never appear. A file under a name the folder rule refuses fails with that reason |
| Duplicates | Skipped and reported, as now: the existing asset stays where it is, and the first of two copies in one Import wins. The Import file names where the existing asset is. A file whose only copy is in the Trash is not a duplicate and imports |
| Record | Every Import and every one of its files is stored, imported ones too. An imported Import file points at its asset until the asset is purged, and keeps its name after |
| Retention | Setting `import.history.days` (Live, default 90, 0 = forever); a daily prune deletes older Imports with their files. A Watch folder's memory of handled files is a table of its own, so pruning never causes a re-import |
| Upload transport | The page opens an Import, posts one file per request, three at a time, each with its relative path, then closes the Import. A file whose request fails is retried twice, then recorded failed; the rest go on. Cancel stops sending and closes the Import. Choosing the same folder again resumes: the files already in the Library are skipped as duplicates. Plain multi-file upload uses the same path |
| Ignored files | Never sent, read or listed, by the tree upload and the scan alike: a name starting with a dot (files and directories), `Thumbs.db`, `desktop.ini`, `@eaDir`, `#recycle`, `$RECYCLE.BIN`, `*.part`, `*.tmp`, `*.crdownload`, zero-byte files, and in a Watch folder symbolic links, which are never followed. One list, served to the page by the server |
| Live status | The ticker goes. The Imports in progress head the Import history, with a progress bar and running counts pushed over the WebSocket, to every account with Import in the Library. An Import expands to its files, filtered by outcome |
| Restart | At startup every Import file still pending becomes failed, "interrupted by a restart", and its Import is closed. Staged files are not resumed |
| Watch folder scope | An Admin adds, edits, pauses, scans, retries and removes; an account with Import sees the list read-only. Several per Library. No two Watch folders overlap (same path, or one inside the other, after resolving symbolic links) in any Library, and none overlaps the app's data directory |
| Watch folder target | Scanned recursively, with the same "Keep folder structure" choice and destination as an upload |
| Watch folder owner | The Admin who created it, or who last edited it, owns its assets. A deleted Admin's Watch folders pass to the deleting Admin with the assets; a deactivated or demoted one pauses them |
| Detection | Polling only: a scan at startup and then every `watch_folder.scan_interval` seconds (Live Setting, default 60), plus "Scan now". A file is taken once its size and modified time are the same in two consecutive scans |
| Memory | A handled file is remembered by relative path, size and modified time and not read again; a changed file is a new file. A remembered file absent from the folder is forgotten after the retention period (30 days when history is kept forever) |
| Retries | Unsupported type, undecodable image or video: final until the file changes. Any other failure: retried on later scans, three attempts in all, then parked. "Retry failed" clears both kinds |
| Deletion | Per Watch folder, off by default. Deleted: a file once its import completes, and a file that is a duplicate of an asset of the Library. Never touched: unsupported and failed files. Subdirectories left empty are removed, the watched path never. Switching it on asks for confirmation, naming the number of files in the folder now |
| Unavailable | A path that is missing, not a directory, unreadable, or not writable with deletion on makes the Watch folder "Unavailable: reason"; it is tried again each interval and logged at WARN on each change of state |
| Lost destination | A Watch folder whose destination is recycled or deleted pauses with that reason and resumes when it is restored or replaced; its files wait untouched. A missing mirrored subfolder is created again. Deleting such a folder warns in its confirmation. An upload in that state fails its remaining files |
| Page | The Import page is two columns: File upload, Folder upload and Watch folders in the left column, the Import history in a wider right column so live progress stays in view. On a narrow screen the columns stack in that order |
| Upload sections | Two sections, each a dashed drop zone with its button inside and its option below it, a two-way segmented control. **File upload**: "Drop files here", "Choose files", and "To Triage" / "Skip Triage"; Skip Triage shows the destination folder picker. **Folder upload**: "Drop a folder here", "Choose folder", and "To Triage" / "Keep folder structure"; Keep folder structure shows the destination folder picker and, under it, a one-line example of the mapping (source subfolder → Library path). Each zone applies its own option to whatever is dropped on it: a folder dropped on File upload is sent as loose files, loose files dropped on Folder upload are the tree's top level. One upload runs at a time: the section that started it shows its progress (files and bytes, Cancel), the other waits, and the Import is live in the history |
| Import title | A folder upload is titled by its top folder ("3 folders" for a drop of several), a scan by its Watch folder's name, an upload of files by its file count |
| Watch folders section | Cards in a grid, one per Watch folder: name, path, target, last scan, the imported and failed counts of its Imports in the history, and whether it deletes imported files. The status is a tinted chip with its reason beside it. "Scan now" is an inline button on the card; Pause / Resume, Retry failed, Edit and Remove are in a ⋯ context menu, as the folder tree's. "Add Watch folder" sits beside the section's heading |
| Watch folder dialog | Three fieldsets: Source (name, path), Destination ("Keep folder structure" and its destination), After import ("Delete imported files"). Switching deletion on reveals a red callout naming the number of files in the folder now, and Save reads "Save and delete imported files" |
| Import history | A vertical timeline, one node per Import, glowing while the Import is in progress; the Imports in progress are pinned under "In progress" above "Earlier". Each entry carries its title, who or what delivered it, when, and where it went. The progress bar is segmented by outcome (imported, already in the Library, unsupported, failed), with moving stripes while in progress. Counts are outcome-coloured chips, a zero count hidden. An expanded Import lists its Import files under outcome filter chips with counts |

## Design

**Tables** (both `all.sql` files).

- `import`: `repository_id`, `source` (`upload`, `watch_folder`), `name` (the uploaded folder's or the Watch folder's name,
  NULL for an upload of files), `watch_folder_id` (`ON DELETE SET NULL`), `account_id`
  (`ON DELETE SET NULL`), `dest_folder_id` (NULL = Triage), `closed_at`, and five counters (`pending`, `imported`,
  `duplicate`, `unsupported`, `failed`) kept by the write that sets a file's outcome. Index on `(repository_id, created_at)`.
- `import_file`: `import_id` (cascade), `repository_id`, `rel_path`, `outcome` (`pending`, `imported`, `duplicate`,
  `unsupported`, `failed`), `reason`, `asset_id` (`ON DELETE SET NULL`), `size_bytes`. Index on `(import_id, outcome)`.
- `watch_folder`: `repository_id` (cascade), `name`, `path`, `dest_folder_id`, `deletes_imported`, `is_paused`,
  `account_id`, `status_reason`, `last_scan_at`. Unique on `path`.
- `watch_folder_file`: `watch_folder_id` (cascade), `rel_path`, `size_bytes`, `modified_at`, `state` (`seen`, `handled`,
  `final`, `retry`, `parked`), `attempts`, `last_seen_at`. Unique on `(watch_folder_id, rel_path)`.

**The element.** `AssetWithData` carries an optional `ImportOrigin(importFileId, folderPath: Seq[String], source: Option[Path])`:
the Import file to settle, the folder names under the destination, and the Watch folder file to delete. `ImportAsset` and the
tests carry none, and behave as now.

**Completion.** `LibraryService.completeImport` stays the one transaction: for an asset with a destination it resolves the
folder path under it (`FolderService.ensurePath`: find by name key, restore, or add, under `advisoryLock` so two imports
creating one folder take turns), writes `folder_id`, clears `is_triaged`, skips the Sequence, counts the asset as sorted, and
sets the Import file to `imported` with its asset. A lost destination fails the completion, which is a drop.

**Settling.** A last stage, `SettleImportFileFlow`, after `DiscardDroppedFlow`: for a dropped asset it writes the outcome and
reason (`duplicate` with the existing asset, `unsupported`, else `failed`); for a Watch folder file it updates the memory row
and, with deletion on and the outcome `imported` or `duplicate`, deletes the source and its emptied parents. The delete runs
after the commit, and a delete that fails is logged at WARN and the file stays remembered as handled.

**Live status.** The socket is per Library (`/import/r/:repoId/status`), opened by an account with Import in it, and
`ImportStatusWsActor` keeps the sockets by Library and account. `ImportService` tells the actor after each commit that
changes an Import (opened, a file settled, closed), with the Import as committed. The actor keeps the latest state per
Import, pushes at most one message per Import per second and a last one at its close, and sends the Import's status (its
progress bar and count chips) rendered by the Twirl partial the history uses, addressed to the Import's element, which the
page's `hx-ws` element swaps in. A push for an Import the page does not show yet, an Import's close and a reconnect make
the page fetch the top of the history again.

**Scanning.** `WatchFolderService.scan(watchFolder)` runs on the import dispatcher, one scan per Watch folder at a time,
scheduled on the actor system. It walks the path, upserts memory rows (`seen` on first sight or change), and for each file
stable since the previous scan stages a copy (`stageCopy`) and offers it to the import queue under its backpressure, in path
order, under an Import opened for the scan when its first file is offered and closed when its last file settles. A file is
never moved out of a Watch folder: the source is the retry.

## Tasks

### A. Import records

1. **`import` and `import_file`.** The tables, `Import` and `ImportFile` models, DAOs, and `ImportService`: `open`,
   `addFile`, `settle(fileId, outcome, reason, assetId)` (the file's outcome and its Import's counters in one write
   transaction, closing the Import when it is marked complete and nothing is pending), `close`, `list`, `files(importId,
   outcome)`. Rationale: every later task writes to or reads from the record.
2. **Origin on the element, settling at the pipeline's end.** `ImportOrigin` on `AssetWithData`; `completeImport` settles an
   imported file in its transaction; `SettleImportFileFlow` settles a dropped one. Rationale: one place per outcome, and a
   record that agrees with what the pipeline committed.
3. **Startup and retention.** `App` startup fails the pending files and closes their Imports before the queues start; the
   `import.history.days` Setting and a daily prune on the actor system's scheduler. Rationale: no Import stays open forever,
   and the history is bounded.

### B. Upload by file, and folder trees

4. **Per-file upload routes.** `POST /import/r/:repoId/imports` opens an Import (optional destination folder, checked for
   Organize), `POST …/imports/:importId/files` takes one file with its relative path and offers it, `POST
   …/imports/:importId/close` closes it; the single multipart route, the cancel route and `uploadCancelRequest` go. The
   server refuses an ignored name and a path with `..` or an empty segment. Rationale: one upload path, a failure costs one
   file, and cancelling is closing.
5. **Sorted on arrival.** `FolderService.ensurePath` and the destination branch of `completeImport`. Rationale: the
   structure choice, with folders born only under an asset.
6. **Upload sections.** The page's two columns, and the File upload and Folder upload sections: each section's drop zone
   and picker (dropped directories are walked with `webkitGetAsEntry`), the "To Triage" / "Skip Triage" and "To Triage" /
   "Keep folder structure" segmented controls with the destination folder picker and the mapping example (shown to
   Organize holders), the ignore list applied before sending, three parallel requests with two retries each, and progress
   by files and bytes. A File upload sends each file under its bare name, so with Skip Triage every file lands in the
   destination itself. Rationale: the first goal's UI.

### C. Import history

7. **Import history, live.** A partial listing a Library's Imports as the timeline, the ones in progress first, then
   newest first and paged, each expanding to its files by outcome, an imported file linking to Asset detail; rendered through Twirl. `ImportStatusWsActor` pushes an Import's
   counters, throttled, to the Library's connected accounts instead of per-file lines, and the socket takes its account from
   the login; the ticker markup, its inline script and the per-file sink go. Rationale: the record made visible, one live
   display, and no file name formatted into HTML by hand.

### D. Watch folders

8. **`watch_folder` and its rules.** The table, model, DAO and `WatchFolderService` add / update / remove / pause / resume,
   with the path rules (absolute, an existing readable directory, writable with deletion on, no overlap with another Watch
   folder or the data directory, compared by real path) as `ValidationException`s. Removing one leaves its files and its
   Imports. Rationale: the configuration and its safety rules before anything reads a disk.
9. **Scan and memory.** `watch_folder_file`, `scan` as designed, the stability rule, the ignore list, the retry states and
   "Retry failed", the forgetting of absent files in the daily prune. Rationale: the second goal, with catch-up being the
   same scan.
10. **Schedule and status.** The startup scan after the prune and requeue, the `watch_folder.scan_interval` Setting,
    "Scan now", Unavailable and Paused with their reasons, the lost-destination pause, the owner rules on account change.
    Rationale: a Watch folder that says why it is not importing.
11. **Deletion.** `deletes_imported`, the source delete and empty-directory cleanup in `SettleImportFileFlow`, never above
    the watched path. Rationale: the third goal, after the commit that makes the file safe to lose.
12. **Watch folders section.** The cards with name, path, target, status, last scan and counts; Admin dialogs for Add and
    Edit, the deletion confirmation with the file count, Pause / Resume, Scan now, Retry failed, Remove; the folder delete
    confirmation's warning. Routes `@requireAdmin` for writes, Import for the list. Rationale: the Watch folder UI.

### E. Docs

13. **Guides.** `../altitude/AGENTS.md` (Import Pipeline: origin, settling, sorted arrival; a Watch folders section),
    `views/AGENTS.md` (the Import page's two columns), `docs/READINESS.md` (the ticker's XSS and the socket's login
    closed, the restart loss now reported, the Watch folder's path and deletion rules under data safety),
    `docs/test-coverage.md`. `CONTEXT.md` holds Import, Import file and Watch folder. Rationale: no drift.

## Tests

Integration, both engines, red first: an Import's counters under concurrent settles (PostgreSQL); a kept tree merging into
existing and recycled folders with no empty folder left by duplicates or unsupported files; two imports creating one folder
at once (PostgreSQL); a restart failing pending files; a scan that skips a file still growing, catches up on files present
before the first scan, does not re-read handled files, re-imports a changed one, retries three times then parks; deletion
removing imported and duplicate files and emptied subdirectories but no unsupported or failed file and never the path;
overlap and data-directory refusals; a symbolic link out of the path not followed; an unavailable path and a recycled
destination pausing without touching memory. Controller tests for the Permission on each route.
