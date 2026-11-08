# Comparable sharpness

## Goals

- Two images' `sharpness` values always describe the same region, so a difference between them is a difference in the
  images, whatever faces were detected in each.
- Each Face of a still image has its own sharpness, so one soft face in a group shot is visible and two frames can be
  compared on the same Person.
- The Image quality score keeps its values.

Out of scope: the Curation view and its bundles, the rule that calls a frame blurry and its thresholds, eye-state
detection, and any change to the score's formula or calibration.

## Language

`CONTEXT.md` gains **Face sharpness**. **Image quality** and **Face quality** stand: Face quality is how recognizable a
face is, Face sharpness how much fine detail its box holds.

## Properties the design rests on

- Whole-frame sharpness describes one region for every image, so a difference between two frames of one burst is a
  difference in the frames. Across different scenes it mostly reflects content.
- The union of the face boxes hides a soft face, since it averages the faces of a group shot.
- Face sharpness falls as a face fills more of the frame, so two values compare only for one Person at a similar size,
  and an absolute threshold on one means nothing.

## Design

### What each value means

| Value | Where | Region | Absent when |
|---|---|---|---|
| `ImageQuality.sharpness` | `asset.sharpness` | Always the whole fitted frame | The image is unmeasured |
| `Face.sharpness` | `face.sharpness`, nullable | That Face's box, scaled and clamped to the fitted frame | A Face in a Video; a box under `MIN_FACE_AREA_PX`; the measurement failed |
| `ImageQuality.score` | `asset.image_quality` | As before: `s` is the mean over the union of the face boxes when they cover at least `MIN_FACE_AREA_PX`, else the whole frame | The image is unmeasured |

The union's sharpness stays the score's input and is not stored: nothing else reads it, and a stored value with a region
that varies per image is what a reader must never compare. The score therefore cannot be recomputed from the stored
columns, so a change to its formula means measuring again. Two scores remain incomparable when the faces of one image
clear the floor and those of the other do not; no reader ranks frames by score.

`MIN_FACE_AREA_PX` (2500, a 50 × 50 box at the measurement scale) now gates each Face on its own as well as the union.
`None` means too little area to say, and a reader falls back on the whole frame.

### Measurement

`ImageMeasurement.measure(image, faces)` returns `Measurement(quality, hash, faces)`, where `faces` are the input faces
in their order, each with `sharpness` set. It computes the `CV_64F` Laplacian once over the gray fitted image, then:

- the whole frame's variance, stored as `ImageQuality.sharpness`;
- each Face's variance over a `submat` of that Laplacian at its scaled, clamped box, or `None` under the floor;
- the union's variance through the existing mask, or the whole frame's under the floor, as the score's `s`.

`score(...)` keeps its signature and formula. Luminance, clipping, crushing and the hash do not change.

`AssetService.measureImage(dataAsset)` returns an `AssetWithData`: the asset with its metrics and hash, and
`detectedFaces` holding the measured faces, each still paired with its `FaceImages`. A Video, an animated image and the
`x-none` type come back unchanged. A `NonFatal` failure is logged at WARN and comes back unchanged too, so the Faces are
stored with no sharpness and the asset imports unmeasured.

### Pipeline

`MeasureImageFlow` runs between `DetectFacesFlow` and `RecognizeFacesFlow`, so a Face's sharpness is in the row's one
insert and no stage updates a Face afterwards.

| Stage | Operator | Touches |
|---|---|---|
| `DetectFacesFlow` | `mapAsync(importParallelism)` | file |
| `MeasureImageFlow` | `mapAsync(importParallelism)` | file |
| `RecognizeFacesFlow` | `mapAsync(1)` | database write, face files |
| `FileStoreFlow` | `mapAsync(1)` | rename |

The number of decodes and of images held at once is unchanged, `mapAsync` keeps upload order for recognition, and
measurement touches no database. The union still counts every detection, including a match-only Face that recognition
then drops, so the score's values do not move. A detection dropped by recognition takes its sharpness with it.

### Storage

`face.sharpness` is nullable in both `all.sql` files (`DOUBLE PRECISION`, `REAL`), with no new migration and no
`schemaVersion` bump. `Face` gains `sharpness: Option[Double] = None`, `FieldConst.Face.SHARPNESS` names the column, and
`FaceRow`, both `FaceDao` mappings (`toModel`, `makeModel`) and the two hand-written `add` statements carry it. Nothing is
backfilled: a database is recreated, and what is imported into it is measured by these rules.

## Tasks

Each task is one reviewable change, in dependency order, red before green.

1. **Face sharpness column.** A test in `AssetServiceTests` or `FaceRecognitionServiceTests` stores a Face with a
   sharpness and one without, on both engines, and reads back `Some` and `None` through `PersonService.getAssetFaces`. Then both
   `all.sql` files, `Face`, `FieldConst`, `FaceRow`, the two mappings and the two inserts. `RowColumnTests` covers the
   column on both schemas. Rationale: everything later writes through this, and nothing in the pipeline changes yet.

2. **Measurement.** In `ImageMeasurementTests`, the face-box test is replaced by:
   - the same image with and without a box, and with a box under the floor, has one `sharpness`;
   - a box over the sharp rectangle scores above the whole frame and a box over the blurred corner scores below it, so
     the union still decides the score;
   - a sharp box and a blurred box in one image each get their own value, in input order, and neither equals the union's;
   - a box under the floor has `None`, and a box past the image's edge is clamped.

   Then `Measurement` and the new `measure`, with `ImageQuality`'s documentation of both fields updated. The orderings
   of sharp above blurred, the channel-layout test and the 2× copy test stay. Rationale: the rule is pure and is proved
   here before the pipeline depends on it.

3. **Stage order.** An integration test in `AssetImportServiceTests`, on both engines, imports a `people/` fixture
   through the pipeline and asserts:
   - each stored Face has a sharpness;
   - the asset's `sharpness` equals `measure(decoded, Nil).quality.sharpness`;
   - its score equals `measure(decoded, detected).quality.score`;
   - a Video's Faces have none.

   The undecodable-file test asserts on the returned `AssetWithData`, with its faces unchanged. Then `measureImage`'s new
   return type, `MeasureImageFlow`, and the move in `ImportPipelineService.combinedFlow`. Rationale: this is the
   red-green proof of the behaviour, end to end.

4. **Documentation.**
   - `altitude/AGENTS.md`: the pipeline table and its paragraph on where the detections ride, and **Sequences and Image
     quality**: the measurement order, the meaning of `sharpness`, and that the decode turns the image upright (the
     measurement's `matFromBytes` passes `IMREAD_ANYCOLOR` without `IMREAD_IGNORE_ORIENTATION`, so EXIF orientation is
     applied, and a Face's box is in upright pixels while `asset.width` and `height` come from the file's header).
   - `ImageMeasurement`'s header comment says the same about orientation; `AssetWithData`'s, `DetectedFaces`' and
     `MeasureImageFlow`'s comments follow the new order.
   - `docs/faces.md`: the paragraph on `MeasureImageFlow`, and the Face fields.
   - `docs/test-coverage.md`: the face-box line and the new tests.
   - `docs/curation-batch-detection.md`: its status paragraph.
   - `CONTEXT.md`: **Face sharpness**: how much fine detail a Face's box holds, comparable only between Faces of one
     Person at a similar size. _Avoid_: Face quality.

   Rationale: the Do section's no-drift rule.

## Verification

- `make lint`, `make test-unit`, `make test-sqlite` and `make test-psql` pass.
