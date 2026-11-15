# Edit render parity: size, resampling, sRGB and orientation

## Goals

What the Edit panel's canvas shows is what the server saves, as closely as two renderers can draw one picture:

- **Size.** The canvas is never drawn finer than the Display image it stands for, so the stage does not soften when the
  Display image replaces the canvas.
- **Resampling.** The server shrinks the frame to the output's scale with area averaging before the warp, as the client
  fits its source, so fine detail does not alias in the Display image where the canvas shows none.
- **Color.** An original with an ICC profile other than sRGB (Display P3, Adobe RGB, a gray profile) is converted to
  sRGB by both renderers, by one written conversion checked by the shared vectors, so an Edit, the Display image and the
  canvas show its colors as the browser shows the original, out-of-gamut colors clipped.
- **Orientation.** The stage refuses to draw an original the browser turns or mirrors otherwise than the server, in any
  of the eight ways, not only a quarter turn on a non-square photo.
- **Parity check.** A pixel comparison of the WebGL2 renderer with the server's render, run in headless Chrome.

Out of scope:
- The original's Preview, Face images, auto-straighten and every import measurement keep reading the original's values
  as sRGB. Auto-straighten reads gray edges, which the conversion barely moves.
- CMYK originals, and RGB profiles described only by lookup tables (`A2B0` with no colorants and TRCs): both renderers
  keep reading their values as sRGB, as now.
- The Display image's encoding (lossy WebP 90, 4:2:0). The bundled OpenCV 4.9 writes no AVIF, and a lossless WebP is
  several times larger.
- Shrinking that varies across a strongly keystoned image: the server shrinks by the output's nominal scale, while the
  client's mipmaps follow each pixel.
- A full-size export.

## Current behavior

Verified against the code on `trunk` (`8a506e5d`):

- `edit-stage.js` `outputFor` draws the canvas at the box's device pixels, bounded only by the region's own size
  (`outputSize(region, devicePixels)`) and `MAX_TEXTURE_SIZE`; the Display image is `outputSize(edited, 3840)`
  (`Const.Edit.DISPLAY_LONG_EDGE`, `EditRenderService.editedImagesOf`).
- `EditRenderer.shrunk` brings the frame down with `INTER_AREA` only when the output's scale is below a half, to twice
  that scale; at a scale from a half to 1 the warp's `INTER_LINEAR` samples the frame with no prefilter. A 6000 px
  original rendered at 3840 px is a scale of 0.64.
- `renderer.js` decodes with `colorSpaceConversion: "none"` and both renderers read the values as sRGB.
  `ImageUtil.exifOrientation` reads metadata-extractor's `Metadata`, which also carries an `IccDirectory` whose colorant
  and TRC tags (`rXYZ`, `gXYZ`, `bXYZ`, `rTRC`, `gTRC`, `bTRC`, `kTRC`) hold their raw bytes (`setByteArray`), for JPEG
  (APP2), PNG (`iCCP`), WebP (`ICCP`) and TIFF (tag 34675).
- `edit-stage.js` `checkOrientation` refuses a bitmap whose size is the server's upright size swapped, on a non-square
  original only.
- The shaders of `renderer.js` have no automated test (`../../../docs/test-coverage.md`, Image edits).

## Decisions

| Area | Decision |
|---|---|
| Canvas size | The canvas's output long edge is at most the region's long edge at the Display image's scale: `round(regionLong × min(1, DISPLAY_LONG_EDGE / editedLong))`. Outside the Crop tool the region is the edited image, so on a large high-DPI screen the canvas is the Display image's exact size, scaled up by CSS as the Display image's `<img>` is. The Crop tool's whole frame is capped at the same scale, so Done changes nothing. |
| The constant on the client | `DISPLAY_LONG_EDGE` in `geometry.js`, checked against a `displayLongEdge` entry of the vectors' `effects` group on both sides (`EditRendererTests` checks it equals `Const.Edit.DISPLAY_LONG_EDGE`). |
| Server shrink | Whenever the output's scale is below 1, the frame (after any reduced JPEG decode) is brought down with `INTER_AREA` to the output's scale itself, an 8-bit frame in 16 bits as now, so the warp samples it at about 1:1, as the client samples its fitted source. At 1 or above, no shrink. |
| Color: what is converted | An RGB profile with the three colorants and three TRCs, or a gray profile with `kTRC`. Anything else (no profile, CMYK, Lab, an RGB profile without colorants or TRCs, a profile that does not parse) is read as sRGB, as now, logged on DEBUG. A profile that is sRGB within tolerance (every matrix entry within 0.002 of the identity's, every curve within 0.5/255 of the sRGB EOTF at 256 evenly spaced inputs) is no conversion, so most photos take no extra step. |
| Color: the descriptor | **Source color** (`SourceColor`): a 3 × 3 matrix from the original's linear RGB to linear sRGB, and one curve per channel: `{"gamma": g}`, `{"parametric": [type, g, a, b, c, d, e, f]}` (the ICC `para` function types 0 to 4, unused parameters 0), or `{"sampled": [v₀ … vₙ₋₁]}` (a `curv` table over 65535). The matrix is `M_sRGB⁻¹ · [rXYZ gXYZ bXYZ]`, with `M_sRGB` the sRGB profile's Bradford-adapted D50 colorants (0.4360747, 0.3850649, 0.1430804 / 0.2225045, 0.7168786, 0.0606169 / 0.0139322, 0.0971045, 0.7141733). A gray profile is the identity matrix and `kTRC` for all three channels. `curv` with no entry is gamma 1, with one entry gamma u8Fixed8. |
| Color: the step | A new **step 0, To sRGB**, before step 1, on the sampled color (after the warp's bilinear sampling, on both sides): each channel through its curve's **table** (4096 entries, entry i the curve at i/4095, float32, read as the tone tables are read), the matrix, each linear value clamped to [0, 1] (relative colorimetric, out-of-gamut colors clipped), then the sRGB OETF. Steps 1 to 11 are unchanged. Before/after's Before converts too: the conversion is how the original's colors are read, not part of the Edit. |
| Color: who reads the profile | The server, from the original's metadata, read once with its orientation (`ImageMetadataReader`). The Edit's state carries the descriptor as `sourceColor` (null for sRGB or none) beside `uprightWidth` and `uprightHeight`, known exactly when they are. The client never parses a profile. |
| Color: the browser | The client decodes with `colorSpaceConversion: "none"` and converts itself. A browser that converts anyway is found by a probe: a 1 × 1 JPEG constant carrying a profile with sRGB colorants and gamma 1.0 curves, decoded as the renderer decodes, uploaded and read back from a framebuffer. If its channels are not the stored values (±2), that browser cannot draw an original whose `sourceColor` is not null: the stage refuses it ("This browser converts this photo's colors itself, so it cannot draw its Edit"), as it refuses a wrong orientation. Untagged and sRGB originals are unaffected. |
| Behind the `<img>` | Kept: while the Edit is the identity the canvas draws behind the original's color-managed `<img>`. With step 0 the canvas matches it within the sRGB gamut; a wide-gamut photo's out-of-gamut colors are what still change on the first change. |
| Orientation check | Once per decoded original, the bitmap is compared with the original's Preview (`previewUrl(repo, id, 0)`, which the server turns by the same EXIF read as the render). The Preview's content is the bounding box of its pixels with alpha above 0 (its letterbox is transparent). Both are drawn into 16 × 16 gray grids, and the bitmap's grid is compared with the Preview's under each of the eight turns and mirrors, by mean absolute difference over 0 to 255. It agrees when the unturned grid's difference is at most 6, or at most 2 above the smallest of the eight, so a flat or symmetric photo passes. Otherwise it is refused with the existing orientation message. A Preview that does not load, or has no opaque pixel, skips the check (logged). The size check stays. A refusal is kept for the asset rather than retried on each presentation. |
| Parity check | `make test-edit-parity`, outside `make test` since it needs Chrome: a Node script (no npm dependency; Node's own `WebSocket` for CDP and `node:http` to serve the files) starts the installed Chrome headless with SwiftShader in a scratch profile, opens a harness page that imports `renderer.js`, draws every case at its edited size from the full-size source, and compares the canvas with the server's golden render: per channel at most 3 levels apart and on average at most 0.5, alpha exactly, pixels within 1 px of a coverage edge excluded. Cases (fixture and Edit) are listed once in `test/resources/edit/parity/cases.json`, which both sides read. |
| Goldens | The server's renders of the cases, as PNG, in `test/resources/edit/parity/goldens/`, written by `make edit-goldens` (a test-scope main, `EditGoldens`). `EditRendererTests` checks the server still renders each one exactly; a change to the math writes them again in the same change, as the vectors are. |

## Design

### Server

- **`models/SourceColor.scala`**: the descriptor, its curves (`Gamma`, `Parametric`, `Sampled`), `toJson` in the shape
  above, and `curveValue(curve, x)` (the ICC formulas: `para` clipped to [0, 1], a negative base held at 0; `Sampled`
  linear between entries over (n − 1)·x).
- **`util/SourceColorReader.scala`**: `of(metadata): Option[SourceColor]` from the `IccDirectory`'s colorant and TRC tag
  bytes (`XYZ ` as three s15Fixed16; `curv`; `para`), the sRGB test, and the reasons it answers None.
- **`EditRenderer`**: public `sourceCurveTable(curve): Array[Float]` and `toSrgb(r, g, b, tables, matrix)` for the vectors.
  `render(original: Path, …)` reads the descriptor from the metadata it already reads for the orientation, and
  `render(upright: Mat, …)` takes `sourceColor: Option[SourceColor] = None`. `toned` runs step 0 before the table
  lookups when there is one. `shrunk` shrinks to the scale itself whenever it is below 1.
- **`AssetService.uprightSize`** becomes one metadata read answering the upright size and the source color
  (`OriginalFacts`). `AssetEditService.get` and `editOf` put both on `EditState` (`sourceColor`, known when `upright`
  is). `EditController.stateJson` writes `sourceColor`. `Api.Field.Edit.SOURCE_COLOR`.

### Client

- **`tone.js`**: `sourceCurveTable(curve)` and `toSrgb(rgb, tables, matrix)`, the twins of the server's, under the
  vectors.
- **`renderer.js`**: `setSourceColor(sourceColor | null)` uploads the three curve tables as a second 64 × 64 RGBA32F
  texture and the matrix as a `mat3` (column-major); DEVELOP runs step 0 after sampling when it is set, for After and
  Before alike. `probeColorConversion()` runs the probe once and keeps its answer. `decodeUpright` is unchanged.
- **`geometry.js`**: `DISPLAY_LONG_EDGE`.
- **`edit-stage.js`**: `outputFor(region, edited)` applies the cap. The stage hands the renderer the source color known
  with the server's upright size, and a state learned later that carries them draws again (the existing
  `uprightLearned` path covers both). A state with no upright size keeps the ones known for the asset.
  `decodedOriginal` runs the orientation check, and the color probe for an original with a source color, and keeps a
  refusal.
- **`edit/orientation.js`** (DOM-free): `grayGrid(pixels, width, height, rect)` and
  `orientationAgrees(bitmapGrid, previewGrid)` with the eight turns and mirrors of a grid; the DOM part (drawing into an
  `OffscreenCanvas`, finding the Preview's opaque box) stays in `edit-stage.js`.

## Tasks

Server tasks are red-green-refactor. Subagents edit disjoint files and never run mill; the parent compiles and tests.

### A. The server shrinks to the output's scale

1. `EditRendererTests`: a frame of 1 px vertical stripes (0 and 255) rendered at a scale of 0.64 is a flat gray, every
   pixel within 8 levels of 128 (red now). The existing test "a scale below a half shrinks the image before the warp"
   is reworded for the new rule and checks a scale of 0.75 too.
2. `EditRenderer.shrunk` and its comment; the render's comments that cite the half.
3. Docs: `../../../docs/image-edits.md` (The two renderers: Shrink; What agrees: the server and the client now both sample a
   source fitted to the output).

### B. The canvas at the Display image's size

1. `geometry.js` `DISPLAY_LONG_EDGE`; the vectors' `effects` group gets `displayLongEdge`; `edit.test.mjs` and
   `EditRendererTests` check it.
2. `edit-stage.js` `outputFor` with the cap, for the draw and for the source's fit, in the Crop tool too.
3. Browser check on the dev server with a dev login: the canvas's `width` and `height` equal the Display image's
   natural size for an edited asset with an edited long edge above 3840, with the device pixel ratio forced to 2
   (CDP `Emulation.setDeviceMetricsOverride`); an asset under 3840 is drawn at the box's device pixels as before.
4. Docs: `../../../docs/image-edits.md` (The output; the client's renderer), `altitude/views/AGENTS.md` (Edit panel: the
   stage's canvas size), `edit-stage.js`'s header comment.

### C. Source colors to sRGB

1. Test helper `IccProfiles` (test scope): writes a matrix/TRC profile from primaries, a white point (Bradford to D50)
   and curves (`curv` gamma, `curv` table, `para` type 3), a gray `kTRC` profile and a CMYK stub, and embeds a profile
   in a JPEG (APP2 `ICC_PROFILE` segments) and a PNG (`iCCP`).
2. `SourceColorReader` unit tests, red first: a Display P3 profile (D65, the sRGB curve as `para` 3) gives the known
   P3-to-sRGB matrix (1.2249, −0.2247, 0 / −0.0420, 1.0419, 0 / −0.0197, −0.0786, 1.0979) within 10⁻³; Adobe RGB
   (1998) gives gamma 563/256; a sampled `curv`; a gray profile; Java's own sRGB profile
   (`ICC_Profile.getInstance(ColorSpace.CS_sRGB)`) and a 1024-entry sRGB `curv` give None; a CMYK, a LUT-only and a
   truncated profile give None. Read through `ImageMetadataReader` from the JPEG and the PNG.
3. `EditRendererTests`: a P3-tagged PNG's gray (128, 128, 128) renders as itself within 1; its (200, 100, 50) renders
   as the formula computed in the test from the matrix and the curves within 1; out-of-gamut P3 green clips to sRGB
   green; an untagged image renders byte for byte as before.
4. `SourceColor`, `SourceColorReader`, step 0 in `EditRenderer`, `OriginalFacts`, `EditState.sourceColor`,
   `stateJson`. `AssetEditServiceTests` (both engines): `get` of a P3-tagged original carries its source color and an
   untagged one none. `EditControllerTests`: the state JSON's `sourceColor` in both shapes.
5. Vectors: a `color` group (per descriptor: curve table entries, lookups between entries, the matrix, conversions of
   given colors, tolerance 10⁻⁵), written from the Scala functions; `_doc` updated. `tone.js` twins;
   `edit.test.mjs` meets the group.
6. `renderer.js`: the curve texture, the `mat3`, step 0 in DEVELOP, `setSourceColor`, the probe with its JPEG constant
   (written once by a throwaway script that builds the profile with `IccProfiles`, its origin in a comment).
   `edit-stage.js`: the source color handed to the renderer, the probe, the refusal.
7. Browser check on a second app instance over a copied `../../../data` (the dev database is not imported into): import a
   P3-tagged photo with saturated and in-gamut regions; with the panel open and the Edit the identity, the canvas
   drawn in front (Before/after on) reads within 2 levels of the `<img>` drawn into a 2D canvas over in-gamut pixels;
   after an Exposure change is saved and rendered, the Display image matches the canvas within the parity check's
   tolerance; an untagged photo is unchanged.
8. Docs: `../../../docs/image-edits.md` (step 0, the descriptor, the Edit's state, the two renderers, the Parity group; the
   paragraph on color management rewritten), `altitude/AGENTS.md` (Image edits: the state's `sourceColor`),
   `altitude/views/AGENTS.md` (the probe and its refusal, behind the `<img>`), `CONTEXT.md` if the refusal's wording
   names a term, `docs/test-coverage.md`.

### D. The orientation check against the Preview

1. `edit/orientation.js` and `scripts/test-js/edit-orientation.test.mjs`, red first, on synthetic grids: the same
   picture agrees; turned 90°, 180° or 270° or mirrored either way it is refused; a flat grid and a mirror-symmetric
   one agree; noise of ±4 levels agrees.
2. `edit-stage.js`: the check in `decodedOriginal`, the Preview's opaque box, the kept refusal, the skip when the
   Preview does not load; the size check stays.
3. Browser check on the second instance: a PNG with an `eXIf` orientation of 3 or 2, whichever this Chrome and the
   server read differently, is refused with the orientation message (if they agree on both, a bitmap turned in the
   console through the stage's check is); an ordinary JPEG with orientation 6 draws.
4. Docs: `../../../docs/image-edits.md` (Geometry: the client's check), `altitude/views/AGENTS.md`, `docs/test-coverage.md`.

### E. The pixel parity check

1. Fixtures in `test/resources/edit/parity/`: `scene.png` (320 × 240, cut from an existing test photo, untagged),
   `scene-p3.png` (the same values tagged Display P3 by `IccProfiles`), `stripes.png`. `cases.json`: the identity, each
   step alone at a strong setting (Exposure, the tone sliders, a curve, white balance, saturation and vibrance,
   Clarity, Sharpen, Vignette, Grain), Straighten with a crop, a perspective with `keepInside` off, a quarter turn and
   flip, and one Edit of everything, on `scene.png`; the identity and Exposure on `scene-p3.png`.
2. `EditGoldens` (test-scope main) and `make edit-goldens`; `EditRendererTests` checks each golden exactly.
3. `scripts/edit-parity/run.mjs` and `harness.html`; `make test-edit-parity`. The harness reads the goldens through the
   renderer's own decode and a framebuffer read, so no PNG decoder is needed in Node, and reports per case the largest
   and mean differences and the worst pixel.
4. Docs: `../../../altitude/AGENTS.md` (build and test commands), `docs/image-edits.md` (Parity: the pixel check and how to
   write the goldens again), `docs/test-coverage.md` (the renderer's gap narrowed to: not run by `make test`).

## Verification

- `make test-unit`, `make test-js`, `make test-sqlite`, `make test-psql` and `make test-controllers` green; `make lint`,
  and a compile after it.
- `make test-edit-parity` green, with each case's numbers reported; any case past the tolerance is a parity bug, not a
  tolerance to widen.
- A render of a 24 MP JPEG at 3840 px timed before and after A and C, medians of five: the conversion and the
  shrink together add no more than 30%.
- The browser checks of B.3, C.7 and D.3, with what each showed.
