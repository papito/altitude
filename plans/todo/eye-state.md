# Eye state

## Goals

- Each Face of a still image records how closed its eyes measure, at import.
- Curation gains an **Eyes closed** Bundle, the most serious problem a frame can have.
- The rule compares a Person with themselves within the set, so it takes no library, camera or shoot as typical.
- The thresholds are Live settings over stored measurements, so a change applies from the next read, with no new import.
- The measurement runs inside the import's memory and time budget, and a failure never drops an asset.

Out of scope: smile, gaze and head pose, Videos, public face datasets, photo fixtures that assert what a measurement means,
and deciding that closed eyes were intended, which stays the user's call: a Bundle recycles nothing by itself.

## Language

`CONTEXT.md` gains **Eyes closed**: a Face whose two eyes both measure as closed, and the Bundle of the frames holding one.
_Avoid_: Blink, in user-facing text.

## Design

### The measurement

Two candidates share Face Mesh V2, and task 1 keeps one of them. The other leaves this plan, with its column, its settings
and its network.

| | Blink score | Eye aspect ratio |
|---|---|---|
| Networks | Face Mesh V2, then Blendshape | Face Mesh V2 |
| An eye's value | `eyeBlinkLeft` or `eyeBlinkRight`, 0 to 1, higher is more closed | `(‖p2 - p6‖ + ‖p3 - p5‖) / (2 ‖p1 - p4‖)`, lower is more closed |
| A Face's **reading** | The lower of the two, so both eyes must measure as closed | The higher of the two, for the same reason |
| Columns on `face`, nullable | `eye_blink_left`, `eye_blink_right` | `eye_aspect_left`, `eye_aspect_right` |

A wink or an eye hidden in a profile does not count as closed. `*Left` and `*Right` are the subject's, and nothing in this
path mirrors the image. Both values of a Face are stored, because calibration reads them.

The score moves with its frame: shifting the crop by at most 10% in scale and 3% in place moves a Face's wider eye by 0.17 at
the median. Landmarks hold steadier, and tracking passes (see **The crop**) steady both. The aspect ratio is plain geometry,
which is why it is a candidate; the score is the model's own answer to the question.

**Aspect ratio landmarks**, `p1` to `p6`: the subject's right eye `33, 160, 158, 133, 153, 144`, and their left eye `362, 385,
387, 263, 373, 380`, in pixels of the whole image.

**Blendshape input**: the 146 landmarks below, X and Y in pixels of the whole image. `eyeBlinkLeft` is output 9 and
`eyeBlinkRight` is output 10. The indices are from `tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc:52` at MediaPipe
`v1.2.0`:

```
0, 1, 4, 5, 6, 7, 8, 10, 13, 14, 17, 21, 33, 37, 39, 40, 46, 52, 53, 54, 55, 58, 61, 63, 65, 66, 67, 70, 78, 80,
81, 82, 84, 87, 88, 91, 93, 95, 103, 105, 107, 109, 127, 132, 133, 136, 144, 145, 146, 148, 149, 150, 152, 153, 154, 155, 157, 158, 159, 160,
161, 162, 163, 168, 172, 173, 176, 178, 181, 185, 191, 195, 197, 234, 246, 249, 251, 263, 267, 269, 270, 276, 282, 283, 284, 285, 288, 291, 293, 295,
296, 297, 300, 308, 310, 311, 312, 314, 317, 318, 321, 323, 324, 332, 334, 336, 338, 356, 361, 362, 365, 373, 374, 375, 377, 378, 379, 380, 381, 382,
384, 385, 386, 387, 388, 389, 390, 397, 398, 400, 402, 405, 409, 415, 454, 466, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477
```

### The files

Google's models, Apache-2.0 on their model cards, converted by us so the chain of custody is ours.

| File | Detail |
|---|---|
| Source | `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`, 3,758,596 bytes, SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`, holding `face_landmarks_detector.tflite` and `face_blendshapes.tflite` |
| Conversion | `scripts/convert-face-mesh.py`: tf2onnx 1.17.0, opset 18. tf2onnx's optimizer stays off for the Blendshape graph, because ONNX Runtime refuses the graph it produces |
| Output | `resources/mediapipe/face_landmarks.onnx` (about 4.9 MB) and `face_blendshapes.onnx` (about 1.1 MB), committed. A conversion is not byte-reproducible, so the committed files are the pinned ones and the script records their hashes |

| Graph | Input | Outputs |
|---|---|---|
| Face Mesh V2 | `input_12`, `[N, 256, 256, 3]`, RGB, 0 to 1 | `Identity`, `[N, 1, 1, 1434]`: 478 × (x, y, z) in crop pixels. `Identity_1`: the presence logit |
| Blendshape | `serving_default_input_points:0`, `[1, 146, 2]` | `StatefulPartitionedCall:0`, `[52]`, 0 to 1 |

### The runtime

ONNX Runtime's CPU artifact, `com.microsoft.onnxruntime:onnxruntime:1.31.1`, whose jar bundles the natives and adds about 51 MB
to the assembly. `service/EyeStateService` holds one shared, thread-safe session per network, opened lazily, with one intra-op
and one inter-op thread each.

`ImportParallelism` budgets the sessions as a fixed cost beside `POOL_COST`, at what task 1 measures, with the figures in its
comment. For the score path that is 52 to 59 MB on one thread and 141 MB settled at 12 threads.

### The crop

Each pass warps a rotated rectangle of the decoded image to 256×256.

- **The first pass** is centred on YuNet's box, with the side `max(w, h) * 1.5`, rotated by the eyes.
- **A tracking pass** takes the bounding box of the previous pass's 478 projected landmarks, rotated from landmark 33 to 263,
  with the longer side and 1.5 each way. Task 1 sets how many there are.
- **Rotation**: `normalize(0 - atan2(-(y1 - y0), x1 - x0))`, from the eye on the image's left to the other. YuNet's columns 4, 5
  then 6, 7 are that order.
- **Warp**: `RotatedRect(centre, size, degrees)`, `boxPoints`, destination `{0,H, 0,0, W,0, W,H}`, `getPerspectiveTransform`,
  `warpPerspective`, `INTER_LINEAR`, `BORDER_REPLICATE`, then RGB divided by 255.
- **Projection** back to the image: `dx = (x / 256 - 0.5) * w`, `dy = (y / 256 - 0.5) * h`, `px = cx + dx cos - dy sin`,
  `py = cy + dx sin + dy cos`.
- **Presence** reads the last pass: `sigmoid(Identity_1) >= 0.5`, MediaPipe's default.

YuNet's landmarks exist only inside `extractFaces`, so `FaceImages` gains the two eye points and carries them to
`MeasureImageFlow`. YuNet stays the only detector: every Face it finds gets this path, and no Face is added or removed here.

### Measurement and storage

`AssetService.measureImage` asks `EyeStateService` for each measured Face's two values, before it releases the decoded image.
A Face under the presence threshold, or any `NonFatal` failure, leaves them NULL with a WARN. The eye step has its own guard, so
its failure keeps the asset's Image quality, its hash and its Faces' sharpness. The columns are in both `all.sql` files, written
in the Face's one insert, with `Face`, `FieldConst`, `FaceRow` and both mappings following.

### The Bundle

`BundleKind` gains `EyesClosed` first, ahead of `PoorFaces`, so it is a frame's main problem and never a chip. A frame the
pipeline did not measure stays a Contender, as `CurationFrame.isMeasured` makes it. A Face is closed when it passes both tests:

| Test | Blink score | Eye aspect ratio |
|---|---|---|
| **The limit** | The reading is at least `curation.eyes_closed_score` | The reading is at most `curation.eyes_closed_aspect` |
| **The comparison** | The reading is at least `curation.eyes_closed_gap` above the same Person's lowest reading in the set's other frames | The reading is under `curation.eyes_closed_ratio` of the same Person's highest reading in the set's other frames |

The comparison is what carries the rule across libraries: narrow eyes, or sunglasses that read high, read that way in each frame,
and a blink stands out against the same Person's other frames.

- **A Person with no other reading** has no comparison, so the limit alone decides. This is the weaker case, and it is most of a
  selection of different scenes.
- **Faces read.** Every Face the rule reads counts, whatever its size: `face.detection.min_face_size` is the floor. A Person
  twice in one frame is compared only with the other frames, as Poor faces does.
- **A NULL reading** is no evidence: the Face is not closed, it sets no baseline, and the frame gets no note, so that frame can be
  the sole Contender.
- **A Person closed throughout.** A Person in at least two frames of the set, every reading past the limit and none passing the
  comparison, cannot be fixed by picking. That is a notice on the set, "Eyes read as closed in every frame they appear in", with
  the Person's face crop, and those Faces put no frame in the Bundle. `CurationNotice` holds exposure readings only, so
  `CurationSet` gains `closedThroughout`, one Face per such Person. Other Persons' Faces are still tested. A set left with no
  Bundle reads Too close to call. Different Persons closed in different frames stay in the Bundle, even when that is every frame
  of the set.
- **Settings off.** A gap of 0 or a ratio of 1 turns the comparison off and leaves the limit alone. Past the readings' range, the
  limit turns the Bundle off.

The thresholds are Live settings in `SettingGroup.Triage`. Task 1 replaces these starting points with what it measures, and
they are calibrated further on real shoots through the Settings screen:

| Key | Starting point | Type | Path |
|---|---|---|---|
| `curation.eyes_closed_score` | `0.5` | `Decimal(0, 1)` | Blink score |
| `curation.eyes_closed_gap` | `0.3` | `Decimal(0, 1)` | Blink score |
| `curation.eyes_closed_aspect` | `0.2` | `Decimal(0)` | Eye aspect ratio |
| `curation.eyes_closed_ratio` | `0.6` | `Decimal(0, 1)` | Eye aspect ratio |

### The view

`CurationReason.EyesClosed` holds the Person, the Face, the reading, the limit and the Person's other reading when there is one,
so a user can see which setting to move.

- **A frame's reason** reads as the others do, "Eyes closed: 0.72 against 0.08, that person's lowest", or "Eyes closed: 0.72, the
  limit 0.50" with no comparison, beside that Face's crop.
- **The Bundle's header** keeps its reason, the first frame's first and "and 2 more", and adds each distinct Person's crop once, so
  the header says who.
- **The face strip** marks the crop of every closed Face, those of a Person closed throughout included.
- **`face.debug.enabled`** adds the two values to each crop's label, for calibrating on one's own files.

### Too few frames in the queue

Recycling a Bundle ends in `openSet`, whose walk passes over a Sequence answering with no content, so in the queue a set recycled
down to one frame vanishes with its Undo out of sight. Recycling any Bundle that leaves fewer than two frames now shows
`Phase.tooFew`, with Undo and Exit, in the queue as in a selection, and in the queue Skip goes on to the next set. A Sequence
reached by walking the queue with too few frames is still passed over without comment.

## Tasks

Each task is one reviewable change, in dependency order. Server tasks are red before green.

1. **Choose the measurement (a gate).** A test-only harness, with no database, runs each candidate at 0, 1 and 2 tracking passes
   over a set of real bursts kept outside the repository, at a path it is given. The set has at least three people and at least
   ten closed Faces, with a manifest giving each Face's state: open, closed, sunglasses, looking down or turned away. It reports
   each reading per state, the spread of one Person's open readings within a burst, the time per Face and the resident memory, and
   the landmarks' distance from the Python `mediapipe` reference's own tracking mode, for information.

   It settles the kept candidate, its tracking passes and its defaults. **A candidate passes when** the rule at its defaults
   finds at least 8 of 10 closed Faces, flags at most 1 in 20 open Faces, puts no sunglasses Face in the Bundle, and costs at most
   100 ms a Face at 12 threads and 384 MB. The defaults are chosen on half of the bursts and judged on the other half. Among
   candidates that pass, the aspect ratio wins a tie, and then the fewest passes. **If none passes, the choice goes back to the
   user**, and stopping here is a fair outcome. Rationale: without this nothing shows either measurement finds closed eyes.

2. **Dependency, files and service.** `build.mill` gains onnxruntime, the conversion script and the committed files, and
   `EyeStateService` with the kept path. Unit tests: the crop's rotation and projection and, on that path, the aspect ratio on
   synthetic landmarks; a Face of the `people/` images gets two values in range, which makes no claim about what they mean.
   Rationale: the path is built and tested before the pipeline uses it.

3. **Pipeline and storage.** The columns on both engines, the eye points on `FaceImages`, `measureImage` filling the values, and
   the sessions' cost in `ImportParallelism` with `ImportParallelismTests`. `AssetImportServiceTests` on both engines: a still
   image's Faces have values, a Video's have none, and a service failure imports the asset with NULL values and its Image quality
   intact. Rationale: red-green end to end.

4. **The Bundle.** The definitions, their accessors, seeds in both `all.sql` files and `TestSettings.seeds`,
   `SettingsServiceTests`, and `CurationRule`. `CurationRuleTests`, on synthetic values, for each clause: both eyes and a wink; the
   limit and the comparison each in and out; narrow eyes reading high with a real blink; no other reading; a NULL reading, as no
   baseline and with its frame as the sole Contender; the priority over Poor faces; a Person closed throughout as a notice, with
   another Person's blink still bundled; different Persons closed across every frame still bundled; the comparison off. Rationale:
   this is the decision, tested alone.

5. **The view.** `htmx/curation_set`, `curation_set.css` and the style guide: the reason with its crop, the crops in the Bundle's
   header, the notice, and the mark in the face strip, with controller tests for the fragment. The debug label. Rationale: the
   reason is what the user acts on.

6. **Too few frames in the queue.** `alpine/components/curation.js` and `htmx/curation`. Rationale: it applies to every Bundle, and
   does not need the model.

7. **Documentation.** `altitude/AGENTS.md` (the pipeline paragraph, the build's dependency list and the memory figures),
   `docs/faces.md`, `docs/READINESS.md` (memory guidance), `views/AGENTS.md` (**Curation**), `CONTEXT.md`, `docs/test-coverage.md`,
   and `docs/curation-batch-detection.md`. Rationale: the Do section's no-drift rule.

## Verification

- `make lint`, `make test-unit`, `make test-controllers`, `make test-sqlite` and `make test-psql` pass. A new Setting needs the
  Postgres test database's public schema dropped once.
- An import of the `people/` images reports the time and the resident memory against the same import without this path.
- On a scratch instance holding the bursts, in the browser:
  - a closed Face's frame sits in Eyes closed with its reading and crop, and the strip marks it;
  - an Eyes closed Bundle shows its Persons' crops in the header;
  - a Person closed in every frame they appear in shows as a notice with their crop;
  - changing a threshold on the Settings screen changes the Bundle at the next read;
  - in the queue, recycling a Bundle that leaves fewer than two frames shows Undo and Exit, Z brings the frames back, and Skip
    opens the next set.
