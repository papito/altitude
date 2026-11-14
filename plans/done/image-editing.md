# Image editing

## Goals

- An **Edit panel** in Asset detail adjusts a still image non-destructively. It has these controls:
  - **Crop & rotate:** crop with aspect presets, rotate 90°, flip, Straighten with Auto and the Level tool, and
    Perspective.
  - **Light:** Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Gamma and a tone curve.
  - **Color:** Temperature, Tint, Vibrance and Saturation.
  - **Effects:** Sharpen, Clarity, Vignette and Grain.
- The original file is never written. An asset's **Edit** is a small JSON description of every control's value. The
  server keeps one Edit per asset, the one last saved. Undo and Redo are the Edit panel's, per asset while it is open.
- The server renders the Edit with OpenCV into two images:
  - the edited **Preview**, which the grid, Quick view and the map show;
  - a **Display image**, at most 3840 px on the long edge, which Asset detail shows in place of the original.
- While editing, a WebGL2 renderer draws the same math live from the original, so every change shows at once.
- A change not saved yet is never lost silently: leaving it asks to Save, Discard or Keep editing.
- One new Permission, **Edit images**, allows editing.

Out of scope:
- Server-side versioning of Edits (a History that survives a reload); a later plan.
- HSL and the channel mixer.
- Per-channel (R, G, B) curves.
- Local adjustments (brushes, masks).
- Presets and copy/paste of Edits between assets.
- Batch editing.
- Downloading or exporting the edited image at full size. The renderer is full-resolution capable; serving the result
  gets its own plan.
- Re-measuring the edited pixels.
- Editing Videos and animated images.
- Full ICC color management: converting a non-sRGB original's profile to sRGB in both renderers.

## Decisions

| Area | Decision |
|---|---|
| What is stored | One row per edited asset in `asset_edit`: the Edit (`version: 1`), its edit version, the edit version rendered, the last revision used, whether it is edited, the edited size and who saved it last. The original, its Preview and every import measurement stay as they are. |
| Undo | In the browser. Each change puts the Edit shown before it on Undo's stack and empties Redo's; Undo and Redo show the Edit before or after and save it as any change. The stacks last while the Edit panel is open on the asset: closing the panel, closing Asset detail, stepping to another asset, a conflict or a failure forgets them. Revert to original saves the identity, which Undo takes back while the panel stays open. |
| Change granularity | A slider drag counts once, on release. A slider's keys and a click on its track count once they rest (400 ms and 300 ms). Enter in its number field counts once. A curve point counts once, on release, and its keys once they rest. A whole Crop tool session (moves, resizes, rotation by drag, Level, any control used meanwhile) counts once, on Done. A button (rotate, flip, aspect, Auto, a section's reset, Reset all, Revert to original) counts once. |
| Concurrency | Every save carries the edit version the client built on (`base`). A stale `base` is a 409 carrying the current state, which the client adopts and reports. Saves of one session go one at a time; changes waiting behind a save are one save of the latest. |
| Pending changes | A change is pending while the Crop tool's session has changed the Edit, a change waits out its delay, or a save is waiting or on its way. Closing the panel, stepping to another asset, closing Asset detail (Escape, ×, backdrop, Back) and Escape in a changed Crop tool session ask first: **Save**, **Discard** or **Keep editing**. A save on its way alone is waited for up to a second. `beforeunload` has the browser ask; `pagehide` sends what is not saved with `keepalive`; `pageshow` from the cache reads every session's Edit again. |
| Rendering | Asynchronous, on a queued pipeline (`edit-render`), 1.5 s after the last save of an asset. It renders the edit version current at that moment, one render of an asset at a time. An edit version that moves during a render throws the result away and queues the asset again. Unrendered Edits are queued again at startup. |
| Edited images | The edited Preview (200 px box, WebP 80, as the original's) and the Display image (long edge `min(3840, edited size)`, WebP 90). One of each per asset, staged and flushed before the render's transaction and moved into place by an atomic rename under the asset's locks. |
| Caching | `asset.edit_rev` is the edited images' `?v=` cache key, never reused for an asset. `v == edit_rev` is served `immutable`; an earlier `v` is an uncached 302 to the current one; a `v` above it, or 0, is an uncached 404. The unversioned Preview URL keeps serving the original's Preview. |
| Asset detail stage | An unedited asset shows the original in `<img>`. An edited asset rendered at a revision shows the Display image. An edited asset whose render is pending or failed, and every asset while the Edit panel is open, is drawn by the WebGL renderer from the original; while the panel's Edit is the identity the canvas draws behind the original's color-managed `<img>`, so an unchanged photo looks as it does with the panel closed. |
| Seeing the original | Anyone who sees an edited asset, Guests included, can see its original in Asset detail: hold `\` for as long as it is held, or press the **Show original** toolbar toggle, which stays pressed until pressed again, Asset detail steps to another asset, or the Edit panel opens or draws a change. |
| Curation | Shows the original everywhere (stage, rail, queue covers). It judges captures, not edits. |
| Measurements | Image quality, Histogram, luminance, clipping, the perceptual hash, Sequences and duplicate detection keep the original's values. |
| Faces | Face highlights are mapped through the Edit's geometry. A Face whose center falls outside the crop is not drawn. Face images and recognition stay on the original. |
| Permission | **Edit images** (bit 9). Every route of an asset's Edit needs it. Anyone who may see the asset sees the edited images and the original. The existing **Edit metadata** is spelled in sentence case. |
| What can be edited | A still image (`Asset.isStillImage`) outside the trash, whose original the browser decodes upright at the server's upright size. Videos, animated images and recycled assets are refused by the server and disabled in the UI. |
| Live preview | WebGL2 fragment shaders over the original, decoded upright (`createImageBitmap`, `imageOrientation: "from-image"`, `colorSpaceConversion: "none"`). The texture is fitted to the stage's device pixels, at most `gl.MAX_TEXTURE_SIZE`, with mipmaps. |
| Color | Both renderers treat the original's values as sRGB; no ICC profile is converted. |
| Parity | One written spec, [docs/image-edits.md](../../docs/image-edits.md), of the geometry, the per-pixel math, the edge rule and auto-straighten. The JavaScript and Scala implementations are both checked against shared vectors (`test/resources/edit/vectors.json`): Scala in the unit suite, JavaScript with Node's built-in `node:test` runner (`make test-js`). |
| Crop tool gestures | interact.js 1.10.27 as vendored: `draggable` and `resizable` on the crop rectangle with the `aspectRatio` and `restrictSize` modifiers. Keeping the crop inside the image is done in the move listeners with the geometry module, since interact.js only restricts to upright rectangles. |
| Language | **Edit panel**, **Edit**, **Effects**, **Crop tool**, **Level tool**, **Display image** and **Show original** are in `CONTEXT.md`, with **Edit images** in the Permission list. |

## The Edit

The full spec (every formula, the edge rule, the two renderers, the crop's rules, auto-straighten and the vectors) is
[docs/image-edits.md](../../docs/image-edits.md). This section is its summary.

### Format

Every key is always present. `EditRecipe.toJson` writes `version` first; the client sends the same object.

```json
{
  "version": 1,
  "rotate": 0, "flipH": false, "flipV": false,
  "straighten": 0, "perspectiveV": 0, "perspectiveH": 0,
  "crop": null, "aspect": "original", "portrait": null, "keepInside": true, "cropFitted": true,
  "exposure": 0, "contrast": 0, "highlights": 0, "shadows": 0, "whites": 0, "blacks": 0, "gamma": 0,
  "curve": [[0, 0], [1, 1]],
  "temperature": 0, "tint": 0, "vibrance": 0, "saturation": 0,
  "sharpen": 0, "clarity": 0, "vignette": 0, "grain": 0
}
```

| Key | Range, step | Meaning |
|---|---|---|
| `rotate` | 0, 90, 180, 270 | Clockwise quarter turns. |
| `flipH`, `flipV` | boolean | Mirror after the turn, in the turned frame. |
| `straighten` | −45…45, 0.1 | Degrees, positive clockwise. |
| `perspectiveV`, `perspectiveH` | −100…100, 1 | Keystone. |
| `crop` | `null` or `{x, y, w, h}` in [0, 1], edges to 4 decimals, `w`, `h` ≥ 0.01 | A fraction of the frame. `null` is the whole frame. |
| `aspect` | `original`, `free`, `1:1`, `5:4`, `7:5`, `3:2`, `16:9` | The Crop tool's lock, written landscape. |
| `portrait` | `null`, `true`, `false` | The lock's orientation. `null` follows the frame. |
| `keepInside` | boolean | The crop may not reach past the image's outline. |
| `cropFitted` | boolean | The crop was never set by hand, so it re-fits as the geometry changes. |
| `exposure` | −5…5, 0.05 | EV. |
| `contrast`, `highlights`, `shadows`, `whites`, `blacks` | −100…100, 1 | |
| `gamma` | −100…100, 1 | The slider's position. γ = 2^(gamma/100), 0.50…2.00. |
| `curve` | 2…16 points `[x, y]` in [0, 1], 3 decimals, x increasing by at least 0.001 | The master tone curve. |
| `temperature`, `tint`, `vibrance`, `saturation` | −100…100, 1 | |
| `sharpen` | 0…150, 1 | |
| `clarity`, `vignette` | −100…100, 1 | A negative Vignette darkens the corners. |
| `grain` | 0…100, 1 | |

An Edit is the **identity** when it changes no pixel: `rotate` 0, no flip, Straighten and perspective 0, `crop`
`null`, every slider 0 and the curve `[[0,0],[1,1]]`. `aspect`, `portrait`, `keepInside` and `cropFitted` do not count.
An asset is **edited** when its Edit is not the identity.

### Geometry

All coordinates are in the **upright original**: the original turned by its EXIF orientation, as browsers show it and
the Preview is made, W₀ × H₀ (`AssetService.uprightSize` on the server; on the client the decoded bitmap's size, checked
against the server's `uprightWidth` and `uprightHeight`).

1. **Turn and mirror.** Rotate clockwise by `rotate`, then mirror. This gives the **frame**, W × H.
2. **Warp**, in normalized coordinates around the frame's center (c = (W/2, H/2), s = max(W, H)/2): with keystones
   a = 0.55·`perspectiveH`/100 and b = 0.55·`perspectiveV`/100, u maps to u / (1 + a·uₓ + b·u_y), then turns by
   θ = `straighten`°. Sampling inverts it; there is no source where w ≤ 0.001.
3. **Crop.** `crop` is a fraction of W × H. The **edited size** is round(w·W) × round(h·H), each at least 1.
4. **The image's outline** is the frame's corners through the warp: a convex quadrilateral that "keep the crop inside the
   image" holds the crop's corners in.

**Sampling and the edge rule.** Both renderers sample bilinearly with the edges repeated (`BORDER_REPLICATE`,
`CLAMP_TO_EDGE`). A pixel is covered when its inverse exists and its source lies in the frame; any other is fully
transparent. Beyond the horizon both sample the projected point, which never shows.

### Per-pixel math

On sRGB-encoded values in [0, 1] (8-bit /255, 16-bit /65535), alpha to the coverage rule:

1. To linear (sRGB EOTF).
2. White balance: luminance-preserving gains from `temperature` and `tint`.
3. Exposure: × 2^`exposure`.
4. To sRGB (OETF), unclamped.
5. Tone: black and white points, Shadows, Highlights, Gamma, Contrast, then a clamp.
6. Curve: a monotone cubic Hermite (Fritsch–Carlson), held flat outside its ends.

Steps 1–6 act on each channel alone; both renderers bake them into a 4096-entry float table per channel, read with
linear interpolation.

7. Saturation and Vibrance around the Rec. 709 luminance. The uncovered pixels go transparent, and L (the luminance of
   this result) is what the rest reads.
8. Clarity (σ = 1.5 % of the output's short side) and Sharpen (σ = max(0.4, 0.8 px × long edge / 3840)), each a delta of
   a Gaussian-blurred L added to all channels.
9. Vignette over the output.
10. Grain: value noise from a 32-bit integer hash on cells of max(1, c) px, c = 1.75 px × (long edge / 3840); its
    strength is scaled by min(1, c), so Grain looks as strong on the Edit panel's canvas as on the Display image shown at
    that size.
11. Clamp and encode to 8 bits.

Clarity, Sharpen and Grain are sized relative to the output, so the canvas, the Display image and a full-size render
look alike at their own scales. Pixels match only within a tolerance (see **Tests**).

### Validation

`EditRecipe.parse` (of text or a `ujson.Value`) is the server's one way in, and `recipe.js`'s `normalize` rounds as it
does. It refuses with a `ValidationException` saying why:
- an object that is no JSON object, a `version` other than 1, an unknown or missing key;
- a value of the wrong type (NaN included), a `rotate` that is no quarter turn, an unlisted `aspect`;
- a slider outside its range once rounded;
- a crop with an edge outside [0, 1] or a side under 0.01;
- a curve with fewer than 2 or more than 16 points, or an x less than 0.001 past the one before.

It rounds every number to its step, half up. A crop is rounded by its edges, so one reaching the frame's edge keeps
reaching it. Whether a crop stays inside the image's outline is the client's concern.

## Storage

### Schema

The changes are in both `all.sql` files. There is no migration. On SQLite, `edit_rev` goes with the small columns of
`asset`, before the bulky ones.

```sql
-- asset: the revision of its edited images, their URLs' cache key; 0 when it has none, so the original's Preview and file are shown
edit_rev INTEGER NOT NULL DEFAULT 0,

CREATE TABLE asset_edit (
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL PRIMARY KEY,
  recipe TEXT NOT NULL,                     -- the Edit; jsonb on PostgreSQL; the identity once reverted
  edit_version INTEGER NOT NULL,            -- 1 at the first save, one more at each save; a save names the one it built on
  rendered_version INTEGER,                 -- the edit version whose edited images are stored; NULL before the first render
  last_rev INTEGER NOT NULL DEFAULT 0,      -- the highest revision the asset has used, so none is reused
  is_edited BOOLEAN NOT NULL,               -- the Edit is not the identity
  width INTEGER NOT NULL, height INTEGER NOT NULL, -- the edited size
  updated_by_account_id CHAR(36) REFERENCES account (id) ON DELETE SET NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
);
-- The startup requeue: Edits whose edit version is not rendered
CREATE INDEX asset_edit_01 ON asset_edit (repository_id) WHERE rendered_version IS NOT edit_version;
-- An account's delete clearing the Edits it saved last
CREATE INDEX asset_edit_02 ON asset_edit (updated_by_account_id) WHERE updated_by_account_id IS NOT NULL;
```

PostgreSQL spells the partial index's condition `rendered_version IS DISTINCT FROM edit_version` and takes
`created_at` and `updated_at` from `_core` as the other tables do. An asset never edited has no row and reads as edit
version 0 with the identity. A purged asset's row goes with it through the cascade.

### Models and DAOs

- **`models/EditRecipe.scala`:** the case class of the Edit (with `EditCrop`). It has `parse`, `toJson`, `isIdentity`
  and `editedSize(W₀, H₀)`.
- **`models/AssetEdit.scala`:** the row. **`models/EditState.scala`:** what the service answers: `editVersion`, the
  Edit, `renderedVersion`, `rev`, `edited`, the edited size and the upright size.
- **`dao/AssetEditDao`** (trait), with `jdbc/`, `postgres/` and `sqlite/` versions. It is repository-scoped; the engine
  subclasses override only the spelling of an unrendered edit version.
  - `get(assetId)`, and `getForUpdate(assetId)`, which locks the row with `forUpdate`.
  - `insert`, `save`, `setRendered(assetId, editVersion, lastRev)`, `pendingRenders()`.
- **`AssetDao.lockUnpurged(assetId)`** locks an asset unless it is marked for purging (`FOR NO KEY UPDATE` on
  PostgreSQL, through `BaseDao.forNoKeyUpdate`).
- **`Asset`** gains `editRev: Int`, read by every asset read, so every search page carries it at no extra query.
- **`MapCell`** gains `editRev`, joined by `SearchQueries.mapCells` with the asset that represents the cell.

### Files

`FileStoreService` gains these methods:
- `stageEditedImage(assetId, kind, bytes)`: writes `.<name>.tmp` beside the image and flushes it to the disk;
- `publishEditedImage(assetId, kind)`: one atomic rename of the staged file over the image;
- `discardStagedEditedImages(assetId)`;
- `getEditedImage(assetId, kind)` and `editedImagePath(assetId, kind)`;
- `deleteEditedImages(assetId)`: the two known paths and their temporary files, with no directory listed.

`kind` is `preview` or `display`. On disk (`FileSystemStoreService`):

```
<repo>/preview/<id[0:2]>/<id>-edited.webp   the edited Preview, beside the original's Preview
<repo>/display/<id[0:2]>/<id>.webp          the Display image
```

`purgeAssetById` also deletes the edited images and their temporary files, so a purge and a discarded import leave no
edited image behind. Their bytes are not counted in the Library's stats, as Previews are not.

## Service

### `AssetEditService`

Every public method opens or joins a transaction, as **Transaction boundaries** requires.

- **`get(assetId)`, `status(assetId)`, `editOf(asset)`: `EditState`.** `get` carries the upright size; `status` reads
  none; `editOf` is the Edit an asset's detail carries (None for an asset never edited), its revision and edit
  versions read again in one snapshot.
- **`whyNotEditable(asset)`.** Not `Asset.isStillImage` (a Video, an animated image) or recycled; the reason, or none.
- **`save(assetId, base, recipe, account): EditState`.** In order:
  1. Reads the upright size before the transaction opens, so reading the original's metadata holds no lock
     (`AssetService.uprightSize`: the stored size swapped for EXIF orientations 5–8, read through
     `ImageUtil.exifOrientation`; unreadable metadata is taken as upright, a missing original is refused).
  2. Locks the asset (`lockUnpurged`), then the Edit (`getForUpdate`): one lock order, shared with a render's write.
  3. Refuses an asset that is not editable (`IllegalOperationException`, `EDIT_NOT_STILL`, `EDIT_RECYCLED`).
  4. Refuses with an `EditConflictException(state)` when `base` is not the edit version. `EditController` answers it
     with a 409 carrying the state.
  5. Writes nothing for an Edit equal to the stored one (or the identity on an asset never edited), answering the
     current state.
  6. Otherwise stores the Edit as edit version `base + 1`, inserting the row at the first save, with `is_edited`, the
     edited size and `updated_*`.
  7. After commit, queues the render and logs the save at INFO.
- **Revert to original** is an ordinary save of the identity; the browser's Undo and Redo are saves too.
- **`editedImage(assetId, v, kind)`.** `Current` when `v` is the asset's `edit_rev`, `Moved(current)` for an earlier
  `v`, not found for a `v` above it, 0, or any `v` while `edit_rev` is 0.
- **`autoStraighten(assetId, recipe): Option[Double]`.** Runs on the request's thread in one of `autoStraightenSlots`,
  a semaphore of `editRenderParallelism` permits apart from the render queue. With none free it is a `BusyException`
  (a 503). An original no image reader decodes is `IllegalOperationException`, `ORIGINAL_UNREADABLE` (a 400).

### `EditRenderService`

- **Queue.** A `QueuedPipeline` named `edit-render`, its flow `mapAsyncUnordered(editRenderParallelism)` on its own
  dispatcher, `altitude.edit-render-dispatcher`, a fixed pool of `max(1, cores / 4)` threads.
- **Debounce.** `enqueue` is debounced per asset: each call starts the 1.5 s delay again
  (`Const.Edit.RENDER_DEBOUNCE_MS`), so a run of saves renders once. A render of an asset under way has the next queued
  again.
- **Render step** (`render(assetId)`). In order:
  1. Read the Edit and its edit version in a read transaction: `UpToDate` when rendered already, `Gone` with no Edit.
  2. Render and stage both images outside any transaction.
  3. Lock the asset, then the Edit:
     - if the edit version moved, `Discarded`: delete the staged images, queue the asset again;
     - for an edited Edit: `last_rev += 1`, publish both images under the locks, `edit_rev = last_rev` (`Rendered`,
       logged at INFO with the milliseconds taken);
     - for the identity: `edit_rev = 0` (`Original`);
     - for an original missing or undecodable: `edit_rev = 0` (`Unrenderable`, at WARN), not tried again;
     - set `rendered_version` to the edit version.
  4. Once an `edit_rev` of 0 commits, delete the edited images in a transaction of their own, under the asset's lock,
     only while `edit_rev` is still 0.
- **Failures.** A render that throws is logged at ERROR and queued again after a delay that doubles from twice the
  debounce, up to `MAX_RETRIES` (5) times. A fatal error (an `OutOfMemoryError`) is caught on the dispatcher, logged at
  ERROR and left for the next startup; it neither keeps the queue's slot nor stops the JVM.
- **Startup.** `App` calls `requeuePendingRenders()` after the purge requeue. It queues every Edit whose edit version
  is not rendered (`asset_edit_01`).
- **Shutdown.** `shutdown()` cancels the delays, stops the queue and waits up to `pipeline.shutdown_timeout` for the
  renders under way; what is left is pending for the next startup.

### Renderer (`util/EditRenderer.scala`)

`render(original: Path, recipe, longEdge: Option[Int]): Mat`, an 8-bit BGRA `Mat` the caller releases. `None` is the
edited size, for the later export plan.

1. **Decode** as stored (`ImageUtil.decodeUnchanged`, 8 or 16 bits, alpha kept). A JPEG the output samples at half or
   less is decoded reduced (`ImageUtil.decodeReduced`).
2. **Frame.** One EXIF orientation code composing the stored orientation with `rotate` and the flips
   (`frameOrientation`), applied by `ImageUtil.orient`.
3. **Shrink** with `INTER_AREA` first when the output scale is below a half, so the warp never shrinks by more than half.
4. **Warp and crop:** one `Imgproc.warpPerspective` of the composed homography, `INTER_LINEAR`, `BORDER_REPLICATE`.
5. **Tone:** the tables and step 7 in 32-bit floats, the coverage applied to alpha.
6. **Finish:** `Imgproc.GaussianBlur` of L for Clarity and Sharpen, then steps 8–11.

Every intermediate image is released as the next is made, so a render holds at most two images of the original's size.
The geometry and math are public functions (`frameSize`, `turn`, `warp`, `unwarp`, `outline`, `whiteBalanceGains`,
`tables`, `lookup`, `Curve`, `saturate`, `hash`, `grainNoise`, `grainCell`, `grainScale`, `vignetteWeight`,
`outputSize`), the source of the vectors.

The **Display image** is the result at `Const.Edit.DISPLAY_LONG_EDGE` (3840) at most, as WebP 90
(`ImageUtil.encodeWebp`). The **edited Preview** is `makeImageThumbnail(result, orientation = 1, PREVIEW_BOX_PIXELS)`,
letterboxed as every Preview is.

**`autoStraightenAngle(original, recipe): Option[Double]`:**
- The frame of step 1 (no Straighten, no perspective) at a 1280 px long edge, in gray.
- A 3 × 3 blur, `Canny` (50, 150) and `HoughLinesP` (θ 0.25°, threshold 60, segments of at least 8 % of the long edge,
  gaps of 6 px).
- Each segment's lean from the nearer of level and plumb counts when it is within the ±10° band, weighted by its length,
  a plumb one by half.
- Consensus: the weights must reach half the long edge, and the leans within ±1° of the smoothed histogram's peak must
  hold 35 % of the weight. Otherwise the answer is none (`{angle: null}`).
- The answer is their weighted mean, negated, in tenths of a degree.

## Routes

`routes/api/EditController.scala` (`prefix = "api/edit"`). Every route is
`@requireLogin() @requirePermission(Permission.EditImages)` and runs in `answeringJson`. An unknown asset, or another
Library's, is a 404. A refused Edit or body (`EDIT_WHOLE_NUMBER`, `EDIT_RECIPE_REQUIRED`) and an asset that cannot be
edited are a 400.

| Route | Answer |
|---|---|
| `GET /api/edit/r/:repoId/asset/:assetId` | The state. |
| `PUT /api/edit/r/:repoId/asset/:assetId` `{base, recipe}` | The new state. A 409 `{error, state}` (`EDIT_CONFLICT`) when `base` is stale. |
| `GET /api/edit/r/:repoId/asset/:assetId/status` | `{editVersion, renderedVersion, rev}`, polled by the client until the edit version is rendered. |
| `GET /api/edit/r/:repoId/asset/:assetId/auto-straighten?recipe=` | `{angle}`, null when no lines agree. A 503 `{error}` while every slot is taken, a 400 for an original no reader decodes. |

The state's JSON (`EditController.stateJson`): `editVersion`, `renderedVersion`, `rev`, `edited`, `width`, `height`,
`uprightWidth`, `uprightHeight`, `recipe`.

### Content (`ContentViewController`)

- `preview` with `v`: the edited Preview when `v` is the asset's `edit_rev`, `Cache-Control: immutable` as all
  content.
- An earlier `v` (a page built before the last render): a 302 to the same item at the current `v`,
  `Cache-Control: no-store`, a signed URL's `exp` and `sig` carried over.
- A `v` above `edit_rev`, or 0, or any `v` while `edit_rev` is 0: a 404, never cached.
- `preview` without `v`: the original's Preview.
- `display` (`Const.DataStore.DISPLAY`): the Display image, under the same rules. Without `v` it is a 400
  (`REVISION_REQUIRED`).
- `v` must be digits, at most nine (`REVISION_NOT_A_NUMBER`, 400 otherwise). Guests go through `visibleOnly` as for
  the original.
- The doc comment states that a content URL's bytes never change for a given `v`.

### Asset detail JSON and fragment

- `AssetActionController.showAssetDetailModal` and the navigator's JSON carry `edit` (the state) when the asset has an
  Edit, `editable` (Edit images held and `whyNotEditable` empty) and, for a holder of Edit images, `editRefusal`, the
  Edit toggle's title.
- The fragment carries them as data attributes, with the clipping levels and the measurement box, which the Clipping
  blink reads, as Curation's set does.
- `Util.previewUrl(asset)` adds `?v=<editRev>` when it is above 0. `result_cell` and `quick_view_modal` use it.
- The map's cell JSON (`MapController`) carries `editRev`, and `map-view.js` builds the pin's URL with it.
- Curation's templates keep the unversioned URL (`Util.contentUrl`).

### Permission

- `Permission.EditImages(9, "Edit images")`. The account form lists it from `Permission.values` with no change.
- `Permission.EditMetadata` is labelled "Edit metadata".
- An Admin holds it as every Permission. The Permission list in `CONTEXT.md` has it.

## Frontend

### Modules

`static/js/edit/`:

| File | Holds |
|---|---|
| `recipe.js` | The defaults, `normalize` (the server's rounding), `isIdentity`, `sameRecipe`, the slider specs (`SLIDERS`: range, step, label, decimals, track), `formatSlider` (the one formatter of a slider's readout), and `SECTIONS`. DOM-free. |
| `geometry.js` | The frame, `turn` and `turnBack`, the warp and its inverse, the outline, `insideQuad`, `validRect`, `limitTransition`, `shrinkToValid`, `fitMaxCentered`, `rectWithRatioAround`, `aspectRatio`, `editedSize`, `outputSize`, `effectRegion`, and `mapBox(box, recipe, W₀, H₀)` for Face highlights. DOM-free. |
| `tone.js` | The tables of steps 1–6, the curve, saturation, the blurs' sigmas and kernels, the vignette's weight, and the grain's hash, noise, cell and strength. DOM-free. |
| `adjust.js` | The Edit panel's changes to an Edit as pure functions: sliders and a drag from its start, the crop's rules, the aspect lock, quarter turns and flips carried into the new frame, section resets, the Level tool's and the rotating drag's Straighten, the curve's points. DOM-free. |
| `edit-session.js` | Server sync for one asset: the serial queue of saves on the last answered edit version, Undo and Redo, conflicts and failures, `discard`, `saveOnHide`, and `isNewerEditState`. DOM-free; the requests are the coordinator's. |
| `renderer.js` | The WebGL2 renderer: Develop (warp, tables as a 64 × 64 RGBA32F texture, step 7, coverage), Blur (separable passes of L for Clarity and Sharpen), Finish (steps 8–11) and Present. Half-float targets, falling back to 8-bit. `renderBefore` (geometry only) feeds Before/after. `readHistogram()` reads the last drawing at 256 px into 64 bins × L, R, G, B. A lost context is rebuilt on `webglcontextrestored`. |
| `edit-stage.js` | Asset detail's stage for a still image: its sources, the canvas, Show original, the Face highlights' mapping and the size handed to the Detail panel. |
| `render-status.js` | The wait for a render (the status route every second, for up to a minute, giving up after three failed reads in a row) and `swapGridPreviews`, which points every grid cell of the asset at the new Preview. |
| `edit-panel.js` | The coordinator (`createEditPanel`, `app.editPanel`): open and close, the dock, the stage while the panel draws, each asset's session, the Crop tool, the warning and the keys' handlers. |
| `leave-dialog.js` | The warning of changes not saved (`askToLeave`, `LeaveChoice`). |
| `crop-tool.js` | The Crop tool on interact.js (see below). |
| `level-tool.js` | The Level tool's line. |

`common/`:
- `edit-keys.js`: the Edit panel's part in `global.js`'s keys (whether the warning is open and its close, whether the
  Crop tool is open and its Escape), which the coordinator registers as the page that holds Asset detail starts, so
  `global.js`, on every page, imports no edit module.
- `histogram.js`: the Histogram's SVG paths (`binTop`, `stepsOf`, `areaPath`, `linePath`, `channelPaths`) and
  `percentLabel`, shared by Curation and the Edit panel.
- `content-urls.js`: an asset's Preview (by revision), Display image and original URLs, and `repointImage`.
- `clipping.js` accepts any image source, so it reads the live canvas; `dock.js` takes `followSide`; `modal.js` has a
  close guard per host (`setCloseGuard`, `takeHeldClose`); `text-editing.js` counts no slider, checkbox or button-like
  input as a text field.

`listeners/edit.js` hands the keys' events to the coordinator. `geometry.js`, `tone.js`, `recipe.js`, `adjust.js` and
`edit-session.js` run unchanged under Node for the tests.

### Alpine component

`alpine/components/edit-panel.js` (`Alpine.data("editPanel", …)`) holds the panel's controls: the tabs, the sliders,
the aspect chips, the curve editor and the section resets.
- It connects to the coordinator as it starts (`connectEditPanelView`). The coordinator syncs a snapshot into it on
  every change, and the component asks it for every change, worked out by `adjust.js`.
- The Edit, its session and the renderer live in the coordinator's closure, outside the reactive object, as `curation`
  keeps its heavy data.

### Placement and layout

The Edit panel is `includes/edit_panel` (`aside#editPanel`, `x-data="editPanel"`, styled by
`static/css/includes/edit_panel.css`) with its divider `#editPanelDivider`, in `view_image_detail_modal.scala.html`
next to the stage, for a holder of Edit images alone. The tools over the stage are `includes/edit_stage_tools`
(`#editSplit`, `#cropLayer`).

- **Dock side.** A third dock of `common/dock.js` with no Dock side of its own: `followSide` gives it the side opposite
  the Detail panel's, laid out again when that one moves, opens or closes. It writes `data-edit-dock-side` and
  `--edit-panel-share` on the fragment.
- **Shares.** 28 % of the width docked left or right, and 42 % of the height docked top or bottom, until a drag. They are
  stored as `Const.localStore.assetDetailView.editDock`. The minimums are `--edit-panel-min-width` (18rem) and
  `--edit-panel-min-height` (12rem), registered with `@property`.
- **Grid.** `#imageDetailView` has the areas `edit` and `egutter`. `view_image_detail_modal.css` has one rule per Detail
  panel side for the Detail panel alone, the Edit panel alone and both open, keyed by `data-app-edit-panel-open` beside
  `data-app-detail-panel-open`. The modal box fills the viewport when either panel is open.
- **Open state.** `Const.localStore.assetDetailView.editOpen` remembers whether the panel is open.
- **Toolbar toggle.** `button.toolbar-toggle[data-app-edit-panel-toggle]`, titled "Edit (E)" with `fa-sliders-h`, first
  of the toolbar's toggles, rendered for a holder of Edit images alone. On an asset no Edit may change (a Video, an
  animated image, an asset in the trash, an original the browser cannot decode or turns a quarter turn from the server's
  upright size, a browser without WebGL2) it is `aria-disabled` with the reason as its title, and `e` says the reason in
  the snackbar. Open on such an asset, the panel says why (`.edit-refusal`) and draws nothing.
- **Show original toggle.** `button.toolbar-toggle[data-app-show-original-toggle][aria-pressed]`, titled
  "Show original (hold \)" with `fa-clone`, after the Edit toggle. It is shown only on an edited asset, to every viewer,
  Guests included. See **Stage**.
- **Docked top or bottom.**
  - The section's controls flow into CSS columns that scroll sideways.
  - The Histogram sits at the left.
  - The commit row joins the header.
  - The key legend is left out.

### Panel

- **Header:** "Edit", Undo, Redo (disabled with nothing to undo or redo), Before/after, and Reset all.
- **Histogram:** red, green and blue shapes and a luminance line of the edited image as drawn, through
  `common/histogram.js`, with the crushed and blown fractions in its corners.
- **Clipping blink:** the whole Histogram is its switch (`aria-pressed`, off on each new fragment). 150 ms after the
  drawings rest, `common/clipping.js` reads the canvas at the fragment's levels and box; the mask is painted on
  `#editClipping`. Nothing is read while Before/after or the Crop tool puts more than the Edit's result on the canvas.
- **Tabs:** Crop & rotate, Light, Color, Effects, an ARIA tablist. A section with changes shows a dot in its tab, and its
  heading has a Reset.
- **Commit row:**
  - status (`SaveStatus`, `role="status"`): "Saving…", "Saved · the original is untouched", "Could not save", or
    "Changed elsewhere · showing its latest Edit";
  - "Revert to original".
- **Key legend:** E Close, C Crop, \ Original (hold), Ctrl+Z Undo, and "Double-click a slider to reset it".
- **Controls:** as listed in **Goals**, with the ranges of **The Edit**.
  - Straighten has an Auto button. See **Auto**.
  - "Keep the crop inside the image" is a checkbox.
  - A readout gives the crop's size in the original's pixels.
- **Sliders:** `.edit-slider`, a primitive in `core.css`.
  - Layout: a label row with an editable `input[type=number]` at its end (Enter sets it, Escape or leaving the field puts
    it back), over a native `input[type=range]` styled thin, filled from the left end to the thumb.
  - A changed slider's label is bold. A double-click on the label or the track resets it.
  - A drag applies each value to the Edit it started from, so a crop that shrank on the way grows back.
  - Gradient tracks for Exposure, Temperature, Tint, Vibrance and Saturation are tokens (`--edit-track-*`) in
    `core.css`.
- **Curve editor:** a canvas behind which the luminance Histogram is drawn.
  - A click adds a point and a drag moves one within its neighbours.
  - Dragging a point well off the box, or a double-click, removes an inner one.
  - Keys: the arrows move the point, [ and ] pick another, + or Insert adds one, Delete or Backspace removes one.
  - A Reset sits in its subhead.
- **Aspect chips:** a `.toggle-group`. Their labels follow the lock's orientation (4:5, 2:3, …), and a swap button flips
  it (X).
- **Auto.** Straighten's Auto calls `auto-straighten` with the Edit shown and sets Straighten as one change. With
  `angle` null, Straighten stays and the snackbar says "No straight lines to level the image by". A 503 or 400 shows
  the server's error; any other failure "Could not level the image".

### Session

- A change is shown at once and saved behind the save on its way, on the edit version that save answered; the changes
  waiting behind it are one save of the latest. The answer of the last save is adopted whole.
- A 409 drops the waiting save, adopts the state it carries and says "This photo was changed elsewhere; showing its
  latest Edit". A failure drops it, reads the state again and says "Could not save the Edit" ("Could not read the Edit"
  for a read). Both forget Undo and Redo.
- A session still saving when Asset detail steps away finishes in the background, and stepping back picks it up; an idle
  one is forgotten.
- Every state a session adopts is the asset's latest Edit, which the stage takes at once (its badge, Show original, what
  it shows while the panel is closed). An Edit Asset detail brings that is newer replaces it.
- A state rendered at its edit version points the grid's cells at its Preview at once. One not rendered has its render
  waited for once per edit version, then the grid's cells and, once the panel is closed, the stage follow.

### Pending changes

The warning (`leave-dialog.js`, `dialog#editLeaveDialog` in `includes/edit_panel`) is a native modal `<dialog>` inside
Asset detail's fragment, so it stacks over Asset detail.

- **When.** A change is pending (see **Decisions**). Closing the panel (`e`, its toggle), stepping to another asset
  (`beforeLeave`, which `detail-navigator.js` awaits), closing Asset detail (the close guard, asked by every
  `closeModal`; Back answered by staying has `detail-url.js` push the asset's entry again) and Escape in a Crop tool
  session that changed the Edit ask first.
- **What it says.** "Changes not saved", with "The Crop tool's changes are not applied yet." or "The last change to this
  photo is not saved yet."
- **Save** (`.primary`, the initial focus): applies the Crop tool's session as Done, saves a change waiting, then goes
  on.
- **Discard** (`.error`): cancels the Crop tool's session alone, or otherwise drops the change waiting and has the
  session `discard`, then goes on.
- **Keep editing** stays; Escape is Keep editing. Asset detail's other keys do nothing while it is open.
- A close nothing asks (another dialog replacing Asset detail) saves what is pending.
- **Leaving the page.** `beforeunload` has the browser ask while any session has a change pending. `pagehide` sends what
  is not saved with `fetch` `keepalive` (`saveOnHide`). `pageshow` from the browser's cache has every session read its
  Edit again.

### Stage

The stage's media box holds, in one grid cell:
- `<img>` (the original or the Display image);
- `canvas#editCanvas` (the renderer's output), for every viewer;
- `canvas#editClipping`, the Clipping blink;
- `#faceHighlights`;
- the Edit's tools: `#editSplit` and `#cropLayer`.

Which source fills it (`edit-stage.js`):
- **Unedited:** the original in `<img>`.
- **Edited and rendered at a revision:** `<img src="/content/r/:repo/display/:id?v=rev">`, its natural size the edited
  aspect.
- **Edited with the render pending or failed, and every asset while the panel is open:** the canvas, drawn from the
  original, fetched once per asset and decoded upright.
- **While the panel's Edit is the identity** and no change is on its way, outside the Crop tool and Before/after: the
  canvas draws behind the original's color-managed `<img>` (`data-app-edit-canvas-behind`), so an unchanged wide-gamut
  photo looks as it does with the panel closed. The Histogram and the Clipping blink still read the canvas.
- **Fallbacks:** a canvas that cannot draw (no WebGL2, an undecodable original) falls back to the original, and a Display
  image that does not load to the canvas.
- **Orientation check:** a decoded original a quarter turn from the server's `uprightWidth` × `uprightHeight` draws
  nothing live and falls back to the original, checked before every draw and once more when the first Edit carrying that
  size arrives. The panel says "This browser turns this photo otherwise than the server, so it cannot draw its Edit".

The canvas is drawn at the device pixels of its box, at most the size of what it shows, and drawn again once the stage's
size settles (150 ms). Whatever is shown, the box is fitted to it (`setAssetDetailSize`) and the Detail panel is handed
the upright size with the Edit shown: its Dimensions row reads "W × H px · edited w × h".

**Face highlights** are drawn through `geometry.mapBox`:
- the corners and the center of a Face's box are pushed through the Edit;
- the box drawn is the corners' bounding box cut to the edited image, as a fraction of it;
- a box whose center falls outside the crop is skipped.

Highlights are hidden while the Crop tool shows the whole frame.

**Before/after**, in the Edit panel: the button shows `#editSplit` over the canvas, "Before" (geometry only) left of a
divider (`#editSplitHandle`, dragged or stepped 5 % with the arrows) and "After" right.

**Showing the original**, anywhere in Asset detail on an edited asset:
- Holding `\` shows the untouched original for as long as it is held; the window losing focus releases it.
- The Show original toggle shows it until pressed again. Stepping to another asset releases it, and so do the Edit panel
  opening and a change drawn from it.
- While the original is shown:
  - the stage holds the original file in `<img>`, at the upright original's size;
  - Face highlights are drawn unmapped;
  - the Dimensions row shows the original's size alone;
  - the title's badge (`.edit-badge`) reads "Original" in place of "Edited".
- The Detail panel's key legend has "\ Original (hold)" on an edited asset.

### Crop tool

The Crop tool opens with C or the Crop button, and the fragment carries `data-app-crop-tool-open`. The canvas then
shows the whole warped frame with a gutter around it; its effects (Vignette, Clarity, Sharpen, Grain) are sized from and
laid over the crop (`geometry.effectRegion`), and the Histogram reads the crop alone, so nothing jumps on Done.
`#cropFrame` lies over the canvas and holds `#cropRect`, which has:
- the guide (an SVG);
- eight handles (`.crop-handle`);
- a dimming shadow over the rest of the stage (`--crop-dim-shadow`).

**Gestures**, all on interact.js:
- `draggable` on `#cropRect` moves the crop.
- `resizable` on it, by its handles, resizes it, with `aspectRatio` at the lock's ratio (none for Free) and
  `restrictSize` at the narrowest crop.
- Each listener turns the proposed rectangle into frame fractions. With `keepInside` on, it runs
  `geometry.limitTransition(previous, proposed)` before applying, so no move or resize reaches past the outline.
- `draggable` on `#cropLayer`, outside the crop and the bar, turns the image (Straighten) by the change of the pointer's
  angle around the crop's center, under a rotate cursor.
- `styleCursor(false)` is set, so the cursors come from CSS. The layer, the crop and the handles are
  `touch-action: none`.

**Keeping the crop inside:**
- A fitted crop (`cropFitted`) re-fits to the largest rectangle at the aspect as Straighten, Perspective or the aspect
  change.
- A crop the user set shrinks around its center only as much as it must (`shrinkToValid`).
- A quarter turn and a flip carry the crop, the perspective and Straighten into the new frame.

**The Level tool** (the Level button, which opens the Crop tool if needed) draws a line (`#levelLine`) and sets
Straighten so the line becomes level or plumb, whichever is nearer. A line under 12 px is a click and sets nothing.

**Keyboard:**
- `#cropRect` is focusable, and the arrows nudge it by 1 px of the frame (10 px with Shift).
- O cycles the guide: Thirds, Grid, Golden ratio, Diagonals, None. Thirds is shown until another is picked.
- X swaps the aspect's orientation.

**Floating bar** (`#cropBar`, `role="toolbar"`): the guide, the size, Cancel (Esc) and Done (Enter or C).
- Every change made while the tool is open joins its session, shown and not saved. Undo, Redo and Before/after are off.
- Done saves the session as one change. Cancel shows the Edit the tool opened on again.
- Escape is Cancel while the session changed nothing, and asks first once it has, as stepping, closing the panel and
  closing Asset detail do.
- An Edit the session adopts meanwhile (a conflict's) closes the tool.

### Keys (`global.js`)

These apply only in Asset detail, never in a text field (`isTextEditingTarget`). Each dispatches a `Const.events` event
that `listeners/edit.js` hands to the coordinator.

| Key | Effect |
|---|---|
| E (plain) | `toggleEditPanel`. |
| C (plain, panel open) | `toggleCropTool`. In the Crop tool, it is Done. |
| O, X, Enter (Crop tool) | `cycleCropGuide`, `swapCropAspect`, `cropDone`. Enter on a button or link presses that instead. |
| Escape | Closes the warning (Keep editing), then leaves the Crop tool (Cancel, or the warning), ahead of a selected Face and of closing Asset detail. Read through `common/edit-keys.js`. |
| `\` held (an edited asset) | `showOriginalHeld` until released; the key that types `\` on any layout. |
| Ctrl/Cmd+Z | `undoEdit`, while the panel is open and not in the Crop tool. |
| Ctrl/Cmd+Shift+Z or Ctrl+Y | `redoEdit`, likewise. |

The arrows and Space are left to focus in `#editPanel`, on `#editPanelDivider`, on `#editSplitHandle` and in
`#cropLayer`, so a slider steps and the crop nudges where Asset detail would step.

### Style guide

Specimens:
- `.edit-slider`, plain and with a gradient, in Forms;
- in Panels & layout, an Edit panel strip: the header's tools, the Histogram, the tab row, the aspect chips and the key
  legend;
- the warning of changes not saved, open in place;
- the Edit and Show original toggles and the "Edited" badge.

## Tests

### Server: integration

Both engines. Assets come from `TestContext`.

**`AssetEditServiceTests`**, in `AllIntegrationTestSuites`:
- **First save:** stores the Edit as edit version 1 with its edited size and `is_edited`.
- **Snapshot:** `editOf` reads the revision and the edit versions in one snapshot.
- **Saves:** each replaces the Edit and moves the edit version on, keeping no earlier Edit.
- **Stale base:** a conflict carrying the current state, writing nothing.
- **No-op:** a save equal to the Edit writes nothing.
- **Revert:** saves the identity as the next edit version, and the Edit before it can be saved again.
- **Refused assets:** an animated image (`images/animated.gif`, two frames), a Video and an asset in the trash.
- **Upright size:** follows the EXIF orientation (`images/exif/orientation-6.jpg`).
- **Missing original** is refused; unreadable metadata is taken as upright.
- **Isolation:** another Library's asset is not found.
- **Race:** two saves built on the same edit version take turns, and the second is a conflict.
- **Purge:** removes the Edit and its edited images.

**`EditRenderServiceTests`:**
- **Edited Edit:** stores both images under the next revision, replacing the ones before.
- **Identity:** sets the revision to 0 and deletes the images; no revision is used twice; the delete leaves images a
  later render stored (the `beforeDelete` hook).
- **Save mid-render:** throws the render away and queues the asset again (the `beforeWrite` hook); a render's write
  waits for a save under way.
- **Lock order:** a render's write and a save take the asset's and the Edit's locks in one order (on PostgreSQL).
- **Debounce:** saves in quick succession render once.
- **Startup:** `requeuePendingRenders` queues every unrendered Edit.
- **Sizes:** the Display image's long edge is `min(3840, edited)`, and the edited Preview a letterboxed 200 px square.
- **Gone and unrenderable:** a purged asset stores nothing; an original gone or undecodable is recorded rendered as the
  original.
- **Failures:** images a failed render left are replaced; a failing render is tried again after a delay; a fatal error
  fails that render alone (the `beforeQueuedRender` hook); a stopped service queues nothing.

### Server: unit

- **`EditRecipeTests`:** a full Edit parses, rounds and writes every key back; every refusal of **Validation**; the
  identity whatever the Crop tool's settings; the edited size; a crop normalized by its edges; curve points closer than
  0.001 refused; every recipe of the vectors parses and has the edited size they give.
- **`EditRendererTests`** on synthetic Mats: the tone steps, the curve and its tables, saturation, vibrance, vignette
  and grain, and the geometry against the spec's formulas; the vectors; the identity gives back the image; the composed
  warp and its transparency; rotate, flip and crop placement; Straighten at 90° equals a quarter turn; Exposure +1
  doubles linear values; a long edge caps the output and a small scale shrinks first; auto-straighten on lines drawn at
  2.7°, and none where no lines agree.

### Server: controller

- **`EditControllerTests`:** the state, a save and the render status; the upright size; a 409 with `state`; a 400 for a
  refused Edit, body or asset; auto-straighten's angle and refusals, a 503 while every slot is taken and a 400 for an
  undecodable original; a 403 without Edit images and a 404 for another Library's asset.
- **`ContentViewControllerTests`:** the edited images served at the revision and not once the asset has none; an
  earlier revision redirected uncached, a signed URL keeping its signature; a revision above, or 0, a 404; a non-digit
  `v`, or none for a Display image, a 400; a Guest limited by `visibleOnly`.
- **`AssetActionControllerTests`** (the detail's `edit`, `editable`), **`MapControllerTests`** (`editRev`),
  **`SearchResultsControllerTests`** (a thumbnail by revision), and **`RouteTableTests`** (every new route).

### Parity and client modules (Node)

`test/resources/edit/vectors.json` holds the groups `tone`, `curves`, `saturation`, `geometry` and `effects`, each with
its tolerance; `_doc` describes them. `EditRecipeTests` and `EditRendererTests` read it. No generator is kept: the values
are the Scala functions' output.

`scripts/test-js/`, run by `make test-js` (`node --test`, no dependency), which `make test-local` includes:
- `edit.test.mjs`: the parity of `geometry.js`, `tone.js` and `recipe.js` with the vectors, normalization, the sliders'
  readouts, `mapBox` and `effectRegion`;
- `edit-adjust.test.mjs`: the Edit panel's changes (`adjust.js`);
- `edit-session.test.mjs`: the session over a fake server: serial saves, Undo and Redo, conflicts, failures, discard and
  the states adopted;
- `content-urls.test.mjs`: the content URLs and a cell's image repointed;
- `histogram.test.mjs`: the Histogram's paths.

### Frontend: browser check

The WebGL2 renderer, the Edit panel, the stage and the Crop tool have no automated test. They are checked in the
browser, on a second instance over a copied `data/`, never the dev database:
- E, C, O, X, Esc, Enter, `\`, Ctrl+Z and Ctrl+Shift+Z;
- the four Detail panel sides with the Edit panel opposite;
- a slider drag, its keys and a Crop tool session each as one change;
- rotate by drag staying inside;
- Auto;
- Undo and Redo forgotten on closing and stepping;
- the warning's triggers and its three choices, Back, `beforeunload`, `pagehide` and `pageshow`;
- a 409 from a second tab;
- the grid cell's Preview updating after the render, and the Display image after closing the panel;
- an unchanged wide-gamut photo looking the same with the panel open;
- Face highlights mapped on a cropped asset;
- Show original (toggle and held `\`) as a Guest on an edited asset, released on stepping;
- both themes.

## Documentation

- **`CONTEXT.md`:**
  - **Edit panel**, **Edit** (the one Edit kept per asset, Undo and Redo while the panel is open, Revert to original;
    _Avoid_: Recipe, Develop settings, Adjustment layer in user-facing text; not the Edit button of a Filtered search),
    **Effects**, **Crop tool**, **Level tool**, **Display image** (_Avoid_: Rendition, Proxy, Thumbnail) and **Show
    original**;
  - **Edit images** and **Edit metadata** in **Permission**;
  - **Preview** (an edited asset's shows its Edit, except in Curation), **Dock side**, **Detail panel**, **Histogram**
    and **Clipping blink** extended to the Edit panel.
- **`docs/image-edits.md`**: the Edit, its geometry, per-pixel math and edge rule, the two renderers, what agrees
  and how far, the crop's rules, auto-straighten, the parity rule and the vectors. The root `AGENTS.md` routes to it.
- **`altitude/AGENTS.md`:**
  - an **Image edits** section: the Edit, the stored Edit and its lock order, rendering, the renderer, parity, edited
    images and caching, the routes, and their tests;
  - **Permissions on routes**, the queues and shutdown, **Library stats and purging**, startup, **Video**'s content
    serving, `BaseDao`'s `forNoKeyUpdate`, **Build & Test Commands** (`make test-js`) and **Key Files to Know**.
- **`altitude/views/AGENTS.md`:**
  - an **Edit panel** section: open and closed, the dock, layout, tabs, sliders, the crop's rules, the curve, the
    Histogram and Clipping blink, the header, the session, the warning, the stage, Show original, the Crop tool, Auto and
    the keys;
  - **Detail panel** (the grid's areas, Dimensions), the Escape chain, the `data-app-*` attributes and events, the
    module map (`edit/`, `common/edit-keys.js`, `histogram.js`, `content-urls.js`), the color rules (`--edit-track-*`,
    `--edit-slider-track`), **Stack** (interact.js drives the Crop tool) and the style guide list.
- **`docs/test-coverage.md`:** an **Image edits** section: what each suite covers and the gaps (no automated test of the
  WebGL2 renderer or the Edit panel, races and shutdown not tested, auto-straighten on synthetic lines only).
- **`docs/READINESS.md`:** ten Permissions, the edited images behind the repository gate and their caching, their atomic
  writes, Edit history as Partial, and the Display image among the derivatives.
- **`static/js/lib/README.md`:** interact.js drives the Crop tool as vendored, linked by the main page and the style
  guide.
- **`ContentViewController`'s doc comment:** a content URL's bytes never change for a given `v`.

## Tasks

- **Glossary.** The `CONTEXT.md` terms and the Permission list, settled before code names anything.
- **A. The Edit on the server.** `EditRecipe`, the schema, `AssetEditDao`, `AssetEditService`, `Permission.EditImages`,
  `EditController` (all but auto-straighten), and the Asset detail JSON's `edit`, `editable` and `editRefusal`. Unit,
  integration and controller tests, test-first.
- **B. Rendering on the server.** `EditRenderer`, the vectors, `EditRenderService` with its queue, debounce, retries and
  startup requeue, the edited images and their purge, the content route's `v` and `display`, `Util.previewUrl`, the
  map's `editRev`, and auto-straighten. Test-first.
- **C. The shared client modules.** `recipe.js`, `geometry.js`, `tone.js`, `renderer.js`, `make test-js`, the stage's
  sources (`edit-stage.js`), `common/content-urls.js` and `common/histogram.js`, Face highlight mapping, the "Edited"
  badge and the edited size in Dimensions, Show original, and the grid's Previews after a render.
- **D. The Edit panel.** The dock and grid, the toggle and keys (`common/edit-keys.js`), the panel and its controls
  (`adjust.js`), the Histogram and Clipping blink, the curve editor, Before/after, `edit-session.js` with Undo and Redo,
  the warning of changes not saved, and the Crop tool and Level tool on interact.js.
- **E. Documentation.** `docs/image-edits.md`, the guides, `docs/READINESS.md`, `docs/test-coverage.md`, the lib
  README and the style guide's specimens.
