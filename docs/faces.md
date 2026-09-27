# Faces: detection and recognition

How a face in an image or a Video becomes a Face of a Person. For the words used here (Face quality, Enrolled face,
Match-only face, Sampled frame, Frame time) see [CONTEXT.md](../CONTEXT.md); for the surrounding architecture see
[altitude/AGENTS.md](../altitude/AGENTS.md).

Sources: [FaceDetectionService](../altitude/src/altitude/core/service/FaceDetectionService.scala),
[FaceRecognitionService](../altitude/src/altitude/core/service/FaceRecognitionService.scala),
[PersonService](../altitude/src/altitude/core/service/PersonService.scala), the engine
[FaceDao](../altitude/src/altitude/core/dao/postgres/FaceDao.scala)s, the
[face table](../altitude/resources/migrations/postgres/all.sql) and the `face.*` and `video.faces.*` keys of
[reference.conf](../altitude/resources/reference.conf).

## Where it runs

Face recognition is a stage of the import pipeline (`service/ImportPipelineService.scala`), after the asset row exists
and the file is stored. On SQLite `IndexAndFaceRecFlow` persists, indexes and recognizes in one `withFaceVector`
transaction; on Postgres `FacialRecognitionFlow` is its own async stage. Both call
`FaceRecognitionService.processAsset`, which dispatches on the asset's media type: `processImage` or `processVideo`.
Whatever the stage throws for one asset drops that asset and the queue goes on; a `DuplicateException` (see **Storage**)
is reported as `SamePersonDetectedTwiceException` on Postgres.

`withFaceVector` (`transactions/TransactionManager.scala`) wraps every read or write that touches the `features`
column: on SQLite it loads the `sqlite-vector` extension and runs `vector_init('face', 'features',
'dimension=512,type=FLOAT32,distance=cosine')`; on Postgres the column is a pgvector `vector`.

## Detection

`FaceDetectionService.extractFaces` takes a decoded BGR `Mat` (the byte-array overload decodes and delegates) and
returns a `(Face, FaceImages)` per face it keeps.

1. **YuNet** (`resources/opencv/face_detection_yunet_2023mar.onnx`, OpenCV `FaceDetectorYN`, one instance per thread)
   finds faces. The image is downscaled to fit `face.detection.bounding_box_size` (960 px) first and the boxes and
   landmarks scaled back; the score is not. A detection needs a score of at least `face.yunet.confidence_threshold`
   (0.80, applied inside the detector with NMS `face.yunet.nms_threshold`) and a box, clamped to the image, of at least
   `face.detection.min_face_size` (50 px) each way. An image whose smaller side is under that size is skipped.
2. **Alignment**: the five YuNet landmarks (eyes, nose, mouth corners) are warped by a similarity transform
   (`Calib3d.estimateAffinePartial2D`) onto the ArcFace 112×112 reference template (`ARCFACE_REF_LANDMARKS_112`,
   `alignCropFaceFromDetection`).
3. **Embedding**: ArcFace w600k_r50 (`resources/opencv/w600k_r50.onnx`, OpenCV DNN, one `Net` per thread) turns the
   aligned crop into 512 floats (`getArcFaceEmbedding`). The result is an `Embedding`: the L2-normalized vector, which
   is compared by cosine distance, and the **norm** the raw output had before normalization.
4. **Quality** is that norm (`Face.quality`). For a net trained with the ArcFace loss the norm tracks how
   recognizable a face was: it drops with blur and occlusion, and it costs nothing extra. It does not respond to low
   resolution, and sharp faces differ by identity. On the test portraits sharp faces measure 19 to 22, the same faces
   blurred at 5% of their width 15 to 19.6, eyes covered 16 to 19.5. Blur only counts relative to the face: the
   112 px alignment hides any blur small against a large face.
5. **Tier**: `Face.isEnrolled` is `quality >= face.quality.enroll_threshold` (18.5). A detection under
   `face.quality.keep_threshold` (15.0) is dropped here, after the debug dump, with an INFO log.
6. **Crops** (`FaceImages`): the raw crop as PNG, an 80 px display thumbnail, the aligned 112×112 colour crop and a
   histogram-equalized grayscale copy of it. The Face's `checksum` is `MurmurHash.hash32` of the raw crop PNG.

With `face.debug.enabled=true` every processed image is written to `debug/` at the project root (cleared at startup):
`<base>-annotated.jpg` with each box, its landmarks and a `q=<quality> d=<score>` label, and
`<base>-<n>-q<quality>-aligned.png`, the exact net input, so a quality can be read against the crop that produced it.
A Video's frames are named by Frame time (`<ms>ms-<file>`). This is the tool for calibrating the two thresholds on
one's own files.

## Recognition

`FaceRecognitionService.recognizeFace(face): Option[Person]` finds the Person for an unsaved Face:

1. `FaceDao.searchClosestFaceMatches` returns the `face.recognition.match_count` (3) nearest stored Faces within
   `face.recognition.cosine_distance_threshold` (0.55), in distance order. The SQL is hand-written per engine
   (pgvector `<=>`, or `vector_full_scan` on SQLite; neither has a vector index, so it is an exact scan of the
   repository) and sees only **enrolled** Faces of people who are **not a bad match**. Hidden people stay matchable:
   hiding is a display preference.
2. The matches vote by Person; most votes win and a tie goes to the closest Face.
3. With no match, an **enrolled** Face starts a new Person (`PersonService.addPerson`, named "Unknown N" from the
   `person_label` sequence, with a zero-padded sort name). A **match-only** Face is nobody's: `None`, and the caller drops it.

Faces of one image are recognized and stored one at a time inside one transaction, so the second face of an image can
match the first one just saved (`twins.png`: the same person twice in one image is two Faces of one Person).

### Why two tiers

A poor face (blurred, obscured, profile) gets a drifted embedding. It fails to match its owner, and before the tiers it
started a new Unknown; stored, it then attracted later poor faces. Now a poor face can only join a Person it still
matches, and is otherwise dropped. Note that with this model quality and matchability are coupled: by the time blur
takes a face below the enroll threshold, its distance to a sharp photo of the same person is usually past 0.55 as well,
so the main effect of the tiers is that poor faces do not start Persons; the "match-only face joins its Person" path
fires mostly through the Video support rule below.

## Videos

`processVideo` runs detection on every Sampled frame (`VideoService.sampleTimes`: every
`video.faces.sample_interval_ms`, at most `video.faces.max_sampled_frames`), tags each Face with its Frame time, and
holds all detections of the video in memory. Then, with the pure functions of the `FaceRecognitionService` companion:

1. `cluster`: greedy leader clustering in **descending quality**. A detection joins the first cluster whose best
   member is within the cosine distance threshold, else starts one, so the sharpest detection is the reference.
2. `mergeClusters`: a cluster whose **centroid** (`meanNormalized` of its members) is within the threshold of an
   earlier cluster's centroid is absorbed by it. A turning head drifts past a single leader; the centroids of the
   halves are closer.
3. Each cluster is one Face: the crop, box, checksum, Frame time and quality of its **best-quality** detection, but the
   normalized centroid of all members as the vector, which is steadier than any one frame. The stored vector therefore
   does not derive from the saved crop.
4. **Support** is the number of distinct Frame times in the cluster. Under `video.faces.min_cluster_frames` (2) the
   Face is match-only whatever its quality, so a passer-by in one frame cannot start a Person. A clip shorter than the
   sample interval has one frame, so nobody enrolls from it.
5. One Face per Person per Video: when two clusters resolve to the same Person, the later, lower-quality one is
   dropped with an INFO log, so a pose change that splits a person in two does not fail the import.

## Storage

`face` row: box (`x1`, `y1`, `width`, `height`), `asset_id`, `person_id` (NOT NULL: a Face always belongs to a
Person), `detection_score`, `features` (the vector, never read back into the model; `FaceRow` omits it), `checksum`,
`quality`, `is_enrolled`, `frame_time_ms` (NULL for an image). `is_enrolled` is decided at import and stored, so
changing a threshold later does not re-tier existing Faces, and a Video's support rule is not derivable from quality.

`face_01` makes `(repository_id, asset_id, checksum)` unique: the same crop twice in one asset is a
`DuplicateException`, while two assets may share a byte-identical frame (a trimmed copy of a video, a re-exported photo).
`face_02` is `(person_id, detection_score)` for a person's faces.

The crops live in the file store (`FileSystemStoreService.addFace`) as
`faces/<id[0:2]>/<id>-{display,detected,aligned,aligned-gs}.png`.

## People

A `person` row carries `name`, `is_named`, `num_of_faces`, `cover_face_id`, `is_hidden`, `is_bad_match`, `is_deleted`.
`PersonService.addFace` inserts the Face, increments `num_of_faces` and sets the cover face when it is the first.
Recycling an asset decrements the counts of its people and restoring increments them; the faces themselves go with the
asset on purge. Moving an asset between folders or triage does not touch the counts.

The People tab (`PeopleActionController`) lists people by `Const.PeopleTypeFilter`: complete (at least
`Const.FaceRecognition.MIN_FACES_THRESHOLD`, 3, faces, or named), incomplete, hidden. Its actions:

- **Name**: `updateName` sets `is_named`; a named person is listed whatever their count.
- **Cover face**: `getPersonFaces` (by detection score) offers the candidates, `setFaceAsCover` picks one.
- **Hide / show**: display only; the person's faces still match.
- **Bad match** (`markAsBadMatch`): the person is taken out of listings and their faces out of the match candidates.
- **Merge** (`merge(dest, source)`): the source's faces move to the destination, the source is soft-deleted, the
  destination takes the source's name when only the source was named, and its count is recounted from the search.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `face.yunet.confidence_threshold` | 0.80 | Detector score floor |
| `face.yunet.nms_threshold` | 0.2 | Detector non-maximum suppression |
| `face.detection.bounding_box_size` | 960 | Detection runs on the image downscaled to fit this |
| `face.detection.min_face_size` | 50 | Smallest face box, in original pixels |
| `face.recognition.cosine_distance_threshold` | 0.55 | Two embeddings closer than this are the same person, in the index and within a Video |
| `face.recognition.match_count` | 3 | Nearest candidates that vote |
| `face.quality.enroll_threshold` | 18.5 | Quality to start a Person and be a candidate |
| `face.quality.keep_threshold` | 15.0 | Quality to be stored at all |
| `face.debug.enabled` | false | Write the debug artifacts above |
| `video.faces.sample_interval_ms` | 1000 | Spacing of Sampled frames |
| `video.faces.max_sampled_frames` | 120 | Cap on Sampled frames per Video |
| `video.faces.min_cluster_frames` | 2 | Fewer frames makes a Video's Face match-only |

## Tests

[FaceDetectionTests](../altitude/test/src/altitude/core/integration/FaceDetectionTests.scala) covers detection on the
`people/` fixtures and the tiers on blurred copies (`TestVideos.still`, `LIGHT_BLUR`, `HEAVY_BLUR`);
[FaceRecognitionServiceTests](../altitude/test/src/altitude/core/integration/FaceRecognitionServiceTests.scala) covers
candidate eligibility, the tie-break and the no-candidate rule on known vectors (`TestContext.addTestFace`), and the
Video rules on clips synthesized by [TestVideos](../altitude/test/src/altitude/test/TestVideos.scala);
[FaceClusteringTests](../altitude/test/src/altitude/core/unit/FaceClusteringTests.scala) covers the pure clustering.
Gaps are listed in [test-coverage.md](test-coverage.md).
