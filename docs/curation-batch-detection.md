# Curation

Curation is the Triage feature that compares the frames of a burst, or of any selection of still images, and keeps the
best of them. The user opens it on the queue of Sequences, on one Sequence, or on a selection; the server sorts the
frames into Bundles by what is wrong with them; the user picks the frames to keep, and the rest go to the trash.

This document has three parts. The first two describe the feature as the `batch-curation` branch implements it: the
backend logic (measurement at import, Sequence assignment, the rule, the writes and the routes) and the UX (the ways
in, the modal, its keys, the walk of the queue, Undo and what the grid does afterwards). The third keeps the research
notes the feature grew out of, trimmed to what was considered and not built, and ends with what is not there yet. The
terms are the glossary's (`CONTEXT.md`): Sequence, Image quality, Face sharpness, Curation set, Pick, Pinned frame,
Bundle, Contender, Too close to call. The binding rules for changing any of it are in `altitude/AGENTS.md`
(**Sequences and Image quality**) and `altitude/views/AGENTS.md` (**Curation**); this is the narrative.

## How the pieces fit

```
import                                  triage                                   Curation modal
──────────────────────────────────────  ───────────────────────────────────────  ─────────────────────────────────
MeasureImageFlow                        asset.sequence_id                        CurationService.sequenceSet /
  Image quality (5 metrics + score)       shared by the frames of one scene        selectionSet
  perceptual hash                         (no table, no row of its own)            → frames + Faces + memberships
  Face sharpness per Face                                                          → CurationRule.assess
  Scene embeddings (DINOv2, CLIP)
        │                               Group by Sequence (grid)                       Bundles, chips, notices,
        ▼                               "Curate N sequences" (toolbar)                 Contenders
completeImport                          Curate (group header)                    resolve / reopen
  stores metrics, hash, embeddings      Curate (batch ops)                         keep Picks, recycle the rest,
  SequenceService.assign                                                           clear or keep the Sequence
```

Nothing the rule decides is stored. Every read of a set runs the rule again on the metrics stored at import and on the
current Settings, so a changed threshold applies from the next read without re-importing anything.

---

# Part 1: Backend logic

## 1. Measurement at import

Every still image is measured once, in the import pipeline's `MeasureImageFlow` ("Image measurement"), which runs after
face detection and before face recognition so that a Face's sharpness is written in the same insert as the Face. The
stage is pure over a decoded `Mat` (`core/util/ImageMeasurement.scala`) and touches no database; `completeImport`
stores the results in the completion transaction together with the completion flag, so an asset is never complete
without its metrics.

What is measured, on the image fitted to a 1280 px box and converted to gray:

| Stored on `asset` | Meaning |
|---|---|
| `sharpness` | Variance of the Laplacian over the whole frame. Always the whole frame, so two images compare on it whatever faces each has. |
| `luminance` | Mean gray over 255, 0 to 1. |
| `clipped_highlights` | Fraction of pixels at 250 or above. |
| `crushed_shadows` | Fraction of pixels at 5 or below. |
| `image_quality` | A 0 to 100 score from sharpness (over the union of the face boxes when they are large enough, else the whole frame) and the exposure penalties. **Curation never reads it**: its sharpness region varies per image, so two scores do not compare. |
| `perceptual_hash` | OpenCV's pHash (DCT of a 32 × 32 downscale), 64 bits, held by `PerceptualHash` and compared by Hamming distance. |
| `dinov2_embedding` | The DINOv2 ViT-B/14 Scene embedding: the pooled CLS token, 768 floats, L2-normalized (`vector(768)` on PostgreSQL, a BLOB of float32 on SQLite). |
| `clip_embedding` | The CLIP ViT-B/16 Scene embedding: the projected image embedding, 512 floats, L2-normalized (`vector(512)`, or a BLOB). |

And on each `face`: `sharpness`, the same Laplacian variance inside the face's box, or NULL under 2500 px² at the
fitted scale. Face sharpness falls as a face fills more of the frame, so it only compares between Faces of one Person
at a similar size, never across Persons and never as an absolute threshold.

The two Scene embeddings are not measurements of the gray, fitted image: `SceneEmbedder` (`core/util`) takes the same
upright decode in color, fits its short side to 224 (`INTER_AREA` when shrinking, `INTER_CUBIC` when enlarging an
image under that size: a transform of this project's own, not the checkpoints' reference one, which the similarity
thresholds are calibrated to), center-crops the square, converts it to RGB and runs it through each Scene model's ONNX
Runtime session (one per model in the process, opened when `AssetService` is built, so a missing model file fails the
start, never an import). Two embeddings of one model compare by cosine similarity, 1 for the same picture. They are
selected by one statement only, the Sequence candidates' (`AssetDao.candidateColumns`), and transferred by no other
read: every other read's column list comes from `AssetRow`, which leaves them out.

All of these are NULL together for a Video, an animated image, or a still image the measurement could not decode, and
an embedding alone for a still image its model could not embed. Measurement is the one stage that swallows its own
failure: a decode or measurement error is logged at WARN and the asset goes on unmeasured, and a model's failed pass
leaves its embedding NULL and the other model's stands. NULL is a state every later reader handles (an unmeasured
frame is a Contender with a note; a frame with no embedding of the chosen model is compared by its hash).

The box size and the score's constants are code, calibrated on the test fixtures, not Settings: changing them means
measuring the library again.

## 2. Sequences

A **Sequence** is still images in triage that the import found shot as one scene: within a minute of each other on
the same camera, looking alike by their Scene embeddings. It has no table. It is the `asset.sequence_id` (`CHAR(36)`, nullable, partial index `asset_sequence`) its
members share, so it exists exactly while some asset carries it and there is no row to lock, date or delete.

### The rule (`SequenceRule`, pure)

An asset is **eligible** when it is a still image in triage with a capture time from the camera's own clock (EXIF
original, EXIF digitized or XMP create date, each carrying a fraction of a second; a time from a file name, a container
or GPS cannot space the frames of a burst), the camera's model, and a hash.

Two eligible assets **match** when they were captured at most `sequence.max_gap_ms` apart (a minute by default), on
the same device model, at the same width and height, and **look alike**:

- when both carry the embedding of the Scene model the Setting `sequence.embedding_model` names, their cosine
  similarity is at least that model's threshold (`sequence.dinov2.min_similarity` or `sequence.clip.min_similarity`);
- otherwise, their hashes are at most `sequence.max_hash_distance` bits apart.

The similarity replaces the hash test rather than joining it: the hash at any usable distance rejects a reframed or
zoomed shot of the scene, which the embedding holds under. Each model has its own threshold since their cosines live
on different scales (CLIP's are compressed toward 1). The size test stays: a portrait and a landscape frame of one
scene can embed alike and are not one Sequence. On the synthesized burst the frames measure 0.98 and up by DINOv2 and
0.97 and up by CLIP, and a different picture about 0.26 and 0.62.

### Assignment (`SequenceService.assign`)

It runs inside the completion transaction, one asset of a repository at a time in upload order, so earlier uploads of
the burst are completed candidates already. It takes the Sequence writers' lock first (see **Locking** below), reads
the candidates with one indexed statement (the repository's completed, triaged still images captured within the gap,
in the trash or not but not purge-pending, the one read that carries the Scene embeddings), applies the rule under the
Settings as read once per asset, and then:

- no match: no Sequence, since a lone image is never one;
- matches with no Sequence: a new ID for the asset and every match;
- matches in one Sequence: the asset joins it;
- matches in several Sequences: a burst is transitive, so they merge into the one with the most members (ties to the
  earliest first capture, then the ID), the others' members repointed in one update.

`setSequence` writes only rows still in triage, so a frame a concurrent move sorted meanwhile stays out.

### What changes membership

| Operation | Effect |
|---|---|
| Move out of triage (also restore into a folder) | `sequence_id` goes NULL with the triage flag. A sorted asset never carries a Sequence. |
| Recycle from triage | Nothing. The frame keeps its flag and its Sequence, so the next frame of its burst imported later still joins it, and a restore brings the pair back together. |
| Restore | Nothing. |
| Curation resolve (also Keep all) | Each Pick's Sequence is decided on its own: when the set holds **all** of that Sequence's frames still out of the trash, the Picks leave it (`clearSequence`); otherwise they stay, to be compared later with the frames the set did not hold. Recycled frames keep it. |
| Curation reopen (Undo) | The recycled frames come back and the Picks go back into the Sequences resolve cleared. A Pick moved into a folder since stays sorted. |
| Purge, discard, repository delete | Nothing beyond the row's delete. |

A cleared Pick stays a candidate of assignment, so a later frame of its burst forms a new Sequence with it.

### Locking

Assignment, resolve and reopen each take `AssetDao.lockSequences()` first: an advisory transaction lock keyed by the
repository on PostgreSQL (SQLite's single write connection already serializes writers). Row locks cannot do this. A
write transaction is READ COMMITTED, and a frame completing is in no row a resolve's `FOR UPDATE` read finds until it
commits, however long the statement waits; and a merge repoints members outside its candidates while a selection's
resolve recycles frames outside its Picks' Sequences, so neither can lock all its rows in one ordered pass. The
advisory lock makes the writers of a repository's Sequences take turns instead.

## 3. Curation sets (`CurationService`)

Curation adds no table either. Every set is read in one read-only transaction and handed to the rule.

| Read | What it returns |
|---|---|
| `queue()` | The repository's Sequences with at least 2 frames completed, out of the trash and not purge-pending, oldest first capture first, then by ID. A lone frame never enters it; recycling can take a Sequence out or reorder it; a restore can bring it back. |
| `countQueue()` | How many, read from the database. The toolbar reads this after every asset mutation, so it costs the rows with a Sequence (`asset_sequence`), not the library. |
| `sequenceSet(id)` | A Sequence's frames still out of the trash, whatever a search shows of them, as a **window**: the first 100 in capture order (a frame with no capture time last). Sequences chain transitively, so a static time-lapse can be one Sequence of thousands of frames; when the window is full the set says how many are held back. `None` under 2 frames. |
| `selectionSet(ids)` | The still images among the IDs, in the repository, completed and not recycled. A selection may hold part of a Sequence, several Sequences, or frames of none. `None` under 2 frames. The cap of 100 IDs is the route's. |

Each frame of a set carries its asset, its Faces (hidden and bad-match people left out) and whether it is in any
album or Location, which recycling would take it out of. The rule gets the frames and the current Settings snapshot.

A window is a set that holds part of a Sequence, exactly as a selection can. Resolve decides by the whole membership,
so on a window the Picks stay in the Sequence, the Sequence stays in the queue, only the window's other frames are
recycled, and Keep all changes nothing. The next read's window starts with those Picks and goes on past the frames
recycled.

## 4. The rule (`CurationRule.assess`, pure)

The rule sorts a set into **Bundles**, each the frames whose most serious problem is the same, plus **Contenders**, the
frames in no Bundle. It suggests nothing beyond that: it never ranks frames, never reads the score, and marks only a
**sole Contender** (the one frame with no problem when others have one). A set with no Bundle is **Too close to call**.

A frame is tested for four problems, in this order of seriousness:

| Bundle | A frame is in it when |
|---|---|
| **Poor faces** | One of its Faces' quality is at least `curation.face_quality_gap` below the same Person's best in the set's *other* frames. Or, among the frames of its Sequence in the set, a Person found in at least half of them has no Face in this one. |
| **Blurry** | A Person it shares with another frame, at a similar size (box areas within 2×), has Face sharpness under `curation.blur_ratio` of that Person's best at that size. A frame with no such Person, in a Sequence, is compared on whole-frame sharpness against the sharpest other frame of its Sequence with the same ratio. A frame with neither is never Blurry. |
| **Too dark** | `crushed_shadows` ≥ `curation.crushed_fraction`, or `luminance` < `curation.dark_luminance`. Or, in a Sequence, its luminance is on the dark side of the target (0.45) and at least `curation.luminance_gap` farther from it than the Sequence's frame nearest the target. |
| **Too bright** | `clipped_highlights` ≥ `curation.clipped_fraction`, or `luminance` > `curation.bright_luminance`. Or the same gap on the bright side. |

Principles behind the table:

- **Compare only what is comparable.** Sharpness and exposure across different scenes mostly reflect content, so the
  whole-frame and luminance comparisons run only among frames of one Sequence. The same-Person tests run anywhere in
  the set, since a Person is the same Person in any frame. Face sharpness and Face quality never compare across
  Persons.
- **Relative to the best, not absolute.** Lighting shifts every absolute number, so a frame is measured against the
  best frame of its Sequence or the best Face of its Person, by a ratio or a gap that is a Setting. The absolute
  exposure thresholds exist only for a frame that is bad whatever its neighbours look like.
- **The worst face dominates, and the cast must be whole.** A group shot with one poor face is a poor group shot, and
  a frame where someone the burst shows in half its frames is missing lost them to a turn or a blink. Both are tests
  of the Poor faces Bundle, not terms of a score.
- **A Person's best is taken from the other frames.** A Person twice in one frame (twins) is nothing a Pick can tell
  apart.
- **One Bundle per frame, the rest as chips.** A frame sits under its most serious problem, with that problem's
  reasons; its other problems are chips on the frame.
- **A problem of every frame is a notice, not a Bundle.** An absolute Too dark or Too bright that holds for every frame
  cannot be fixed by picking, so it becomes a `CurationNotice` on the set ("Every frame: 8% blown highlights"), worded
  at its mildest value so it holds for all of them. The relative tests still bundle. An unmeasured frame stops a notice.
- **Reasons are data.** Each `CurationReason` says what is wrong, how much, and compared to what (`FaceQuality`,
  `PersonMissing`, `FaceSharpness`, `FrameSharpness`, `Exposure`, `LuminanceGap`); the template words them, so the user
  sees why a frame is where it is and can overrule it.
- **An unmeasured frame is a Contender** with a note, and its Faces still count for the other frames.

The model (`models/CurationSet.scala`) also derives what the view lays out by: the rail's order (Contenders, then each
Bundle in the order above, frames in capture order), the opening frames (the rail's first two frames, the first pinned and
the second focused: two Contenders, a sole Contender beside the first frame of a Bundle, or the first two frames of the
Bundles), and the face strip's rows
(Persons in the most frames first, the better Face where a Person is twice in a frame).

## 5. Outcomes

Two writes, each one transaction under the Sequence writers' lock:

**`resolve(keepIds, recycleIds)`** keeps the Picks, recycles the others through `LibraryService.recycleAssets` (so the
stats are counted as any recycle), and clears the Picks out of each Sequence the set held in full. It refuses an empty
`keepIds`, an overlap between the lists, and a Pick that is no live asset of the repository (which would recycle the set
with nothing kept). It answers with each Pick that left its Sequence and that Sequence, which Undo needs. Keep all is
`resolve` with nothing to recycle: on a whole Sequence it dismisses a wrongly grouped burst; on part of one it changes
nothing.

**`reopen(keepIds, recycleIds, sequenceOf)`** is the Undo: it restores the recycled frames through
`restoreRecycledAssets` (a frame whose content was imported again stays in the trash as a duplicate; an unknown ID is a
`NotFoundException` that changes nothing) and stamps the Picks back into the Sequences `resolve` answered with. It
refuses a Sequence given for a frame that is no Pick.

**Recycling a Bundle is not a call of this service.** The client uses the grid's own recycle endpoint, then reads the
set again so the Bundles are recomputed against the frames left, and undoes it through the grid's restore endpoint.
Skip Sequence reaches no server.

Two side effects of recycling reach the user: a recycled frame leaves its albums and Locations and a restore does not
add it back (the set's footer says so with a count), and its Faces leave its Person's face count, so an unnamed Person
known mostly from a burst can fall under the Matched line.

## 6. Settings

All Live, in the Triage section of the Settings screen, since they are calibrated on real files and the defaults are
starting points:

| Key | Default | What it tunes |
|---|---|---|
| `sequence.max_gap_ms` | 60000 | Sequence assignment: the most two frames may be apart |
| `sequence.embedding_model` | `dinov2` | Sequence assignment: the Scene model whose embeddings the rule compares, DINOv2 or CLIP on the screen's `<select>`, stored as `dinov2` or `clip` |
| `sequence.dinov2.min_similarity` | 0.80 | Sequence assignment: the least DINOv2 cosine similarity to look alike, -1 to 1 |
| `sequence.clip.min_similarity` | 0.90 | Sequence assignment: the least CLIP cosine similarity to look alike, -1 to 1 |
| `sequence.max_hash_distance` | 10 | Sequence assignment: the most bits two hashes may differ by, 0 to 64; decides only a frame with no embedding of the chosen model |
| `curation.blur_ratio` | 0.5 | Blurry: fraction of the best sharpness |
| `curation.face_quality_gap` | 2.0 | Poor faces: distance below the Person's best quality |
| `curation.clipped_fraction` | 0.05 | Too bright: clipped highlights |
| `curation.crushed_fraction` | 0.05 | Too dark: crushed shadows |
| `curation.dark_luminance` | 0.15 | Too dark: mean brightness under this |
| `curation.bright_luminance` | 0.75 | Too bright: mean brightness over this |
| `curation.luminance_gap` | 0.1 | Too dark / Too bright within a Sequence: distance from the target beyond the nearest frame's |

Code constants (`Const.Curation`): `MIN_FRAMES` 2 (under it there is no set and a Sequence is not in the queue),
`MAX_SELECTION` 100 (the most IDs a selection posts), `MAX_SEQUENCE_FRAMES` 100 (a Sequence's window).

## 7. Routes

Every route requires a login; a repository the user does not own is a 404.

| Route | Answers |
|---|---|
| `GET /htmx/curation/r/:repoId` | The modal's shell (`htmx/curation`) |
| `GET /htmx/curation/r/:repoId/sequence/:sequenceId` | The set's fragment (`htmx/curation_set`), or 204 under two frames so a queue's Sequence gone meanwhile is passed over silently. A non-UUID is a plain-text 400. |
| `POST /htmx/curation/r/:repoId/selection` | The set's fragment for `assetIds` (comma-separated, in a JSON body), or the fragment saying too few frames are left. Over 100 IDs or no valid selection is a plain-text 400. |
| `GET /api/curation/r/:repoId/queue` | `{sequenceIds}` in the queue's order |
| `GET /api/curation/r/:repoId/count` | `{count}` |
| `POST /api/curation/r/:repoId/resolve` | Takes `{keepIds, recycleIds}`, answers `{sequenceOf}`: each Pick that left its Sequence |
| `POST /api/curation/r/:repoId/reopen` | Takes `{keepIds, recycleIds, sequenceOf}`, answers `{restored, duplicates}` as the restore endpoint does |

On the writes, a refusal or a malformed body (not a JSON object, a list missing or holding anything but UUIDs) is a
JSON 400 and an unknown asset a JSON 404. Every ID is validated as a UUID because each reaches a statement, and a
Sequence's ID is written to a `CHAR(36)` column.

## 8. Group by Sequence

The grid's Group choice menu gains **Sequence**, in the Triage view only, since only a frame in triage carries one.
Groups are the Sequences by the first capture of their matching frames, with a trailing "No sequence" group, and the
order is fixed (no direction). Outside Triage the server treats `groupBy=sequence` as no grouping rather than a 400,
and the search store keeps the choice, so returning to Triage groups by Sequence again. The cursor continues a Sequence
group by its ID alone, since its first capture moves when the frame that set it leaves.

## 9. Tests

- `ImageMeasurementTests`, `SceneEmbedderTests`, `SequenceRuleTests`, `CurationRuleTests` (unit): orderings rather
  than exact values for the metrics and the embeddings; every rule clause in and out, on both sides of its threshold,
  the likeness by each Scene model's threshold and by the hash where an embedding is missing.
- `SequenceServiceTests`, `CurationServiceTests` (both engines, on a burst synthesized from one fixture by shifting and
  brightening each frame): assignment, by embedding and by the hash, the model switch, merges, membership through
  every operation, the queue, the window, each outcome and its Undo, and a frame completing during a resolve
  (`contend`, red on PostgreSQL without the advisory lock); `AssetImportServiceTests` the embeddings stored with the
  completion and read back exactly on both engines, and no general read carrying them.
- `CurationControllerTests`, `CurationActionControllerTests`, `SearchResultsControllerTests`: the routes, the
  fragment's words, the Curate buttons in their templates, and Group by Sequence.
- The modal itself has no automated test; see **Curation** in `docs/test-coverage.md` for the gaps.

---

# Part 2: UX

## 1. Where Curation shows up

Curation lives in Triage. It has no page or URL of its own and adds no nav entry: it is a modal that fills the window
over the nav, the explorer and the grid, so the nav keeps marking the view it opened from and a reload shows the grid.

Three ways in, all opening the same modal:

| Way in | Where | Opens on |
|---|---|---|
| **Curate 3 sequences** | The right edge of Triage's results toolbar. Hidden until the count is read, and gone at zero. | The whole queue, whatever the search shows. |
| **Curate** on a group header | Each Sequence's header when the grid is grouped by Sequence and the Sequence has two frames or more. | That one Sequence: all its frames still out of the trash, even under a search showing only some. |
| **Curate** in the batch ops | The selection's action bar (toolbar and footer copies) while two or more images are selected. | The selection. Over 100 images the snackbar says so and nothing opens; the server refuses it too, but only the client says why. |

The group header's Curate button goes away on the client when the group's count falls under two or when Curation
clears that Sequence.

## 2. The modal

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│ Curation                                   3 sequences left        [Undo Z] [Exit Esc]│
├──────────────────┬──────────────────────────────────────────────────────────────────┤
│ Showing the first│  ┌───────────────────────────┐ ┌───────────────────────────┐    │
│ 100 of 2,000 …   │  │ Keep  Blurry              │ │ Too dark                  │    │
│ Too close to call│  │                           │ │                           │    │
│ Every frame: 8% …│  │      pinned frame         │ │      focused frame        │    │
│                  │  │                           │ │                           │    │
│ Contenders (2)   │  └───────────────────────────┘ └───────────────────────────┘    │
│                  │    ▁▂▃▅▇▅▃▂▁  histogram          ▁▁▂▅▇▇▅▂▁  histogram           │
│  ▣ IMG_0041.jpg  │   IMG_0041.jpg  📌 Pinned  [Keep left 1] [Recycle ⇧1]   IMG_0042.jpg [Pin P] [Keep right 2] [Recycle ⇧2]
│    The only … ✓  │                                                                  │
│  ▢ IMG_0042.jpg  ├──────────────────────────────────────────────────────────────────┤
│                  │  face strip: one row per Person, one column per frame            │
│ Poor faces (1)   │        ▣      ▢      ▢      ▢                                    │
│  [ⓘ] [Recycle]   │  P1   [👤]   [👤]    –     [👤]                                   │
│  Face quality …  │  P2   [👤]   [👤]   [👤]   [👤]                                   │
│                  ├──────────────────────────────────────────────────────────────────┤
│ Blurry (1)       │  2 frames to recycle are in an album or Location: …              │
│  [ⓘ] [Recycle]   │  ← → ↑ ↓ move, R recycle   [Keep IMG_0041.jpg, recycle 3 ⏎]  [Keep all A] [Skip Sequence S]
└──────────────────┴──────────────────────────────────────────────────────────────────┘
```

**The shell** is the header (title, how many Sequences are left, Undo while there is one, Exit), the host each set's
fragment goes into, and four states with no set: too few frames left, the report of a resolve, "This set could not be
read" with Try again, and the queue's end ("All done", or the count left with Start again). A new shell is loaded on
every open, so each session starts with no skips and no undo entry.

**The Bundle rail** is the left column, scrolling by itself. From the top: the notices ("Showing the first 100 of 2,000
frames. The Sequence stays in the queue with the rest", "Too close to call", or in the warning color "Every frame: 8%
blown highlights"), then **Contenders**, then each Bundle in the rule's order. Each frame is a Preview that focuses it on
click, with its file name, its reasons ("Face sharpness 42 against 118, that person's sharpest") and its chips for its
other problems. A Pinned frame wears a pin mark and a Pick a check mark. A Bundle's header gives its name, its count, an
info (ⓘ) switch, a **Recycle** button and the first reason ("and 2 more"); it shows the
check while it holds a Pick, which its Recycle leaves in place. Every Bundle lists its frames. A Bundle's details, its
header's reason and its frames' reasons, are hidden until its info switch is pressed, so the rail reads as names and
chips; the switch is per Bundle kind, holds across sets, and the browser remembers it for the next open. The sole Contender is marked "The only frame
with no problem"; an unmeasured frame says "Not measured".

**The stage** is two panes from the originals: the pinned frame on the left and the focused frame on the right, or one
pane when nothing is pinned or the pinned frame is focused. A set opens on the rail's first two frames, the first pinned
and the second focused: its first two Contenders when it has them, else a sole Contender beside the first bundled frame, or
the first two bundled frames. Each pane carries a tag in its corner ("Keep" in the success color on a Pick, and the frame's
problems as chips), the file name, "Pinned" or a **Pin P** button, and a **Keep** button that reads "Keep left 1",
"Keep right 2" or "Keep 1" with one frame on the stage, and a **Recycle** button ("Recycle Shift+1", "Recycle Shift+2")
that sends the frame to the trash at once, for a frame that is plainly no keeper. A pane hides its image and shows a spinner from the moment its
frame changes until the new image loads, so a frame is never shown under another's name. The focused frame's neighbours
in the rail are preloaded; a held arrow key does not queue originals ahead of the frame on show.

Hover over a pane and the **Loupe** shows the spot under the pointer at the original's pixels, so sharpness, noise and
eyes are judged on the file and not on a pane that shrinks it; it shows on the pane under the pointer alone. Its level
is one value for every frame, from 100% to 400% in steps of 50%: the wheel over a pane
steps it, the only control it has, the browser remembers it for the next open, and a label in the lens's corner reads it. Above 1:1 the pixels are crisp blocks, so blur and noise are the file's and not the browser's
smoothing. It works while the stage is zoomed to a Person, and shows nothing over the bars beside the image or while a
frame loads.

Under each pane a **Histogram** strip shows the frame's luminance as bars, read from the frame's own pixels as the exposure
measurements read them, with faint lines at a third and two thirds of the range and, in its corners, the fractions of
the frame's pixels crushed (left, in blue) and blown (right, in red), so exposure is judged on the frame and the two
strips compare side by side. A click on it turns
on the **Clipping blink**: the frame's blown highlights flash red and its crushed shadows blue over the image, at the
levels the measurement counts them, so what Too bright and Too dark say is seen where it is; a second click turns it
off. It is one switch for the stage, on for both panes and through the arrows and the next sets, until clicked off or
Curation closes. It follows the zoom to a Person, and holds steady for a user who prefers reduced motion. The strip's
bars and corners are empty while a frame loads.

Pinning keeps nothing. It holds a frame on the left to compare the others against. Pinning the focused frame replaces
the pinned one and moves focus to the next frame, so two stay on the stage. There is no unpinning; focusing the pinned
frame shows it alone and moving away brings the pair back.

**The face strip**, under the stage, is the main decision aid, since eyes and expression are judged by eye: one row
per Person (most frames first) and one column per frame in the rail's order, from the stored face crops, with a dash
where a frame has no Face of that Person. A column's head is the frame's Preview and focuses it. Clicking a crop zooms
both panes to that Person's box, or back out; a frame with no Face of the Person shows whole.

**The footer** holds the keys' hint on the left, at twice the size of the footer's other small text, Keep all and Skip Sequence on the right (a selection has no Skip Sequence), and in the center what Resolve will
do: "Keep IMG_0042.jpg, recycle 3" with one Pick, "Keep 2, recycle 3" with several. With no Pick and one frame on the
stage, that frame is kept; with no Pick and two on the stage, nothing is, and the center reads "Press 1 or 2 to keep a
frame" with no button, so a frame on the stage is never recycled on a guess. A row above says how many frames to be
recycled are in an album or a Location, since recycling takes them out and a restore does not add them back.

## 3. Keys

Bound on the document while Curation is open, ignored in a text field or with Ctrl, Cmd or Alt held. Only the arrows
repeat when held, so a held key cannot resolve set after set. Enter on a focused button presses that button; a pointer
click hands focus back to the shell so Enter resolves next.

| Key | Action |
|---|---|
| ← ↑ | Focus the previous frame in the rail's order |
| → ↓ | Focus the next frame |
| 1 | Toggle the left frame as a Pick (or the only frame on the stage) |
| 2 | Toggle the right frame as a Pick; nothing with one frame on the stage |
| Shift+1 | Recycle the left frame at once (or the only frame on the stage) |
| Shift+2 | Recycle the right frame at once; nothing with one frame on the stage |
| P | Pin the focused frame and move on; nothing on the pinned frame |
| Enter | Resolve with the Picks; with none, the one frame on the stage; otherwise nothing |
| A | Keep all |
| R | Recycle the focused frame's Bundle, leaving any Pick in it; a Contender has none |
| S | Skip Sequence (nothing on a selection) |
| Z | Undo the last action |
| Esc | Close Curation |

Actions run one at a time: a key pressed while a set is being resolved or read does nothing.

The wheel over a stage pane steps the Loupe's level, the one pointer gesture with an
effect of its own; the Loupe is pointer-only, and the arrows are the rail's.

## 4. The walk of the queue

Opened from the toolbar, Curation reads the queue's Sequence IDs once and requests one set's fragment at a time; the
user never sees the list. Resolve, Keep all and Skip Sequence open the next set. A Sequence that answers with no content (gone
under two frames meanwhile) is passed over without comment. The next set's opening frames are preloaded while the
current one is on show, so it paints at once.

When the IDs run out, the count is read again. With Sequences left (skipped, or imported meanwhile) the end state shows
the count with **Start again**, which reads the queue afresh; with none it reads **All done**.

A Sequence opened from its header or a selection has no next set. A Keep all that changes nothing closes
Curation, as Skip Sequence does on that one Sequence. A selection is no Sequence, so it has no Skip Sequence, button or key:
Exit closes it. A Resolve, or a Keep all that changes something, stays open on a report ("Kept 1 frame, recycled 3 frames") with
Undo and Exit. A Bundle's recycling that leaves too few frames shows the too-few state.

## 5. Outcomes and Undo

Every action is saved as it happens, so closing loses only the skips and the undo entry.

| Action | What happens |
|---|---|
| Resolve | Posts the Picks and the rest to `resolve`. The Picks are kept, the rest recycled, and the Sequence cleared when the set held all of it. |
| Keep all | `resolve` with nothing to recycle. On a whole Sequence this dismisses a wrongly grouped burst; on part of one it changes nothing. |
| Recycle a Bundle | The grid's own recycle endpoint on the Bundle's frames (a Pick in it stays). The set is read again, keeping the pinned frame, the focused frame, the Picks and the zoom, and the Bundles are recomputed against the frames left. |
| Recycle a frame | The grid's own recycle endpoint on a frame of the stage, a Pick or not. The set is read again as after a Bundle's recycling, and the next frame takes the recycled one's place: the frame left on the stage is the pinned frame and the next frame in the rail's order comes in on the right, so two stay on the stage. |
| Skip Sequence | Nothing is saved; the walk moves on. Not offered on a selection. |
| Undo | One entry, the last mutation. A Resolve or Keep all goes back through `reopen`; a recycled Bundle or frame through the grid's restore, the frame back on the stage beside the one it was recycled from. The set is read again by its own ID wherever the walk has gone: the walk goes back to it, or if Start again replaced the list, the Sequence is put in where the walk stands. The result is reported as a restore is (a frame imported again since stays in the trash). |

A failed write goes to the snackbar and leaves the set on show, since nothing was saved. A failed read, whether it
opens Curation or follows an outcome, shows "This set could not be read" with Try again and never leaves a set on show
past its outcome, so a set whose outcome was saved cannot be acted on twice.

## 6. After closing

Exit and Esc close Curation; a backdrop click does not. The selection is reset on every close, resolved or not: the
set is done with, and a selection left standing would make the next one additive. The grid beneath keeps its scroll
position. If the session changed nothing net of its undos, nothing else moves. Otherwise the frames still recycled
leave the grid as the grid's own Recycle takes them (with the group counts and the total), and the Curate buttons come
off the Sequences cleared; kept frames stay under their old header until the next search. On the map layout, which has
no cells, the search runs again.

After each Resolve, Keep all, Bundle recycling and Undo, the counts refresh: the nav, folder, album and Location counts
and the number of Sequences to curate, which the toolbar's button reads. Skip Sequence changes nothing and refreshes nothing.

## 7. Settings screen

The Triage section of the Settings screen holds the five Sequence Settings and the seven Curation thresholds, each
with a description of what it changes. All are Live. A Curation threshold is used by the next set read, with no restart
and no re-import. A Sequence Setting, the Scene model included, applies to the next assignment: both models' embeddings
are stored at import, so switching the model needs no re-import either, but Sequences already assigned stay as they
are.

---

# Part 3: Research notes

These notes were written on 2026-10-31, before any of the above existed, as the options for a Curate view where the
user picks the best shot out of a batch of related images. What they led to is in Parts 1 and 2: the metadata and
perceptual-hash layers of batch detection became the Sequence rule and the global image embedding its likeness test,
the Laplacian and exposure metrics became Image quality, and the ideas that the worst face dominates and that the whole cast must be present became the Poor faces
Bundle, as tests rather than terms of a score. This part keeps only what was considered and not built, so it reads as
the options still open. A batch in the notes is a Curation set.

## What the stack offers

Most of the options below need no new native dependency:

- **OpenCV 4.9** via bytedeco, with the contrib modules: `img_hash`, `quality`, `features2d` (SIFT is in the main
  module since 4.4), `xfeatures2d`, `photo`, `ximgproc`, `dnn`, `dnn_superres`. The build file lists the presets
  (`build.mill`, `bytedecoPresetLibs`).
- **ffmpeg 6.1** and **openblas** via bytedeco.
- **ONNX Runtime** (`com.microsoft.onnxruntime`, CPU), which runs the two Scene models.
- **Faces**: YuNet detection with five landmarks and a score, ArcFace 512-float embeddings, and the embedding norm as
  `Face.quality` (see [faces.md](faces.md)). The Video path clusters detections across Sampled frames with the pure
  `cluster` and `mergeClusters` functions of `FaceRecognitionService`.
- **Vectors**: a pgvector column on PostgreSQL and the `sqlite-vector` extension on SQLite, wrapped by `withFaceVector`.
- **Metadata**: metadata-extractor 2.19 feeds `ExtractedMetadata` and the `PublicMetadata` fields (device model,
  f-number, focal length, ISO, exposure time, date/time original), plus `originalCreatedAt` with its
  `CaptureDateSource`, `width`, `height`, GPS, and a SHA-256 `checksum` of the file.
- **The measurement stage**: `MeasureImageFlow` already holds the decoded BGR `Mat` of each image, so a new per-image
  metric is computed there without decoding again.

## 1. Grouping beyond the Sequence rule

The research saw grouping as layers, cheapest first, escalating only for borderline pairs: union-find over the pairs
that pass, restricted to a sliding window over the assets sorted by capture time, so the cost stays linear. The
Sequence rule is the first two layers (metadata, then the hash) with the transitive merge standing in for union-find.
The signals it does not use:

### Metadata not read

- **Orientation and focal length**, and sequential file names, would confirm a candidate the time gap proposes.
- **Maker sequence tags**: Apple writes a burst UUID in its MakerNote, Sony writes sequence numbers; metadata-extractor
  exposes these directories. They would group a burst without any image comparison.

### Hash variants (`opencv_img_hash`, ships)

- `BlockMeanHash` is the alternative to pHash; `RadialVarianceHash` tolerates rotation and `ColorMomentHash` colour
  edits. All compare by Hamming distance.
- A hash nails re-exports, resized copies and static-scene bursts, and is weak for reframing and zoom, which is what a
  burst with a moving photographer does. That weakness is where the next two layers come in.

### Global image embedding

What is built (Part 1 §1 and §2): every still image is embedded at import by two Scene models through ONNX Runtime,
**DINOv2 ViT-B/14** (768 floats, self-supervised and layout sensitive, the one for "same scene, different framing")
and the **CLIP ViT-B/16** image tower (512 floats, semantic), both stored on the asset in a vector column per model
(`vector` on PostgreSQL, a float32 BLOB on SQLite), and the Setting `sequence.embedding_model` names the one the
Sequence rule compares by cosine similarity, each model with its own threshold. ArcFace and YuNet stay on OpenCV DNN.

Still open:

- **SigLIP 2** or **MobileCLIP 2** in CLIP's place, or **DINOv3** in DINOv2's, as better or smaller models of the same
  two kinds; a third column, or a column replaced, and a re-import of the library.
- **Similar-image search** over the stored embeddings, which needs a vector index on the columns (pgvector HNSW as the
  faces have; sqlite-vector's scan on SQLite) and a search entry; the rule itself compares a frame with the handful of
  candidates of its window and needs none.
- **Text search** over the CLIP embedding, which needs the text tower exported beside the image tower.
- An **aesthetic score** as a linear head on the CLIP embedding (see **Learned no-reference quality** below).

### Geometric verification for doubtful pairs (`features2d`, ships)

- SIFT or AKAZE keypoints, a brute-force or FLANN matcher, then RANSAC via `findHomography`. The inlier ratio proves
  two frames see the same physical scene from a nearby viewpoint. This is what Lightroom-style auto-stacking and
  panorama tools do.
- Tens of milliseconds a pair at 1280 px, so it runs only where the layers above disagree.

### People as a signal

The Jaccard overlap of Person sets between two images is a strong tie-breaker, and the pipeline already has it.

### Working thresholds (to calibrate on real files)

The first four rows are the Sequence Settings of Part 1 §6, with their defaults; the last is an untested starting point.

| Signal | Setting | Default |
|---|---|---|
| Capture gap | `sequence.max_gap_ms` | 60 s |
| pHash Hamming | `sequence.max_hash_distance` | 10 bits, only where an embedding is missing |
| DINOv2 cosine | `sequence.dinov2.min_similarity` | 0.80 |
| CLIP cosine | `sequence.clip.min_similarity` | 0.90 |
| Homography inliers | not built | 30% and up for a burst, 15% for a session |

### Other open-source references

digiKam's similarity search (Haar wavelet signatures), czkawka (pHash family), imagededup (Python, CNN plus hashes).
Useful for threshold ideas, not as dependencies.

## 2. Measurements not taken

Any of these would be computed in `MeasureImageFlow` and stored on the asset or the Face, as the existing metrics are.

### Technical, no model needed

- **Sharpness**: Tenengrad (Sobel gradient energy) is more robust to noise than the variance of the Laplacian. A
  saliency map from `ximgproc` would give a subject region for an image with no faces, where today only the whole frame
  is measured. Resolution normalization: the fitted 1280 px box is the current answer.
- **Motion blur vs defocus**: gradient anisotropy separates them; exposure time times focal length from EXIF predicts
  shake.
- **Exposure**: histogram entropy, dynamic range and EXIF exposure bias, beyond the mean, clipped and crushed fractions.
- **Noise**: Immerkær's estimate from the Laplacian is cheap; ISO from EXIF corroborates it.
- **Colour and contrast**: RMS contrast, Hasler and Süsstrunk colourfulness, white balance cast from the mean a and b
  channels in Lab.
- **Composition**: horizon tilt from Hough lines, subject placement against thirds, headroom, and faces cut by the
  frame edge, which the YuNet boxes tell directly.
- **Z-scores within a batch** were the research's way to compare frames; the rule compares by a ratio or a gap to the
  best frame instead, which reads the same for a set of two.

### Learned no-reference quality

- **BRISQUE** ships in `opencv_quality`; it needs the two small model files from opencv_contrib
  (`brisque_model_live.yml`, `brisque_range_live.yml`).
- **NIMA** (aesthetic and technical heads, MobileNet backbone, small ONNX exports) gives a 1 to 10 aesthetic score.
- **LAION aesthetic predictor** is a linear head on a CLIP embedding, which every still image now carries.
- **MUSIQ**, **TOPIQ**, **CLIP-IQA**, **ARNIQA** are the research-grade scorers (the `pyiqa` zoo). Heavier, and only
  worth it if the simple metrics prove insufficient.

### Face-specific

- **Head pose** by `solvePnP` on the five YuNet landmarks against a 3D template: yaw, pitch, roll.
- **Eyes open and smile** need a second model; five landmarks cannot see an eyelid. The best single addition is the
  **MediaPipe Face Landmarker** (478 landmarks plus blendshapes: blink left and right, smile, and pose in one pass;
  TFLite, with ONNX conversions available). Lighter alternatives: the OpenCV Zoo facial expression model (smile only),
  or a 106-landmark model (InsightFace 2d106, PIPNet) with the eye aspect ratio for blink. The eye-state plan below
  takes this up.
- **YuNet's detection score** and the face's **cosine distance to the Person's centroid** over the set (lowest on the
  most canonical, frontal, sharp look) are two per-face readings the rule does not use.
- **OFIQ** (Open Source Face Image Quality, German BSI, 2024) implements ISO/IEC 29794-5 with a unified score and
  component measures: eyes open, mouth closed, pose, illumination, sharpness, occlusion. It is a C++ library on ONNX
  Runtime, so the checklist is worth borrowing; the dependency is not. Related research-grade face quality scorers:
  SER-FIQ, CR-FIQA, SDD-FIQA.

## 3. Ranking frames

The research's end point was a ranked set: a per-frame score aggregated over its faces so that the worst face
dominates (a minimum or a soft minimum, a generalized mean with a negative exponent, never the mean), a hard penalty
for a frame missing someone of the cast, and the frames ordered by the combined score with the rationale as per-face
chips. The rule keeps the chips and the two face tests and drops the score. The research's other reuse idea, clustering
the set's detections with the Video path's `cluster` and `mergeClusters` so that batch-level voting can resolve a face a
single-frame match would drop, is not taken either: the rule reads the Persons the recognition already assigned at
import.

## What is not there yet

- **Eyes closed** (`plans/todo/eye-state.md`): a per-Face eye-state measurement at import and an Eyes closed Bundle,
  the most serious problem a frame could have. Today eyes are judged in the face strip by eye. The landmarker it
  needs would bring smile and pose with it.
- **Beyond the Sequence rule's use of the Scene embeddings**: similar-image search (a vector index on the columns),
  text search over the CLIP embedding (its text tower), and the aesthetic score as a head on it. The embeddings are
  stored for every still image; nothing reads them but the rule.
- **Learned quality models** and the other measurements of §2.
- Nothing ranks the frames and nothing suggests a Pick beyond the sole Contender; the score is never read.
- Videos and animated images are never measured and never in a Sequence or a set.
- The modal's behaviour is checked in the browser, not by an automated test.
