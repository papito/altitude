# Curation: defects to fix, by priority

## Scope

Curation end to end: Sequence assignment at import (`SequenceService`, `SequenceRule`, the `AssetDao` statements), the
image measurements and Scene embeddings that feed it, `CurationRule` and `CurationService`, the Curation routes, the
modal (`alpine/components/curation.js`, its templates and CSS), the queue count, and Group by Sequence with every way
into Curation from the grid.

Each item says how it is known:

- **Reproduced**: a throwaway test showed the behaviour on this branch. The tests are not in the tree; each item names
  the test to write.
- **Read**: it follows from the code as written and was not run. The modal has no automated test, so every client item
  is Read.

Priorities:

- **P1**: recycles something the user did not choose, fails an import, or hides frames from the grid.
- **P2**: wrong state or misleading output with a workaround, or a cost that grows with the Library.
- **P3**: low impact, rare, or cosmetic.

No critical defect: no path loses a file for good. The trash holds everything a wrong recycle takes, though a recycle
drops album and Location membership and a restore does not add it back (item 13), so a wrong recycle is not fully undone.

## P1

### 1. A resolve acts on a set that changed since it was read

`CurationService.resolve` (`service/CurationService.scala:104-123`) recycles every ID of `recycleIds` that is live. It
does not check that a frame is still triaged or still in the Pick's Sequence, and it reads the Picks unlocked.

- A burst A, B, C is open in the modal. B is moved into a folder in another tab or by another account. Resolve with A as
  the Pick recycles B out of its folder, with its album and Location membership. Without Recycle the whole resolve is a 403.
  **Reproduced** (SQLite).
- On PostgreSQL, a grid recycle of the Pick that commits between `liveAmong` and `getLiveSequenceMembers` leaves every
  frame of the set in the trash and nothing kept, the outcome the "A Pick is no live asset" refusal exists to prevent.
  **Read**.

Fix: read the set's rows locked in one ID-ordered pass over `keepIds ++ recycleIds`, judge the Picks on those rows, and
for a Sequence's set recycle only frames still triaged. Test: `CurationServiceTests`, `burst(0, frames = 3)`, move the
second frame to a folder, resolve, assert it is not recycled.

### 2. A Bundle's recycling can leave one pane on the stage, which arms Enter to recycle every other frame

`showSet` (`alpine/components/curation.js:1013-1030`) gives a stage role whose frame is gone the server's opening frame
for that role, without comparing it with the other role. `recycleFrames` (`:1599-1620`) passes `currentView()` as it is
for a Bundle, where a stage frame's recycling goes through `viewWithout`.

- A set opens on Contenders C1 | C2. Space pins C2 and focus moves on. Arrow to a bundled frame, press R. The focused
  frame is gone and falls back to the rail's second frame, C2, which is also pinned. `keepIds` is now `[C2]`, the footer
  reads "Keep C2, recycle N", and Enter recycles every other frame, C1 included.
- The mirror case: the pinned frame is in the Bundle and the focused one is the rail's first.

It breaks the rule that with no Pick nothing is kept on a guess. **Read**, by two independent reads of the code.

Fix: in `showSet`, when a view's role falls back to the frame the other role holds, take the other opening frame; or
build the Bundle path's view as `viewWithout` does. Test: browser, check `pinnedId !== focusedId` after R.

### 3. "Person missing" fires at exactly half, so two halves bundle every frame

`CurationRule.poorFaces` (`util/CurationRule.scala:97-103`) tests `foundIn * 2 >= sequenceSize`.

- A two-frame Sequence where frame A holds Person P1 and frame B holds P2 (one face recognized as a new Unknown person,
  or a background face found in one frame). Each frame misses a Person found in half the Sequence. Both are Poor faces,
  there is no Contender, and R recycles the whole Sequence. The same holds for four frames split two and two.
- With one-sided detection in a two-frame Sequence, the frame without the face is always Poor faces.

**Reproduced** (unit). `CurationRuleTests` asserts the tie as intended but never gives the other half a Person of its own.

Fix: a strict majority (`foundIn * 2 > sequenceSize`), or at least two frames holding the Person.

### 4. A Person twice in every frame puts every frame in Poor faces

`CurationRule.poorFaces` (`:90-95`) compares each Face with the Person's best in the other frames; `blurry` (`:117-124`)
does the same for sharpness.

- Twins, or two people merged into one Person, with Faces of quality 22 and 15 in frames A and B. A's 15 is compared with
  B's 22 and B's 15 with A's 22. Both frames are Poor faces with no Contender.

**Reproduced** (unit). `CurationSet.faceRows` already shows a frame's better Face of a Person, so the strip and the rule
disagree. Fix: compare each frame's best Face of the Person with the other frames' best, for quality and sharpness.

### 5. Removing the cell that carries the cursor stops the grid from loading more

The next page's cursor lives only in `data-app-search-after` on the last loaded cell
(`search-results/infinite-scroll.js:21`). `removeAssetsFromGrid` (`assets/asset-actions.js:79-99`) removes any cell and
hands the cursor to none; only `page-window.js` `dropPage` puts a cursor back on a cell.

- Triage holds more than a page and its last loaded cell is below the fold. Curation recycles that frame. On close
  (`listeners/curation.js:156`) the cell leaves, no element carries a cursor, and scrolling loads nothing while the
  total still counts the rest. Under Group by Sequence the first pages are all Sequence frames, so the last cell is
  likely one Curation recycles.
- Without Curation: tick a group's checkbox, which selects its loaded cells, the last one included, then Recycle or Move.

**Read**. A cell the user clicks has been in view, so its page is already loaded; the two paths above remove a cell that
was never in view.

Fix: before a cell with a pending cursor leaves, hand the cursor to the previous remaining cell and observe it, as
`dropPage` does; or carry the cursor on a sentinel after the grid.

### 6. PostgreSQL: an import's `assign` deadlocks with a move or recycle of the burst's frames, and the file fails

`AssetDao.sequenceCandidatesStatement` (`dao/jdbc/AssetDao.scala:369-378`) locks the candidates `FOR UPDATE` with no
`ORDER BY`, and `repointSequences` (`:592-600`) writes rows outside them. Every library operation locks in ID order
(`lockAssets`, `:313-334`) and none takes `lockSequences`, so the advisory lock does not order them against `assign`.

- Two frames of a burst are in triage, the one captured first with the higher ID. A move or recycle of both (the grid,
  or Curation's Bundle recycling) runs while a third frame of the burst completes. PostgreSQL aborts one. When the
  import loses, its completion fails, the asset is discarded and the Import file fails with a bare `SQLException`.

**Reproduced** (PostgreSQL: one deadlock detected, the import failed). Nothing retries on SQLState `40P01`. The scaladoc
of `lockSequences` and the guide's **Locking** bullet say the advisory lock removes the deadlock risk; it does so among
Sequence writers only.

Fix: `ORDER BY id` on the dated candidates statement, lock a merge's members in ID order before repointing, and retry a
completion on `40P01`. Test: `make test-focused-psql`, a holder transaction on the lower ID, the move queued on it, then
the import.

## P2

### 7. PostgreSQL: a selection's resolve, and a reopen, deadlock with a move of the same frames

`resolve` locks the Picks' Sequence members in ID order, then `recycleAssets` locks the recycle IDs in a second ordered
pass (`CurationService.scala:112-123`). A selection's recycle IDs can lie outside the Picks' Sequences. `reopen` does
the same: the restore's locks, then `setSequence` on unlocked Picks (`:146-148`).

- A selection with a Pick in a Sequence and a frame to recycle outside it, that frame's ID the lower. A grid move of
  both locks the frame and waits on the Pick while the resolve holds the Pick and waits on the frame. The loser is a 500.

**Reproduced** (PostgreSQL: the resolve failed with "deadlock detected"). `liveSequenceMembersStatement`'s scaladoc says
"cannot deadlock", which holds for a Sequence's set alone. Fix: the single locking pass of item 1.

### 8. A reopen stamps whatever `sequenceOf` says, and restores any recycled ID

`CurationService.reopen` (`:140-148`) writes each `sequenceOf` value through `setSequence`, which tests only
`is_triaged` (`AssetDao.scala:581-590`).

- With no resolve before it, `{keepIds: [frame], recycleIds: [], sequenceOf: {frame: <any UUID>}}` moves a frame, or a
  triaged video, into another Sequence, another account's under Curate own included. **Reproduced** (SQLite).
- An honest Undo after a later import paired with the Pick alone puts the Pick back in the old Sequence and leaves the
  later frame a one-frame Sequence. **Read**.
- A resolve retried after its answer was lost succeeds with `sequenceOf: {}`; its Undo restores the frames and leaves the
  Picks out of the Sequence. **Read**.
- `reopen` restores every ID of `recycleIds`, frames in the trash before the resolve included, because `resolve` does not
  answer which frames it recycled.

Fix: stamp only still images whose `sequence_id` is NULL, with a Sequence some row still carries; have `resolve` answer
the IDs it recycled.

### 9. Undo of a recycled Pick brings the frame back without its Pick

The undo entry's view holds the Pick (`curation.js:1605-1613`), the re-read drops it (`:1030`), and Undo on the same set
merges `currentView()`, which no longer has it, with the stage roles alone (`:1661-1665`).

- Pick A and B, press Shift+1 on A by mistake, press Z. A is back unmarked, and Enter reads "Keep B, recycle N" with A
  among the recycled.

**Read**. Fix: on Undo, add the entry's Picks among the restored IDs back to the view.

### 10. Group by Sequence repeats or skips frames when a Sequence's first capture moves between pages

`afterSequence` (`dao/sql/search/SearchQueries.scala:574-585`) tells the anchor's own Sequence by ID, and every other by
its first capture over the matching live frames, which a recycle, a restore or a merge moves.

- S has frames at 10:00, 11:00 and 12:00; page 1 of two shows the first two. The 10:00 frame is recycled. Sequence T
  (10:30, 10:31) fills page 2, so the next cursor is anchored in T. Page 3 returns S's 11:00 frame again, then 12:00.
  **Reproduced** (SQLite).
- A Sequence not yet shown whose first capture moves before the anchor's key (an earlier frame restored, or an import
  merging it into a shown Sequence) is skipped whole. **Read**.

`SearchCursorTests` "Recycling the frame that set a Sequence's first capture repeats nothing…" uses a one-frame
overtaker, so its cursor returns to S. Fix: order groups by a key a recycle does not move (the first capture over the
Sequence's triaged frames, trash included), or drop a cell already in the grid as a page is appended.

### 11. After a resolve, a kept frame gets a second cell once the "No sequence" pages load

A Pick cleared from its Sequence keeps its cell under the old header (`listeners/curation.js:149-158`). It is now a "No
sequence" match, so a later page renders it again with the same DOM `id` (`views/htmx/results_grid_grouped.scala.html`).
`SearchCursorTests` accepts the repeated row. **Read**; the client half was not run. Fix: on close, remove the cleared
Picks' cells while "No sequence" is not fully loaded, or dedupe on append.

### 12. After a queue session the grid's total, group counts and Curate buttons are wrong for frames with no loaded cell

`removeAssetsFromGrid` skips an ID with no cell and does not count it, and `date-groups.js:20-52` decrements a header
only for loaded cells. The queue walks all of triage.

- The footer total drops only by the frames that had cells. A loaded header of a group that continues into unloaded
  pages keeps its count and can be left with no cells.
- A Sequence that fell under two live frames without being cleared keeps its Curate button, which opens the too-few state.
- Under Curate own the button is removed on `data-count`, all matching frames, not on the account's own count.

**Read**. Fix: on close, search again when any recycled ID had no cell, as the map layout does.

### 13. Recycling a Bundle or a stage frame drops album and Location membership with no note, and Undo reads as complete

`LibraryService.recycleAssets` (`:391-394`) takes a recycled asset out of every album and Location, and a restore does
not add it back. The footer note (`curation.js:577-593`) is worded for Resolve and is empty while there is no Pick, and
Undo reports "N assets restored". The server behaviour is as the guide states it; the gap is what the modal tells the
user. **Read**. Fix: `resolve` and the recycle answer the memberships removed and the restore re-adds them, or the
controls and the Undo report say what is lost.

### 14. The Loupe can mislead the judgement of sharpness

- **Stale pixels.** `focusFrame` (`curation.js:824-837`) does not refresh the Loupe, which is refreshed on `load` or
  `error` alone (`:656-670`), and `.pane.loading` hides only the `img` (`css/htmx/curation_set.css:239-241`). With the
  pointer resting on a face, an arrow key shows the new frame's name and Histogram over the old frame's pixels until
  the image loads.
- **One rung per wheel event.** `steppedLevel` (`common/loupe.js:123-129`) reads the sign of `deltaY` alone, so a
  trackpad swipe or a free-spinning wheel jumps to an end of the ladder. The wheel is the level's only control.
- **"100%" is one original pixel per CSS pixel.** `measureLens` (`loupe.js:188-209`) ignores `devicePixelRatio`. At
  2× the lens upscales with smoothing, and under 1.25× or 1.5× scaling no rung is pixel-exact, so a sharp frame looks
  soft at "100%".

**Read**. Fix: hide the lens while its pane loads; accumulate `deltaY` to a rung per about 100 px; size a level in
device pixels and snap the position to them.

### 15. A date with no time, and a modification time, count as the camera's clock

`CaptureDateResolver` (`util/CaptureDateResolver.scala:64-66`) tags `photoshop:DateCreated` and `xmp:ModifyDate` as
`XmpCreateDate`, `WallClockParser.parse` turns a date-only value into midnight, and `SequenceRule.CameraClockSources`
lists `XmpCreateDate`.

- Images with no EXIF time and a date-only XMP date all read 00:00:00 of that day, a gap of 0: any two of one size and
  camera that look alike form a Sequence, however many hours apart. A batch dated by `xmp:ModifyDate` is spaced by its
  export.

**Read**; what the extractor emits for these keys was not checked on real files. Fix: keep whether the source carried a
time, or give the two their own source outside `CameraClockSources`.

### 16. Blurry's "similar size" compares raw pixel areas, while Face sharpness is measured at the 1280 box

`CurationRule.isSimilarSize` (`:188-190`) against `ImageMeasurement` (`util/ImageMeasurement.scala:68-69, 144-149`). In
a selection that mixes resolutions (a 45 MP body with a 12 MP phone, an original with a downsized export), a face of
the same pixel width is about 4× apart in area at the box, so the lower-resolution frame reads falsely Blurry; the same
framing at two resolutions is never compared. A Sequence is safe, since its frames share a size. **Read**. Fix: scale
each area by the frame's fit, `min(1, 1280 / max(width, height))²`.

### 17. Costs that grow with the Library

- **Dated candidates.** `getSequenceCandidates` materializes and row-locks every triaged dated frame within the gap,
  any size or camera, with both embeddings (`SequenceService.scala:47-48`, `AssetDao.scala:336-337`), and
  `sequence.max_gap_ms` has no upper bound (`models/Settings.scala:321-328`). A gap raised for an event shoot makes an
  import quadratic in the frames of a window and holds their rows against user operations.
- **Undated cap.** `UNDATED_CANDIDATES_PER_CORE` scales with the cores, but the completion stage is serial. On 16 cores
  one undated import can stream 80,000 rows inside the completion transaction, which holds the Library's Sequence lock
  on PostgreSQL and the instance's one write connection on SQLite.
- **`asset_sequence` has no `repository_id`** (`migrations/postgres/all.sql:270`, `sqlite/all.sql:253`). The queue, its
  count (read after every asset mutation in every view, `asset-actions.js:48`) and each sequenced page of Group by
  Sequence read every Library's sequenced rows and filter after the fetch.
- **Every set is rendered twice.** `preloadNextSet` (`curation.js:1334-1356`) fetches the next row's whole fragment to
  read two IDs, after every read, a Bundle recycling's and an Undo's included, and its image preloads are never
  cancelled.

**Read**; no plan or timing was measured. Fix: bound the gap and prefilter size in SQL, streaming as the undated path
does; a fixed undated cap; index `(repository_id, sequence_id) WHERE sequence_id IS NOT NULL`; put the opening IDs on
the queue rows.

## P3

18. **An unmeasured frame is "The only frame with no problem".** `CurationSet.soleContender`
    (`models/CurationSet.scala:90-92`) does not require the frame to be measured, so a frame whose measurement failed
    shows "Not measured" beside the rule's one suggestion. **Reproduced** (unit).
19. **A notice says "Every frame:" over readings that hold for some.** `noticeOf` (`CurationRule.scala:171-177`) falls
    back to every test some frame fails, each at its mildest value: 10% crushed in one frame and luminance 10% in
    another read "Every frame: 10% crushed shadows, Luminance 10%". The notice names no kind and no limit. **Read**.
20. **Zero is accepted for the `>=` thresholds.** `curation.face_quality_gap`, `luminance_gap`, `clipped_fraction` and
    `crushed_fraction` at 0 flag every frame where a reader expects the test off (`Settings.scala:367-410`), and nothing
    keeps the dark limit under the bright one. **Read**.
21. **Reason wording.** Whole-percent rounding gives "Luminance 15%, under 15%" (`curation_set.scala.html:27, 39-50`);
    sharpness under 1 reads "0 against 0"; the `f` interpolator formats in the JVM's default locale. **Read**.
22. **No cap on the ID lists of `resolve` and `reopen`.** `requireKnown` and `liveAmong` bind one parameter per ID
    (`CurationService.scala:163, 171`), so a body of tens of thousands of IDs is a 500. **Read**.
23. **The Detail panel's Curation row ignores whose frame is open.** `setSizeOf` (`CurationService.scala:46-53`) counts
    the viewer's frames of the Sequence without testing the asset's owner: under Curate own, "View set of N" on another
    account's frame opens a set without it. **Read**.
24. **A tampered cursor is a 500.** `LocalDateTime.parse` of the cursor's key (`SearchQueries.scala:578`, also `:252`
    and `:413`) throws past `SearchResultsController`'s catch of `SearchCursorException`. **Read**.
25. **The asset API's recycle and restore parse the body outside `answeringJson`** (`routes/api/AssetController.scala:33-35,
    46-48`), so a malformed body is a 500, and the IDs are not checked as UUIDs. **Read**.
26. **A double click lands on what replaced the control.** `isBusy` clears as the new fragment shows
    (`curation.js:1465-1483`), so the second click of a fast double click recycles the next Bundle, dismisses the next
    Sequence (Keep all) or recycles the frame that moved in. Keys are guarded by `!event.repeat`; clicks are not. **Read**.
27. **Try again can loop.** After a failed move, a retry that meets a 204 with nothing further keeps the unread state on
    a done row; with one open row left only Exit recovers (`curation.js:1145, 1275-1278, 1436-1441`). **Read**.
28. **A request that never settles freezes Curation silently.** `isBusy` is closure state, no control is disabled and
    `http/client.js` sets no timeout; `curationClosed` fires from `exclusively`'s `finally`, so a close during a hung
    write never cleans the grid. **Read**.
29. **Letter keys match `event.key`**, so A, R, H, Q, N, P and Z are dead on a non-Latin layout, where the digits use
    `event.code` (`curation.js:1716, 1731-1746`). Shift is not excluded, so Shift+Enter resolves beside the Shift+1
    recycle chord. **Read**.
30. **Accessibility.** Sequence rail rows are `<li>` with a delegated click, no role or tabindex
    (`curation_queue.scala.html:14`); the rail's Pinned and Keep marks are `aria-label` on a bare `<i>`
    (`curation_set.scala.html:77-78, 177`); the focused frame is a border colour with no `aria-current`; the Loupe has
    no keyboard path. **Read**.
31. **Native and browser memory.** `ImageUtil.decode` never releases its `MatOfByte` (`util/ImageUtil.scala:39`), three
    decodes per image; DINOv2's center crop resizes the whole image before cropping (`util/SceneEmbedder.scala:181-199`),
    hundreds of MB for an extreme strip, and past about 2560:1 the measurement throws, leaving no hash;
    `clippingReads` keeps a byte per sampled pixel for every frame viewed (`curation.js:676-693`). **Read**.
32. **"Same camera" is the same model string** (`SequenceRule.scala:70-71`): two bodies of one model at one scene form
    one Sequence, across owners too. **Read**.
33. **Small inaccuracies.** The report's "Kept N, recycled M" comes from the request (`curation.js:1559`); rail rows
    keep their count and cover after recycles; each Histogram strip is scaled to its own tallest bin, so two panes do
    not share a scale; `preloadOnly` can cancel the preload of the frame just focused (`:357-369`); `onImageSettled`
    records the `src` at event time (`:656-659`); the "another dialog took over" guard reads `activeHost` a tick early
    (`listeners/curation.js:162`). **Read**.

## Where the docs say otherwise

- `altitude/AGENTS.md`, **Locking**, and the scaladocs of `lockSequences` and `liveSequenceMembersStatement`: no
  deadlock. Items 6 and 7.
- `altitude/AGENTS.md`, **The rule**: a notice's "words hold for every frame" (item 19); Blurry's "box areas within 2×"
  does not say the areas are raw pixels (item 16).
- `altitude/AGENTS.md`, **Group by Sequence**, and `groupedBySequence`'s scaladoc: the cost is "the frames in a
  Sequence … not the library" (item 17). `afterSequence`'s scaladoc: nothing of a shown Sequence repeats (item 10).
- `views/AGENTS.md`, **Curation**: "every set opens on two frames and Enter keeps nothing until there is a Pick" (item
  2); "a key pressed during an action does nothing" holds for actions and arrows, while 1, 2, Space, H and Q still act;
  "the frames still recycled leave the grid, with the group counts and the total" holds for loaded cells (item 12);
  Undo of a recycling "keeps the view" (item 9); kept frames "stay under their old header until the next search" omits
  the second cell (item 11). **Infinite scroll** does not say the cursor dies with its cell (item 5).
- `docs/test-coverage.md`, **Curation**: "a resolve whose recycle list holds another repository's assets, or unknown
  IDs, passes over them … a Pick of another repository is a JSON 400". `requireKnown` makes both a 404, as
  `RouteTableTests` and `CurationServiceTests` assert.
- `curation.js:51` describes the Loupe's levels as original pixels per screen pixel (item 14).
- `SequenceRule.CameraClockSources` and the glossary's Sequence: "the camera's own clock" (item 15).
- `Altitude.scala:321`: the undated cap is "per core, so the machine bounds the read", while the read is single-threaded.

## Sound

- **Permissions and scoping.** No escalation: recycle and restore go through `requireRecycle` as the grid's do, a
  foreign or unknown ID is a 404 before any 403, and every Sequence and Curation statement carries `repository_id`.
- **The SQL prefilters agree with `SequenceRule`** (sources, media type, hash, triage, trash included, the day list
  across midnight), and a failed completion rolls back every stamp.
- **`CurationRule` has no division by zero or NaN**, its thresholds match the guide's sides, and its order agrees with
  `sequenceFramesStatement`.
- **Measurement.** Inputs are 8-bit with one or three channels, the clipped, crushed and Histogram math is right, storage
  is all or none, and the embedder's lock and close are sound.
- **The modal's requests.** Stale responses are dropped, a second Resolve or Undo is refused while one runs, the Undo
  payload is what the resolve sent and got back, listeners are released on close, and no user value reaches markup,
  a selector or a URL unescaped.
- **Group by Sequence** holds on the first page, inside a group, into "No sequence", at exact page ends, for undated
  Sequences and for ties on first capture, on both engines.
