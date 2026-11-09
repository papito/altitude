# Faces: detection and recognition

How a face in an image or a Video becomes a Face of a Person. For the words used here (Face quality, Enrolled face,
Match-only face, Sampled frame, Frame time) see [CONTEXT.md](../CONTEXT.md); for the surrounding architecture see
[altitude/AGENTS.md](../altitude/AGENTS.md).

Sources: [FaceDetectionService](../altitude/src/altitude/core/service/FaceDetectionService.scala),
[FaceRecognitionService](../altitude/src/altitude/core/service/FaceRecognitionService.scala),
[PersonService](../altitude/src/altitude/core/service/PersonService.scala), the engine
[FaceDao](../altitude/src/altitude/core/dao/postgres/FaceDao.scala)s, the
[face table](../altitude/resources/migrations/postgres/all.sql) and the `face.*` and `video.faces.*` Settings of the
registry in [Settings.scala](../altitude/src/altitude/core/models/Settings.scala) (see **Settings** below).

## Where it runs

Faces are two stages of the import pipeline (`service/ImportPipelineService.scala`), after the asset row exists and is
indexed, on both engines, their work on the import dispatcher's threads (the `import.parallelism` Setting, a Restart
setting: 0, the default, means half the cores or what half the machine's memory affords beside the shared net and
the two Scene models, whichever is fewer, and never fewer than 2, and `import.parallelism.max` in the server config caps it whatever the
Setting says; see `util/ImportParallelism`):

- `DetectFacesFlow` calls `FaceRecognitionService.detect`, which finds the faces without touching the database: an
  image's every detection (`extractFaces`), or a Video's clusters (see **Videos**). An animated image (an image with a
  duration, an animated GIF) is not searched for faces. The stage works on up to that many assets of a
  repository at once. The detections, a `DetectedFaces` of each Face with its crops, ride on the pipeline element
  (`AssetWithData.detectedFaces`) through `MeasureImageFlow` to recognition.
- `RecognizeFacesFlow` calls `recognizeAndStore`, which matches and stores them in a short `withFaceVector`
  transaction, so the detection never holds SQLite's one write connection. It works on one asset of a repository at a
  time, in upload order, so a face can join the Person an earlier photo of the same upload started.

Between the two, `MeasureImageFlow` reads `AssetWithData.detectedFaces`, gives each detection of a still image its
Face sharpness (the variance of the Laplacian inside its box at the measurement scale, none for a box too small to say),
which recognition stores in the Face's one insert, and scores the image on the union of the boxes when they cover enough
of the frame, so a sharp portrait on a flat background is not dragged down. A Face in a Video has no sharpness. Face
sharpness is not Face quality: it compares only between Faces of one Person at a similar size. That stage and the score
are described under **Sequences and Image quality** in [altitude/AGENTS.md](../altitude/AGENTS.md); `StripBinaryDataFlow`
leaves the detections behind.

Whatever a stage throws for one asset drops that asset and the queue goes on; a `DuplicateException` (see **Storage**)
rolls back that asset's faces and is reported as `SamePersonDetectedTwiceException`. Each Face's files are written as it
is stored, inside that transaction, and a rollback deletes the files already written (`withFaceFiles`), so a Face in the
database always has its files and a rolled-back one leaves none.

`withFaceVector` (`transactions/TransactionManager.scala`) wraps every read or write that touches the `features`
column. On SQLite every connection, as it is opened, loads the `sqlite-vector` extension and runs `vector_init('face',
'features', 'dimension=512,type=FLOAT32,distance=cosine')` when the `face` table exists, and the migration of a fresh
database has the connections opened before its schema existed replaced (`schemaCreated`), so the wrapper sets nothing
up per transaction there; on Postgres the column is a pgvector `vector` and the wrapper sets the HNSW search parameters
for the transaction.

## Detection

`FaceDetectionService.extractFaces` takes a decoded BGR `Mat` (the byte-array overload decodes and delegates) and
returns a `(Face, FaceImages)` per face it keeps.

1. **YuNet** (`resources/opencv/face_detection_yunet_2023mar.onnx`, OpenCV `FaceDetectorYN`, one instance per thread)
   finds faces. The image is downscaled to fit the `face.detection.bounding_box_size` Setting (1280 px) first and the boxes and
   landmarks scaled back; the score is not, so that size decides how small a face in a 12 to 48 MP photo can be found.
   A detection needs a score of at least `face.yunet.confidence_threshold` (0.85, set on the thread's detector before each
   detection together with NMS `face.yunet.nms_threshold`, 0.3, OpenCV's default; tighter and one of two cheek-to-cheek
   faces is suppressed) and a box, clamped to the image, of at least `face.detection.min_face_size` (50 px) each way. An image whose smaller side
   is under that size is skipped.
2. **Alignment**: the five YuNet landmarks (eyes, nose, mouth corners) are warped by a similarity transform
   (`Calib3d.estimateAffinePartial2D`) onto the ArcFace 112×112 reference template (`ARCFACE_REF_LANDMARKS_112`,
   `alignCropFaceFromDetection`).
3. **Embedding**: ArcFace w600k_r50 (`resources/opencv/w600k_r50.onnx`, OpenCV DNN) turns the aligned crop into 512
   floats (`getArcFaceEmbedding`). The threads share one `Net` (`arcFaceNets`, a `util/NetPool`, Winograd off: about
   370 MB, against about 780 MB with it on). A `Net` is not thread-safe, so an embedding borrows it for its one
   forward pass, waiting while it is out, and copies the output inside the borrow, because the output shares its
   memory with the net. The result is an `Embedding`: the L2-normalized vector, which
   is compared by cosine distance, and the **norm** the raw output had before normalization.
4. **Quality** is that norm (`Face.quality`). For a net trained with the ArcFace loss the norm tracks how
   recognizable a face was: it drops with blur and occlusion, and it costs nothing extra. It does not respond to low
   resolution, and sharp faces differ by identity. On the test portraits sharp faces measure 18.8 to 22.4, the same faces
   blurred at 5% of their width 15 to 19.6, eyes covered 16 to 19.5. Blur only counts relative to the face: the
   112 px alignment hides any blur small against a large face.
5. **Tier**: `Face.isEnrolled` is `quality >= face.quality.enroll_threshold` (18.0). A detection under
   `face.quality.keep_threshold` (15.0) is dropped here, after the debug dump, with an INFO log. Both thresholds are read
   from the snapshot of this `extractFaces` call; changing them never re-tiers Faces already stored.
6. **Crops** (`FaceImages`): the raw crop as PNG, an 80 px display thumbnail, the aligned 112×112 colour crop and a
   histogram-equalized grayscale copy of it. The Face's `checksum` is `Sha256.hex` of the raw crop PNG, its SHA-256 as
   64 lowercase hex characters; the file store writes those same bytes as the Face's `-detected.png`.

With `face.debug.enabled=true` every processed image is written to `debug/` at the project root (cleared at startup):
`<base>-annotated.jpg` with each box, its landmarks and a `q=<quality> d=<score>` label, and
`<base>-<n>-q<quality>-aligned.png`, the exact net input, so a quality can be read against the crop that produced it.
A Video's frames are named by Frame time (`<ms>ms-<file>`). This is the tool for calibrating the two thresholds on
one's own files.

## Recognition

`FaceRecognitionService.recognizeFace(face): Option[Person]` finds the Person for an unsaved Face:

1. `FaceDao.searchClosestFaceMatches(features, matchCount, distanceThreshold)` returns the `face.recognition.match_count`
   (5) nearest stored Faces within `face.recognition.cosine_distance_threshold` (0.55), in distance order; the service
   reads both Settings from one snapshot per face and passes them in, so the DAO holds no Setting. The SQL is hand-written per engine and sees
   only **enrolled** Faces of people who are **not a bad match**, in the context repository. Hidden people stay
   matchable: hiding is a display preference.
   - **PostgreSQL** (`postgres.FaceDao.CLOSEST_MATCHES_SQL`): the candidates come from `face_03`, a partial HNSW index
     over the enrolled Faces' vectors at half precision (`features::halfvec(512)`, `WHERE is_enrolled`). The statement
     asks it for four times `match_count` Faces, ordered by the same cast, then takes their exact `<=>` distances at
     full precision, applies the threshold and keeps the nearest. `withFaceVector` sets `hnsw.ef_search`
     (the PostgreSQL-only `face.recognition.hnsw_ef_search` Setting, 100, read when the outermost `withFaceVector` runs) and `hnsw.iterative_scan = relaxed_order` for the transaction, so the
     index scan goes on until its limit is met by Faces that pass the repository and bad-match filters. The search is
     approximate; over a handful of Faces it is exhaustive.
   - **SQLite**: `vector_full_scan` is an exact scan of the `face` table, every repository's rows, filtered
     afterwards. sqlite-vector's quantized scan reads only the rows present when the table was last quantized, so it
     would miss a Face saved a moment ago, such as the first of two Faces of one image.
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

`detect` runs detection on every Sampled frame of a Video (`VideoService.sampleTimes`: every
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
5. One Face per Person per Video: when two clusters resolve to the same Person (`recognizeAndStore`), the later,
   lower-quality one is dropped, so a pose change that splits a person in two does not fail the import. The rule holds
   after import too: a Face correction that would give a Person a second Face on a Video keeps the better one (see
   **Corrections** below).

## Storage

`face` row: box (`x1`, `y1`, `width`, `height`), `asset_id`, `person_id` (NOT NULL: a Face always belongs to a
Person), `detection_score`, `features` (the vector, never read back into the model; `FaceRow` omits it), `checksum`
(the crop's SHA-256 in hex, `CHAR(64)`, `COLLATE "C"` on PostgreSQL as the IDs are), `quality`, `is_enrolled`,
`frame_time_ms` (NULL for an image). `is_enrolled` is decided at import and stored, so
changing a threshold later does not re-tier existing Faces, and a Video's support rule is not derivable from quality.

`face_01` makes `(asset_id, repository_id, checksum)` unique: the same crop twice in one asset is a
`DuplicateException`, while two assets may share a byte-identical frame (a trimmed copy of a video, a re-exported photo).
`face_02` is `(person_id, detection_score)` for a person's faces.

The crops live in the file store (`FileSystemStoreService.addFace`) as
`faces/<id[0:2]>/<id>-{display,detected,aligned,aligned-gs}.png`.

## People

A `person` row carries `name`, `is_named`, `num_of_faces`, `cover_face_id`, `is_hidden`, `is_bad_match`, `is_deleted`.
`num_of_faces` is, despite its name, the number of assets outside the trash on which the person has a Face: what a search
for the person finds. A person seen twice in one image (`twins.png`) has two Faces there and counts that asset once.
`PersonService.addFace` inserts the Face, sets the cover face when it is the first, and increments `num_of_faces` only when
the person had no Face on the asset yet (`FaceDao.hasFaceOfPerson`). Recycling an asset takes it from the counts of its
people and restoring gives it back, whether it is restored or moved out of the trash into a folder, each asset once
however many Faces of a person it holds (`PersonDao.addFacesOfAssets`, `COUNT(DISTINCT asset_id)`); only the assets a call
actually recycles or takes out of the trash are counted. `merge` recounts the destination from a search for its assets.
The faces themselves go with the asset on purge, and their files with them, except the cover face of its person. An
import the pipeline drops, or one a crash cut off (pruned at startup), is undone (`LibraryService.discardImport`): the
assets it added to the counts are given back, its faces and their files go, and a person it started, left with no face,
is deleted. Moving a live asset between folders or out of triage does not touch the counts.

The People tab (`PeopleActionController`) lists people by People bucket (`Const.PeopleBucket`; the glossary in
`CONTEXT.md` has the terms): Matched (a Face on at least `Const.FaceRecognition.MIN_FACES_THRESHOLD`, 3, assets outside
the trash, or named), Unmatched (fewer and unnamed), Hidden (hidden, whatever its count or name); a discarded person is in
none. The list
(`PersonService.getInBuckets`, one query) is the union of the buckets switched on, Matched alone by default; Hide / Show,
offered to a Matched person (`Person.isMatched`), moves it between buckets. Its actions:

- **Name**: `updateName` stores the name in NFC and sets `is_named`; a named person is listed whatever their count. A
  named person's `name_for_sort` is its name key (`PersonService.nameKey`: the NFC name lowercased under `Locale.ROOT`),
  and `person_01` keeps that unique over the live people, hidden ones included, so a name is unique regardless of case and
  Unicode form. This is lowercasing, not a full Unicode case fold: "Weiß" and "WEISS", or a Greek name with a final sigma
  and with a plain one ("σοφος" and "σοφοσ"), keep different keys. A merged-away or bad-match person holds no name. An unnamed person's `name_for_sort` is `unknown 0042`, so
  a name of the form `Unknown N`, in any case and spacing, is reserved and refused with a validation error. A change of
  case alone is a rename. A name another live person holds is refused, and the editor offers to merge into that person
  (**Event Flow** in `views/AGENTS.md`).
- **Cover face**: `getPersonFaces` (by detection score) offers the candidates, `setFaceAsCover` picks one.
- **Hide / show**: display only; the person's faces still match.
- **Bad match** (`markAsBadMatch`): the person is taken out of listings and their faces out of the match candidates.
- **Merge** (`merge(dest, source)`): the source's faces move to the destination, the source is soft-deleted, and the
  destination's count is recounted from the search. Both people's rows are locked first (`lockAndGetLive`, in ID order).
  The merged name is decided from the two stored rows, not the models passed in: an unnamed destination with a named,
  live source takes the source's `name`, its name key as `name_for_sort`, and `is_named`; otherwise the destination, named
  or not, keeps all three. A source no longer live (merged away or a bad match) gives no name; a merge of one is not
  refused. The source is soft-deleted first, so `person_01` never sees the name on two live people.

### Corrections

Asset detail's People section (the glossary's Detail panel) acts on one Face at a time, never on a Person as a whole: Remove
deletes a Face, Change gives it to another Person. Each is one `PersonService` transaction followed by a file purge, and
each follows the count rule above: a Face leaving an asset lowers its Person's `num_of_faces` only if they have no other
Face on it, and a Face arriving raises the target's only if they had none (`FaceDao.hasFaceOfPerson`).

- **Remove** (`removeFace(faceId)`): the Person is locked (`lockAndGetLive`), the Face re-read and its row deleted; the
  cover, if it was this Face, becomes the Person's remaining Face with the highest detection score (`getTopFaces`); a
  Person left with no Face row is deleted (`deletePeopleWithoutFaces`), named or not. Once committed the Face's files go
  (`purgeFaceFiles`), given the Person as re-read after the repick, since a cover Face keeps its files and this one is no
  longer the cover; a deleted Person's cover loses its files too.
- **Change** (`moveFace(faceId, FaceTarget)`): the target is a live Person by ID (hidden or not; a merged-away or
  bad-match Person is not found) or a name, which resolves to the live Person holding it in any case or Unicode form
  (`getLiveByNameKey`) or creates a named Person. Creators of one name take turns under the name's lock
  (`PersonDao.lockName`, `BaseDao.advisoryLock`: `pg_advisory_xact_lock` on PostgreSQL, nothing on SQLite, whose one
  write connection serializes the writers), so the loser of a race finds the winner instead of failing on `person_01`.
  Source and target are then locked in ID order, the Face re-read and its `person_id` changed, the vector going with the
  row so later imports match it to the new Person. The source is settled first, as Remove settles a Person (count, cover
  repick, deletion at the last Face), before the target may take the Face as cover, which `person_02` allows one Person
  at a time; the target counts the asset if they had no Face on it and takes the Face as cover only if they have none (a
  new Person's cover is the moved Face). On a Video where the target already has a Face, the lower-quality of the two
  (the target's own on a tie) is deleted as Remove deletes it, and a cover that pointed at it moves to the survivor.
- **Last Face**: a Person is deleted only when no Face row of theirs remains. Faces on assets in the trash are rows, so a
  Person whose every live Face was removed stays, counting zero, until the trash is purged.
- **Refusals**: a Face of a recycled asset is an `IllegalOperationException` (the section is read-only in the trash); a
  Face gone, or moved by a request that held the lock first, is a `NotFoundException`; the Face's own Person a
  `ValidationException`, as is a reserved name.

The routes (`AssetActionController`, under `requireLogin`, each logging the correction on INFO):
`PUT /htmx/asset/r/:repoId/face/:faceId/person` with a JSON body naming `personId` or `name` (one of the two; the name
trimmed and held to the name editor's length rules), and `DELETE /htmx/asset/r/:repoId/face/:faceId`. Both answer the
asset's People section (`htmx/detail_panel_people`: one row per Face with its Person, bad matches left out, Hidden
marked, by `x1` for an image and by Frame time for a Video, `PersonService.getAssetFacesForDetail`), its root carrying
the result message (`Const.Msg.FaceCorrection`) and, when a Person was deleted, their ID and the line that says so; a
refused correction is a plain-text 400 (the body or the target), 404 (gone or moved) or 409 (in the trash), which the
client answers by reloading the section. The controls, the Change combobox and what the client does with an answer are
under **Detail panel** in `altitude/views/AGENTS.md`. `PeopleActionController` serves the Change combobox's options,
`GET /htmx/people/r/:repoId/assignable` (`getAssignable`: the live people who are not a bad match, named first, then by
`name_for_sort`, Hidden among them and marked; `htmx/assignable_people`), and a Person's results title on its own,
`GET /htmx/people/r/:repoId/p/:personId/title` (`htmx/person_inner`), for the count to be refreshed after a correction.

## Settings

All but `face.debug.enabled` are Live settings: an Admin edits them on the Settings screen (`/settings/r/:repoId`), they
are stored in the `setting` table, and the next detection or search uses the new value (an import in flight at its next
stage). Their help text on the screen is the registry's (`models/Settings.scala`); the screen enforces the bounds and the
cross rules (box size ≥ smallest face, `hnsw_ef_search` ≥ 4 × `match_count`, enroll ≥ keep, `max_sampled_frames` ≥
`min_cluster_frames`). `face.debug.enabled` stays a `reference.conf` key, read once at startup.

| Key | Default | Meaning |
|---|---|---|
| `face.yunet.confidence_threshold` | 0.85 | Detector score floor; a non-face rarely scores this, a sharp face 0.91 to 0.95 |
| `face.yunet.nms_threshold` | 0.3 | Detector non-maximum suppression, OpenCV's default |
| `face.detection.bounding_box_size` | 1280 | Detection runs on the image downscaled to fit this |
| `face.detection.min_face_size` | 50 | Smallest face box, in original pixels |
| `face.recognition.cosine_distance_threshold` | 0.55 | Two embeddings closer than this are the same person, in the index and within a Video |
| `face.recognition.match_count` | 5 | Nearest candidates that vote |
| `face.recognition.hnsw_ef_search` | 100 | PostgreSQL only: candidates a search of the vector index keeps as it goes; at least four times `match_count` |
| `face.quality.enroll_threshold` | 18.0 | Quality to start a Person and be a candidate |
| `face.quality.keep_threshold` | 15.0 | Quality to be stored at all; neither tier re-tiers stored Faces |
| `face.debug.enabled` | false | Write the debug artifacts above (a config key, not a Setting) |
| `video.faces.sample_interval_ms` | 1000 | Spacing of Sampled frames |
| `video.faces.max_sampled_frames` | 120 | Cap on Sampled frames per Video |
| `video.faces.min_cluster_frames` | 2 | Fewer frames makes a Video's Face match-only |

## Tests

[FaceDetectionTests](../altitude/test/src/altitude/core/integration/FaceDetectionTests.scala) covers detection on the
`people/` fixtures and the tiers on blurred copies (`TestVideos.frameStill` at `FRAME_BLUR`, `still` at `HEAVY_BLUR`);
[FaceRecognitionServiceTests](../altitude/test/src/altitude/core/integration/FaceRecognitionServiceTests.scala) covers
candidate eligibility, the tie-break and the no-candidate rule on known vectors (`TestContext.addTestFace`), a stored
Face's checksum against the SHA-256 of its stored crop, the same crop twice in one asset, and the Video rules on clips
synthesized by [TestVideos](../altitude/test/src/altitude/test/TestVideos.scala);
[FaceClusteringTests](../altitude/test/src/altitude/core/unit/FaceClusteringTests.scala) covers the pure clustering;
[FaceCorrectionTests](../altitude/test/src/altitude/core/integration/FaceCorrectionTests.scala) covers Remove and
Change on both engines, their two races included (`contend`), and
[AssetActionControllerTests](../altitude/test/src/altitude/core/controller/AssetActionControllerTests.scala) their
routes.
Gaps are listed in [test-coverage.md](test-coverage.md).
