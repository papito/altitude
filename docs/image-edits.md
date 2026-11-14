# Image edits: the Edit, its math and its renderers

What an asset's Edit is, and how its two renderers draw it: the geometry, the per-pixel math, the edge rule, the sizes,
auto-straighten, and the rule that keeps the two renderers drawing the same picture. For the words used here (Edit,
Edit panel, Crop tool, Level tool, Display image, Show original) see [CONTEXT.md](../CONTEXT.md). The stored Edit, the
render queue, the edited images and the routes are **Image edits** in [altitude/AGENTS.md](../altitude/AGENTS.md), and the
Edit panel and Asset detail's stage are **Edit panel** in [views/AGENTS.md](../altitude/views/AGENTS.md).

Sources: the Edit, [EditRecipe.scala](../altitude/src/altitude/core/models/EditRecipe.scala) and its client twin
[recipe.js](../altitude/static/js/edit/recipe.js); the server's renderer,
[EditRenderer.scala](../altitude/src/altitude/core/util/EditRenderer.scala); the client's geometry, math and WebGL2
renderer, [geometry.js](../altitude/static/js/edit/geometry.js), [tone.js](../altitude/static/js/edit/tone.js) and
[renderer.js](../altitude/static/js/edit/renderer.js); the Crop tool's rules, [adjust.js](../altitude/static/js/edit/adjust.js);
the shared vectors, [vectors.json](../altitude/test/resources/edit/vectors.json).

## The Edit

An Edit is one JSON object, `version` 1 (the format's version), with every key always present: what `asset_edit.recipe`
stores, what the client sends and what `EditRecipe.toJson` writes, `version` first.

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
| `flipH`, `flipV` | boolean | Mirrored after the turn, in the turned frame. |
| `straighten` | −45 to 45, 0.1 | Degrees, positive clockwise. |
| `perspectiveV`, `perspectiveH` | −100 to 100, 1 | Keystone. |
| `crop` | `null`, or `{x, y, w, h}` in [0, 1] | A fraction of the frame (below); `null` is the whole frame. |
| `aspect` | `original`, `free`, `1:1`, `5:4`, `7:5`, `3:2`, `16:9` | The Crop tool's lock, a ratio written landscape. |
| `portrait` | `null`, `true`, `false` | The lock's orientation; `null` follows the frame's. |
| `keepInside` | boolean | The crop may not reach past the image's outline. |
| `cropFitted` | boolean | The crop was never set by hand, so it re-fits to the geometry. |
| `exposure` | −5 to 5, 0.05 | EV. |
| `contrast`, `highlights`, `shadows`, `whites`, `blacks`, `gamma` | −100 to 100, 1 | `gamma` is the slider's position: γ = 2^(`gamma`/100), 0.5 to 2. |
| `curve` | 2 to 16 points `[x, y]` in [0, 1], 0.001 | The master tone curve, x increasing. |
| `temperature`, `tint`, `vibrance`, `saturation`, `clarity`, `vignette` | −100 to 100, 1 | A negative Vignette darkens the corners. |
| `sharpen` | 0 to 150, 1 | |
| `grain` | 0 to 100, 1 | |

The Edit panel's sections (`SECTIONS` in `recipe.js`) group the keys: Crop & rotate (the turn, the flips,
Straighten, the perspective, the crop and the four Crop tool settings), Light (Exposure to Gamma, and the curve), Color
(Temperature, Tint, Vibrance, Saturation) and Effects (Sharpen, Clarity, Vignette, Grain).

**The identity** is an Edit that changes no pixel: `rotate` 0, no flip, Straighten and the perspective 0, no crop, every
slider 0 and the curve `[[0, 0], [1, 1]]`. `aspect`, `portrait`, `keepInside` and `cropFitted` do not count. An asset is
edited when its Edit is not the identity (`isIdentity`, on both sides).

**Validation and rounding.** `EditRecipe.parse` is the server's one way in, and `recipe.js`'s `normalize` rounds as it
does, so what the client sends is what the server keeps. It refuses, with a `ValidationException` saying why, an object
that is no JSON object, an unknown or missing key (no default is filled in), a `version` other than 1, a value of the
wrong type (a number that is NaN included), a `rotate` that is not a quarter turn, an `aspect` it does not list, a slider
outside its range once rounded, a crop and a curve out of shape (below). Every number is rounded to its step,
`Math.round` taking a half up, as the browser's does. A **crop** must be `null` or exactly `{x, y, w, h}`: its left and
top edges and its right and bottom edges (x + w, y + h) are rounded to 4 decimals and its width and height are the
differences, so a crop that reaches the frame's edge keeps reaching it, however its left or top rounds; an edge outside
[0, 1] or a width or height under 0.01 (`MIN_CROP`) is refused. A **curve** must hold 2 to 16 points, each x at least
0.001 (`MIN_CURVE_SPACING`) past the one before as sent, so rounding never puts two points on one x; then both
coordinates are rounded to 3 decimals and a point outside [0, 1] is refused. Whether a crop stays inside the image's
outline is not checked: that is the Crop tool's rule (**The crop's rules**), and a renderer draws what lies outside the
image as transparent.

## Geometry

Coordinates are continuous pixels: the top-left corner at (0, 0), y down, a pixel's center at +0.5. Angles are positive
clockwise. Everything starts from the **upright original**, the original turned by its EXIF orientation as a browser
shows it and the Preview is made, W₀ × H₀ (`AssetService.uprightSize` on the server: the stored size swapped for the
orientations 5 to 8; the decoded bitmap's size, or the server's `uprightWidth` and `uprightHeight`, on the client, which
draws nothing live from a bitmap a quarter turn from the server's size: its geometry would be on the wrong frame).

1. **Turn and mirror** (`turn`). The point (x, y) of the upright original turns clockwise by `rotate`: to (H₀ − y, x) at
   90, (W₀ − x, H₀ − y) at 180, (y, W₀ − x) at 270. That is the **frame**, W × H, W₀ × H₀ swapped for a quarter turn
   (`frameSize`). Then `flipH` mirrors x to W − x and `flipV` y to H − y, in the turned frame.
2. **Warp** (`Warp` on the server, `createWarp` on the client), in normalized coordinates around the frame's center:
   c = (W/2, H/2), s = max(W, H)/2, u = (p − c)/s. With the keystones a = 0.55 · `perspectiveH`/100 and
   b = 0.55 · `perspectiveV`/100, u goes to u/(1 + a·uₓ + b·u_y), which is turned by θ = `straighten`° and scaled back:
   p′ = R(θ)·u′·s + c, R(θ) = [[cos θ, −sin θ], [sin θ, cos θ]]. The warped image keeps the frame's size. Sampling reads
   the inverse (`inverse`, `unwarp`): q = (p − c)/s, u = R(−θ)·q, w = 1 − a·uₓ − b·u_y, and the source is u/w · s + c;
   there is none where w ≤ 0.001 (`MIN_DENOMINATOR`), beyond the horizon of the perspective's plane. With Straighten and
   both perspectives at 0 the warp moves nothing (`moves`).
3. **Crop.** `crop` is a fraction of W × H. The **edited size** is round(w · W) × round(h · H), each at least 1
   (`editedSize`), the whole frame with no crop.

**The image's outline** in the frame (`outline`) is the frame's four corners, top left, top right, bottom right, bottom
left, pushed through the warp, as fractions of W and H: a convex quadrilateral, which "keep the crop inside the image"
holds the crop's four corners in.

**The output** is the cropped region rendered at `outputSize(editedWidth, editedHeight, longEdge)`: the edited size, or,
when `longEdge` is shorter than its long edge, that long edge and the short side in step, round(short · longEdge / long),
at least 1. The Display image is rendered at a 3840 px long edge at most (`Const.Edit.DISPLAY_LONG_EDGE`), the edited
Preview from the same render; the Edit panel's canvas at the device pixels of the box it is shown in, at most the region
it shows.

**Sampling and the edge rule.** Output pixel (i, j), integer column and row from the top left, shows the frame point
(crop.x · W + (i + 0.5) · crop.w · W / outW, crop.y · H + (j + 0.5) · crop.h · H / outH), whose source the inverse warp
gives, turned back into the upright original. Its color is the image sampled bilinearly there, the edge pixels repeated
beyond the last pixel centers (OpenCV's `BORDER_REPLICATE`, a texture's `CLAMP_TO_EDGE`), so no color is ever mixed with
a transparent border. The pixel is **covered** when the inverse exists (w > 0.001) and its source lies within the frame,
edges included (`Warp.covers`; every pixel when the warp moves nothing): a covered pixel keeps the image's alpha, and any
other is fully transparent, a hard edge with no partial coverage. Beyond the horizon (w ≤ 0.001, a negative w included)
both renderers still divide by w, OpenCV's `warpPerspective` by any w but 0 and the shader by w held at least 10⁻⁶ from
0, and sample the projected point, on the far side of the frame's center, its edges repeated; the pixel is uncovered, so
that color never shows.

**A box through the Edit** (`mapBox`, the Face highlights): the four corners and the center of a box of the upright
original are turned, warped and taken into the crop as fractions of the edited image; the box drawn is the corners'
bounding box cut to the edited image, and none when the center falls outside the crop or beyond the horizon.

## Per-pixel math

The steps run on sRGB-encoded values v in [0, 1]: an 8-bit input over 255, a 16-bit one over 65535 (the server decodes
16 bits; a browser decodes 8). Alpha passes through to the coverage rule.

Steps 1 to 6 act on each channel alone, so both renderers bake them into a **table** per channel (`tables`): 4096 entries
(`TABLE_SIZE`), entry i the steps run on i/4095, held as 32-bit floats. A value is read from its channel's table
(`lookup`) at f = clamp(v) · 4095, i = ⌊f⌋: entry 4095 when i ≥ 4095, else entry i + (entry i+1 − entry i) · (f − i). The
client lays the 4096 entries out as a 64 × 64 RGBA32F texture (rows of 64, since WebGL2 promises textures of 2048 a
side), read with two texel fetches and a mix, so no float filtering is needed.

1. **To linear:** the sRGB EOTF, v/12.92 at v ≤ 0.04045, else ((v + 0.055)/1.055)^2.4.
2. **White balance:** with t = `temperature`/100 and τ = `tint`/100, the gains are ((1 + 0.28t)(1 + 0.08τ), 1 − 0.2τ,
   (1 − 0.28t)(1 + 0.08τ)), each divided by 0.2126·g_r + 0.7152·g_g + 0.0722·g_b, so a neutral gray keeps its luminance
   (`whiteBalanceGains`).
3. **Exposure:** × 2^`exposure`.
4. **To sRGB:** the sRGB OETF, 12.92·l at l ≤ 0.0031308, else 1.055·l^(1/2.4) − 0.055, unclamped.
5. **Tone** (`tone`), in this order on the value v:
   - **Black and white points:** v ← (v − bp)/(wp − bp), bp = −0.1 · `blacks`/100, wp = 1 − 0.15 · `whites`/100.
   - **Shadows:** v += 0.95 · (`shadows`/100) · x(1 − x)², x = clamp(v).
   - **Highlights below 0:** v moves toward the shoulder v − u + u/(1 + 1.6u), u = max(0, v − 0.45), by the fraction
     −`highlights`/100. **Above 0:** v += 0.3 · (`highlights`/100) · smoothstep((v − 0.35)/0.65), where smoothstep(t) is
     x²(3 − 2x) of x = clamp(t).
   - **Gamma:** v ← v^(1/γ), γ = 2^(`gamma`/100), for v > 0.
   - **Contrast:** with c = `contrast`/100 and x = clamp(v), S = ½ − ½cos(πx) for c > 0 and acos(1 − 2x)/π for c < 0;
     v ← x + 0.85 · |c| · (S − x).
   - **Clamp** to [0, 1].
6. **Curve** (`Curve`, `createCurve`): a monotone cubic Hermite through the points, held flat outside the first and last
   x and clamped to [0, 1]. The secants are dy / max(10⁻⁶, dx). The tangents: the end ones the end secants, an inner one
   the mean of its two secants, or 0 where they differ in sign or one is 0; then, segment by segment in order, both
   tangents 0 on a flat segment, or, with α and β the tangents over the segment's secant, both scaled by 3/√(α² + β²)
   when α² + β² > 9 (Fritsch–Carlson). Within a segment of width h at t = (x − x₀)/h, the value is
   (2t³ − 3t² + 1)·y₀ + (t³ − 2t² + t)·h·m₀ + (−2t³ + 3t²)·y₁ + (t³ − t²)·h·m₁.
7. **Saturation and vibrance** (`saturate`, skipped when both are 0): Y = 0.2126r + 0.7152g + 0.0722b, C = max − min,
   k = (1 + `saturation`/100) · (1 + vib · (vib > 0 ? 1 − min(1, 1.6C) : 1)), vib = `vibrance`/100; each channel becomes
   Y + (channel − Y) · k.

The uncovered pixels are made transparent here, and **L**, the Rec. 709 luminance of the step 7 result, is what the rest
reads.

8. **Clarity and Sharpen**, each a delta of L added to all three channels, each skipped at 0. Clarity: Lb = L blurred
   by a Gaussian of σ = 0.015 · the output's short side; delta (L − Lb) · 1.4 · (`clarity`/100) · (1 − 0.7(2L − 1)²).
   Sharpen: Ls = L blurred by σ = max(0.4, 0.8 · output long edge / 3840); delta (L − Ls) · 2.2 · (`sharpen`/100). The
   blur is OpenCV's `GaussianBlur` of a float image with no size given: round(8σ + 1) | 1 taps, each exp(−x²/2σ²),
   normalized (`gaussianHalfKernel`), the borders mirrored (`BORDER_REFLECT_101`).
9. **Vignette**, at the integer column and row (i, j) of the output, with no +0.5, of the outW × outH output:
   dx = (i − outW/2)/(outW/2), dy = (j − outH/2)/(outH/2), d = √((dx² + dy²)/2), s = smoothstep((d − 0.39375)/0.65625)
   (0 up to d = 0.39375, reaching 1 over 0.65625 past it), v = `vignette`/100. A negative v
   multiplies each channel by 1 + v·s; a positive one adds (1 − channel) · v · s.
10. **Grain**, at (i, j) too: value noise over cells of max(1, c) px, c = 1.75 · output long edge / 3840 (`grainCell`).
    The noise is the hash of the four corners of the cell (i/cell, j/cell) falls in, bilinearly between them, minus ½. The
    hash is 32-bit integer arithmetic, every product wrapping (`Math.imul` in JavaScript, `uint` in GLSL, `Int` on the
    JVM): h = x · 374761393 + y · 668265263; h = (h ^ (h >>> 13)) · 1274126177; (h ^ (h >>> 16)) read unsigned, over
    4294967295. The delta is noise · 0.22 · (`grain`/100) · min(1, c) (`grainScale`) · (1 − 0.5 · |2L − 1|), added to all
    three channels. The pattern is the same for a given output size, not across sizes. **The floor and the scale** keep
    Grain as strong at the size it is seen: the Display image (c = 1.75 at 3840 px) shown in a box of a smaller long edge
    is averaged over about 1/c′² of its cells per pixel shown, c′ its cell in the pixels shown, which takes the noise's
    spread down by c′; an output drawn at that size (the Edit panel's canvas, at the box's device pixels) would have cells
    of c′ < 1 px, each pixel then a point of the noise at its full spread, about twice as strong at 1000 px. Floored at a
    pixel, its pixels are independent cells, and scaled by c′ they spread as the shown Display image does.
11. **Clamp** each channel to [0, 1] and encode to 8 bits, round(v · 255).

Step 10, like step 8, reads L as step 7 left it, before Clarity and Sharpen. Clarity, Sharpen and Grain are sized relative to the
output (`REFERENCE_LONG_EDGE`, 3840), so the Edit panel's drawing, the Display image and a full-size render look alike
at their own scales; Grain's strength follows its cell below a pixel (step 10). No color management is done: an original with an ICC profile other than sRGB is treated as sRGB by
both renderers (OpenCV ignores the profile; the client decodes with `colorSpaceConversion: "none"` and uploads with
`UNPACK_COLORSPACE_CONVERSION_WEBGL = NONE`), so a wide-gamut (Display P3, Adobe RGB) photo's Edit, the Display image
and the canvas alike, is less saturated than the browser shows its original. Converting a profile to sRGB in both
renderers is not done. What the stage does meanwhile: while the Edit panel's Edit is the identity, the canvas draws behind
the original's color-managed `<img>` (**Edit panel** in [views/AGENTS.md](../altitude/views/AGENTS.md)), so only a change
shows the renderers' colors.

## The two renderers

**The server's** (`EditRenderer.render(original, recipe, longEdge)`, an 8-bit BGRA `Mat` the caller releases; None for
`longEdge` is the edited size):

- **Decode.** The original as stored, its EXIF orientation ignored (`ImageUtil.decodeUnchanged`: 8 or 16 bits, alpha
  kept), and only an image of 8 or 16 bits and 1, 3 or 4 channels is rendered (an `ImageException` otherwise). A JPEG the
  output samples at half its size or less (the lesser of the output's width and height over the crop's in frame pixels)
  is decoded by the decoder at a half, a quarter or an eighth (`ImageUtil.decodeReduced`, `IMREAD_REDUCED_COLOR_*`), the
  largest reduction that keeps a frame pixel per output pixel.
- **Frame.** One EXIF orientation code turns the stored image straight into the Edit's frame: the one of the eight that
  composes the stored orientation with `rotate` and the flips (`frameOrientation`, found by trying each on a 3 × 2 probe of
  distinct values), applied by `ImageUtil.orient`.
- **Shrink.** When the output scale is below a half, the frame is first brought down to twice the scale with
  `INTER_AREA` (an 8-bit image in 16 bits, so the averages keep their fractions), so the warp never shrinks by more than
  half.
- **Warp and crop.** One `Imgproc.warpPerspective` of the composed homography (OpenCV's pixel-center offset, the output
  scale, the crop offset, the frame's center and half long side, Straighten, the keystones, back, and the source's
  reduction) into the output size, `INTER_LINEAR`, `BORDER_REPLICATE`, in the source's own depth.
- **Tone.** The result as 32-bit float BGRA over 255 or 65535, steps 1 to 7 per pixel through the tables, the coverage
  applied to alpha, and L kept as a float image.
- **Finish.** `Imgproc.GaussianBlur` of L for Clarity and Sharpen, then steps 8 to 11 per pixel into 8-bit BGRA.

Every intermediate image is released as the next is made, so a render holds at most two images of the original's size.
The Display image is the result as a WebP at quality 90 (`ImageUtil.encodeWebp`); the edited Preview is
`ImageUtil.makeImageThumbnail` of the same result, orientation 1, letterboxed into the 200 px box as every Preview is.

**The client's** (`renderer.js`, `createRenderer(canvas)`), four full-canvas passes:

1. **Develop:** each output pixel's frame point, warped back and turned back into the upright original
   (`geometry.frameToUpright`), sampled from the source texture: the upright original as the browser decodes it
   (`createImageBitmap`, `imageOrientation: "from-image"`, no color conversion, no premultiplied alpha), fitted to the long
   edge its caller asks for, at most `MAX_TEXTURE_SIZE`, mipmapped (`LINEAR_MIPMAP_LINEAR`), `CLAMP_TO_EDGE`. Then the
   tables (steps 1 to 6) and step 7, and the coverage. With the tone off it draws the geometry alone (`renderBefore`,
   Before/after's Before).
2. **Blur:** Clarity's and Sharpen's blurs of L, separable, a horizontal and a vertical pass computing both at once,
   with OpenCV's kernels and border; skipped when both are 0.
3. **Finish:** steps 8 to 11 on integer columns and rows from the top left, into an 8-bit texture. Drawing the whole
   frame (the Crop tool, which the server never renders), the steps' sizes and positions are the crop's in the output
   (`geometry.effectRegion`, the crop's edges rounded to output pixels): Clarity's and Sharpen's σ and Grain's cell and
   strength from its size, the vignette's center and extent and the grain's origin from its place, so what the crop shows
   in the tool is what Done draws, at the tool's scale; only the blurs still mirror at the output's edges, not the crop's.
4. **Present:** onto the canvas, its rows turned bottom up (the intermediate textures hold rows top down, so pixel (i, j)
   is the spec's everywhere but on the canvas), straight alpha, the drawing buffer kept.

`readHistogram` reads the last render as drawn at 256 px on its long edge (the crop alone, when the whole frame was drawn),
over the covered pixels: 64 bins of 4 levels
per channel, the luminance the Rec. 601 gray the import's measurement reads, as bytes over the tallest bin of the four.
A lost context is rebuilt on `webglcontextrestored` and the last render drawn again.

**What agrees, and how far.** The geometry, the tables, step 7, the kernels, the vignette's weight and the grain's hash
and noise are the same functions on both sides, checked against the shared vectors (**Parity**). The pictures agree within
a tolerance, not to the byte: the server resamples a pre-shrunk frame bilinearly, the client a mipmapped texture
trilinearly; a reduced JPEG decode, a 16-bit original (8 bits in a browser), float precision in the shaders and the WebP
encode each move a value a little. So do the client's intermediate targets: the developed colors and the blurs of L are
half floats (`RGBA16F`, `RG16F`, with `EXT_color_buffer_half_float`), against the server's 32-bit floats, and where the
context has no such extension, or cannot draw into a half-float target, the renderer falls back to 8-bit targets for
good, which clamps the step 7 colors to [0, 1] and quantizes them and the blurs of L to 1/255 before steps 8 to 10 read
them.

## The crop's rules

These are the client's (`geometry.js`, `adjust.js`); the server stores whatever crop the rounding leaves.

- **Inside** (`validRect`): a crop is at least `MIN_CROP` each way, within the frame (with a hair of floating-point
  slack) and, while the Edit keeps it inside, its four corners inside the outline (`insideQuad`: the same sign of the cross
  product along every edge, edges included).
- **A gesture** (`limitTransition`) goes from a valid crop toward the one it proposes as far as stays valid, found by
  halving, so a move or a resize stops at the outline.
- **A fitted crop** (`cropFitted`) re-fits, as Straighten, a perspective or the aspect change, to the largest crop of the
  lock's ratio centered on the frame and shrunk to stay valid (`fitMaxCentered`); **a crop the user set** shrinks around
  its center only as much as it must (`shrinkToValid`, around the frame's center when its own lies outside the image),
  and a new ratio reshapes it around its own center with its own area (`rectWithRatioAround`).
- **The lock's ratio** (`aspectRatio`): none for Free; the frame's own for Original, turned when the lock's orientation is
  not the frame's; a ratio landscape, or portrait when `portrait` says so or, while it is null, the frame is portrait.
- **Stored** (`storedCrop`): rounded as the server rounds it, moved in by 0.0001 of the frame on each side when the
  rounding takes a corner past the outline, and `null` when it is the whole frame, so an Edit whose crop covers the frame
  is the identity again.
- **A quarter turn and a flip** turn or mirror the edited image as shown: the crop, the perspective and Straighten are
  carried into the new frame, and with one flip on, a turn steps `rotate` the other way, since the mirror reverses
  the turn's sense as shown.
- **The Level tool** sets Straighten so the line drawn becomes level or plumb, whichever is nearer; a line under 12 px is a
  click. **A drag around the crop** turns Straighten by the change of the pointer's angle around the crop's center, from
  the Edit the drag started on.

## Auto-straighten

`EditRenderer.autoStraightenAngle(original, recipe)`: the Straighten that levels the image's lines, in tenths of a
degree, or nothing when no lines agree on one.

- **The frame:** the original turned and mirrored as the Edit's step 1 makes it (no Straighten, no perspective), a JPEG
  decoded reduced as far as keeps its long edge at 1280 px or more, then resized to a 1280 px long edge (`INTER_AREA` to
  shrink), in gray (16 bits brought to 8).
- **The lines:** a 3 × 3 Gaussian blur, `Canny` (50, 150), and `HoughLinesP` (ρ 1 px, θ 0.25°, threshold 60, the least
  length 8 % of the long edge, gaps of 6 px). Each segment's lean is its angle folded into [−45°, 45°) from the nearer of
  level and plumb; it counts when it is longer than the least length and leans within ±10°, weighted by its length, a plumb
  one by half (verticals lean with the camera's tilt as well as its roll).
- **The answer:** none unless the weights reach half the long edge. The leans go into a histogram of 0.2° bins from −10°
  to 10°, smoothed by [1, 2, 1]/4; the leans within ±1° of its peak must hold 35 % of the weight, else none; the answer is
  their weighted mean, negated so it turns them level, rounded to 0.1° (never −0).

## Parity

**The rule.** The Edit has two renderers, and the geometry and the math above are written twice: in `EditRenderer.scala`,
and in `geometry.js` and `tone.js` (DOM-free), which the shaders of `renderer.js` follow. A change to either is made on
both sides, in this guide and in the vectors, in the same change, and both test suites pass: `EditRendererTests` and
`EditRecipeTests` in the unit suite, and `scripts/test-js/edit.test.mjs` under `make test-js`.

**The vectors**, `altitude/test/resources/edit/vectors.json`, are the contract both sides are checked against. Every
recipe in them is a whole Edit, as stored. Its groups, each with its `tolerance` (the absolute difference allowed):

- `tone`: per recipe, the white balance gains, entries of the three tables and lookups between entries (10⁻⁵, the
  tables being float32);
- `curves`: curve samples (10⁻⁹);
- `saturation`: step 7 on given colors (10⁻⁹);
- `geometry`: per recipe and upright size, the frame and edited sizes, the outline, points turned into the frame, points
  warped, and points sampled back with whether each is covered (10⁻⁶);
- `effects`: the hash, the grain's noise, its cell and strength by output size, the vignette's weight and output sizes
  (10⁻⁹).

`_doc` in the file says what each group holds and the conventions above. `EditRecipeTests` also parses every recipe of
the vectors and checks the edited size they give.

**Regenerating them.** No generator is kept. The values are the Scala functions' output (`EditRenderer`'s public
`whiteBalanceGains`, `tables`, `lookup`, `Curve`, `saturate`, `frameSize`, `turn`, `warp`, `unwarp`, `outline`, `hash`,
`grainNoise`, `grainCell`, `grainScale`, `vignetteWeight` and `outputSize`, and the `editedSize` method of an `EditRecipe`), checked by hand where `EditRendererTests` checks the
spec's formulas directly (exposure doubling linear light, Straighten at 90° against a quarter turn, the tone steps, the
curve, the effects). To change them: change both implementations, write the affected entries again from the Scala
functions (a throwaway unit test printing the group as JSON in the file's shape will do, removed afterwards), keep `_doc`
true, and run both suites; an entry the client then misses is a parity bug, not a tolerance to widen.

## Tests

The renderer and the Edit are unit tests (`EditRendererTests`, `EditRecipeTests`), the stored Edit and the render queue
integration tests on both engines (`AssetEditServiceTests`, `EditRenderServiceTests`), the routes controller tests
(`EditControllerTests`, `ContentViewControllerTests`, `RouteTableTests`), and the client's modules Node tests
(`scripts/test-js/`: the parity, the session, the Edit panel's changes, the content URLs, the Histogram's paths). What each
covers and what is not covered is under **Image edits** in [test-coverage.md](test-coverage.md).
