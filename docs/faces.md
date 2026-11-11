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
   after import too: a Face correction or a merge that would give a Person a second Face on a Video keeps the better one
   (see **Merge** under **People** and **Corrections** below).

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
`num_of_faces` is, despite its name, the number of assets outside the trash on which the person has a Face, triaged assets
included: what a search for the person finds with "Show triaged items" on, once none of those assets is still being
imported. A person seen twice in one image (`twins.png`) has two Faces there and counts that asset once.
`PersonService.addFace` inserts the Face, sets the cover face when it is the first, and increments `num_of_faces` only when
the person had no Face on the asset yet (`FaceDao.hasFaceOfPerson`). Recycling an asset takes it from the counts of its
people and restoring gives it back, whether it is restored or moved out of the trash into a folder, each asset once
however many Faces of a person it holds (`PersonDao.addFacesOfAssets`, `COUNT(DISTINCT asset_id)`); only the assets a call
actually recycles or takes out of the trash are counted, and the people are locked first (`PersonDao.lockAssetPeople`, after
the assets and in ID order), so a correction holding one of them (see **Corrections**) ends before the count is read: read
while it held, from a snapshot in which its Face still existed, the count would take the asset from the Person twice.
`merge` recounts the destination from a search for its assets.
The faces themselves go with the asset on purge, and their files with them, except the cover face of its person. An
import the pipeline drops, or one a crash cut off (pruned at startup), is undone (`LibraryService.discardImport`): the
assets it added to the counts are given back, its faces and their files go, and a person it started, left with no face,
is deleted. Moving a live asset between folders or out of triage does not touch the counts.

The People tab (`PeopleActionController`) lists people by People bucket (`Const.PeopleBucket`; the glossary in
`CONTEXT.md` has the terms): Matched (a Face on at least `Const.FaceRecognition.MIN_FACES_THRESHOLD`, 3, assets outside
the trash, or named), Unmatched (fewer and unnamed), Hidden (hidden, whatever its count or name); a discarded person is in
none. The list
(`PersonService.getInBuckets`, one query) is the union of the buckets switched on, Matched alone by default; Hide / Show,
offered to a Matched person (`Person.isMatched`), moves it between buckets.

The number on a person's cell is what opening them from the repository view finds (`PersonService.getListedInBuckets`, a
`ListedPerson`'s `visibleCount`, read in one snapshot). `GET /htmx/people/r/:repoId/list` takes `triaged` as the repository
view does (`SearchRequestParser`): only `true` shows triaged items. With them shown the number is `num_of_faces`, and the
triaged assets are not read. With them hidden it is `num_of_faces` less the person's triaged assets outside the trash
(`PersonDao.getTriagedAssetCounts`, each asset once, those still being imported among them), and a person left with 0 or
less is left off the list. A list that comes out empty with someone in the buckets (`ListedPeople.leftOffByTriage`; the
buckets read only people with a count above zero, so the subtraction is what dropped them) says "Everyone here is only in
triaged items. Turn on Show triaged items to see them." in place of "No one in the buckets switched on". Only the number follows the switch: the buckets and the order are those of the whole
`num_of_faces`, so the switch moves no one between buckets, and the Discard link, `Person.isMatched`, the merge modal's
"instances" and the merge direction read `num_of_faces` as well, since a merge moves every Face.

In Triage the tab asks for `triaged=true` on every load, whatever the switch: it lists every Person of its buckets, those
found only in triaged assets included. The numbers are rendered and not displayed there, since they read confusingly
against what Triage holds. Triage finds only triaged assets, so a click on a person's face or name asks the Triage scope
route first (`GET /htmx/triage/r/:repoId/dialogs/scope?personId=`, `TriageController`): a person with a Face on a
triaged asset outside the trash is a 204, and the click searches Triage scoped to them; any other live person gets a
popover dialog under the cell's name, "This person has no assets assigned in Triage", with "Show person in new tab",
Browse scoped to them in a new tab, and Close (**The explorer in Triage** and **Scope dialog and Quick view** in
`views/AGENTS.md`). The tab manages people there as in Browse: rename by the pencil, merge by drag and drop.

A merge reloads the tab and confirms in the snackbar, and runs no search of its own, in Browse and Triage alike: results
that are neither person's stay as they are, results scoped to the source, who is gone, become the destination's in the
same view, and results scoped to the destination are read again with the Faces the merge gave it (**Event Flow** in
`views/AGENTS.md`). A merge from the Detail panel reloads the tab and confirms in the snackbar too, and searches only on
closing Asset detail, and only when the results are scoped to one of the two people: results scoped to the merged person
become the destination's then, and results scoped to the destination are read again, while every other result stays as it
is (**Detail panel** in `views/AGENTS.md`).

The Triage summary above the Triage explorer's tabs counts, among other things, the assets in triage that have an
**Unknown person** (the glossary's term): a triaged asset outside the trash with a Face of a Person who has no name and is
neither hidden, merged away nor discarded as a bad match, whatever their bucket, so an Unmatched person or a Matched one.
A named person on the same asset does not keep it out; an asset whose Faces are all of named, hidden, merged-away or
discarded people, or that has none, is not counted. The number searches those assets (`subset=unknownPeople`,
`SearchQueries.unknownPeopleFilter`; the server takes the subset in any view) under the Results title "Unknown people".
Naming, hiding, discarding and merging a person, a Face correction and every asset mutation announce `peopleChanged`,
which reloads the summary (`js/listeners/triage.js`).

While assets are being imported the number and a search can differ, as a search skips those assets and `num_of_faces` holds
their Faces. With triaged items shown, the number runs ahead of the search by those assets until they complete. With them
hidden the subtraction takes them out again, except after a merge: its recount goes through a search, so they are not in
`num_of_faces`, while the subtraction still includes them. One sorted asset and two still being imported give 1 − 2. No
negative number is shown: such a person is left off the list until the next recount.

The tab's actions:

- **Name**: `updateName` stores the name in NFC and sets `is_named`; a named person is Matched whatever their count. A
  named person's `name_for_sort` is its name key (`PersonService.nameKey`: the NFC name lowercased under `Locale.ROOT`),
  and `person_01` keeps that unique over the live people, hidden ones included, so a name is unique regardless of case and
  Unicode form. This is lowercasing, not a full Unicode case fold: "Weiß" and "WEISS", or a Greek name with a final sigma
  and with a plain one ("σοφος" and "σοφοσ"), keep different keys. A merged-away or bad-match person holds no name. An unnamed person's `name_for_sort` is `unknown 0042`, so
  a name of the form `Unknown N`, in any case and spacing, is reserved and refused with a validation error. A change of
  case alone is a rename. A name another live person holds is refused, and the form offers to merge into that person
  (**Event Flow** in `views/AGENTS.md`). There are two forms over one rename (`PeopleActionController.renamePerson`, whose
  `RenameOutcome` is `Saved` or `Refused`, each route rendering it its own way): the results title's inline name editor
  (`PUT /htmx/people/r/:repoId/p/:personId/name/edit`, answering the name or the editor again) and the rename dialog a
  pencil on the person's cell in the People tab opens as a popover, in Browse and in Triage
  (`GET /htmx/people/r/:repoId/dialogs/rename-person`, `PUT /htmx/people/r/:repoId/p/:personId/rename`, answering an
  empty success or the dialog's form again). The two dialog routes find the person through
  `PersonService.getInRepository`, so an unknown person, or another repository's, is a 404. Either rename reloads the
  People tab, since naming an Unmatched person makes them Matched. A person with no name can also be named from the
  Detail panel, by Change on a Face of theirs with a new name, which calls `updateName` too, and merged there into a named
  person (**Corrections**). A name another live person holds is never a new name there, since typing it makes that
  person's option the active one (a merge, or with "This face only" on a move); a holder who took it meanwhile makes the
  name a 400 with no offer to merge.
- **Cover face**: `getPersonFaces` (by detection score) offers the candidates, `setFaceAsCover` picks one.
- **Hide / show**: display only; the person's faces still match.
- **Bad match** (`markAsBadMatch`): the person is taken out of listings and their faces out of the match candidates.
- **Merge** (`merge(dest, source)`): the source's faces move to the destination, the source is soft-deleted, and the
  destination's count is recounted from the search. Both people's rows are locked first (`lockAndGetLive`, in ID order).
  The merged name is decided from the two stored rows, not the models passed in: an unnamed destination with a named,
  live source takes the source's `name`, its name key as `name_for_sort`, and `is_named`; otherwise the destination, named
  or not, keeps all three. A source no longer live (merged away or a bad match) gives no name; a merge of one is not
  refused. The source is soft-deleted first, so `person_01` never sees the name on two live people. One Face per Person
  per Video holds as at import (**Videos**): on each Video where both had a Face (`FaceDao.getFacesOnSharedVideos`, read
  before the Faces move), the higher-quality Face stays, the destination's own on a tie as for a move, and the other is
  deleted as Remove deletes it (`keepOneFacePerVideo`), after the source is soft-deleted, so a cover of either person that
  pointed at it can move to the survivor, which `person_02` allows. The deleted Faces' files go once the merge commits,
  given the destination as re-read after it, whose cover keeps its files. An image may hold two Faces of one person, so
  a merge keeps both there, and the recount counts the asset once. The Detail panel's Change merges through it as well,
  a person with no name into a named one (**Corrections**).

### Corrections

Asset detail's People section (the glossary's Detail panel) acts on the selected Face: Remove deletes it, Change gives it
to another Person. For a Face of a Person with no name (`is_named` false, Hidden or not), Change acts on that Person as a
whole unless its "This face only" switch is on: a new name names them, and a named Person picked merges them into that
Person, so every asset of theirs follows, not just the one shown (**Name** and **Merge** below). A nameless Person picked
still gets this Face alone, so two nameless people are never merged from the panel (the People tab's drag and drop does
it), and a named Person's Face always moves alone, so the panel never renames a named Person or merges them away (the
results title and the People tab's pencil rename them).
Each correction is one `PersonService` transaction. Once it commits, the files of the Faces it deleted go: Remove's Face
always, a move's only on a Video where the target already had a Face (the one of the two that lost), and a merge's only
on the Videos where the two people both had one; a name deletes no Face. Each follows the count rule above: a Face leaving an asset
lowers its Person's `num_of_faces` only if they have no other Face on it, and a Face arriving raises the target's only if
they had none (`FaceDao.hasFaceOfPerson`). A name changes no count, and a merge recounts the destination as every merge
does.

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
- **Name** (`nameFacePerson(faceId, personId, name)`): names the Face's Person through `updateName`, as the tab's **Name**
  does, so the same Person, with their ID, cover and count, carries the name on every asset of theirs. It takes the name's
  lock first (`lockName`), as a move to a name does, so a move creating a Person of that name meanwhile waits for it and
  then finds the named Person holding the name, instead of failing on `person_01`.
- **Merge** (`mergeFacePerson(faceId, personId, destId)`): merges the Face's Person into the named Person `destId` through
  `merge(dest, source)`, as the tab's **Merge** does: every Face of theirs becomes the destination's, whose ID, name and
  Hidden state stay, the source is soft-deleted and one Face per Person per Video holds; the lost Faces' files go once the
  correction's transaction commits.

  Both answer a `FacePersonChange(face, before, after)`: the Face, its Person as they were, with the name they had, and the
  Person who has the Face now, the same Person named or the destination. `personId` is the Person the client showed with
  the Face, checked rather than read from the Face: the Face may have been given to someone else since the panel was
  rendered, and the name must not land on, nor the merge take, whoever has it now. In one transaction (`withTransaction`
  joins the outer one, so `updateName` runs inside it, and the merge runs `merge`'s body, `mergeLocked`, in it) the Person
  is locked, with the destination in ID order for a merge (`lockAndGetLive`, each of them live in this repository), the
  Face is re-read under the lock (`lockedFace`, as Remove and Change re-read it, so it must still be that Person's and on
  a live asset), and the Person, read again, must still have no name. Beyond that and a merge's named destination they
  add no rule of their own: a held name is `updateName`'s refusal, and `merge` keeps the destination's name. Each logs on
  DEBUG.
- **Last Face**: a Person is deleted only when no Face row of theirs remains. Faces on assets in the trash are rows, so a
  Person whose every live Face was removed stays, counting zero, until the trash is purged.
- **Locks**: a correction locks the people it touches (`lockAndGetLive`, ID order: the Face's Person and the target or the
  destination), after the name's lock when it gives a name (a move to a name, a Name: the name, then the people), and
  reads its asset without a lock, while a
  recycle or restore locks its assets first (`AssetDao.lockAssets`) and their people after (`lockAssetPeople`): assets, then
  people, in every transaction that locks both, so none waits on another in a cycle, and a recycle counts an asset's people
  only once a correction holding one of them has ended.
- **Refusals**: a Face of a recycled asset is an `IllegalOperationException` (the section is read-only in the trash); a
  Face gone, or moved by a request that held the lock first, is a `NotFoundException`; the Face's own Person a
  `ValidationException`, as is a reserved name. A Name or a Merge refuses as well, changing nothing: a Face no longer
  `personId`'s, or a Person or destination no longer live or of another repository, is a `NotFoundException`; a Person
  who has a name by now (`Const.Msg.Err.FACE_PERSON_NAMED_SINCE`, "This person has a name now") is an
  `IllegalOperationException`, as the section showing them nameless is stale; a destination with no name
  (`MERGE_INTO_NAMELESS`, "Merge into a person with a name", which the panel never offers) and the Person themselves as
  the destination (`FACE_ALREADY_THEIRS`, checked before the transaction) are a `ValidationException`; a name another
  live Person holds is `person_01`'s `DuplicateException`.

The routes (`AssetActionController`, under `requireLogin`, each logging the correction on INFO):
`PUT /htmx/asset/r/:repoId/face/:faceId/person` with a JSON body naming `personId` or `name` (one of the two; the name
trimmed and held to the name editor's length rules); for a Face's Person as a whole,
`PUT /htmx/asset/r/:repoId/face/:faceId/person/name` with `{personId, name}` (both required, `personId` a UUID, the name
trimmed and held to the same rules) and `PUT /htmx/asset/r/:repoId/face/:faceId/person/merge` with
`{personId, destPersonId}` (both required UUIDs); and `DELETE /htmx/asset/r/:repoId/face/:faceId`. Each answers the
asset's People section (`htmx/detail_panel_people`: one row per Face with its Person, bad matches left out, Hidden
marked, by `x1` for an image and by Frame time for a Video, `PersonService.getAssetFacesForDetail`), its root carrying
the result message (`Const.Msg.FaceCorrection`: `MOVED`, `REMOVED`, and for a name `NAMED`, "%s is now %s", and for a
merge `MERGED`, "%s merged into %s", both by the name the Person had), when a Person was deleted their ID and the line
that says so, and after a merge the merged Person's ID (`data-app-merged-person-id`) and the destination's
(`data-app-merged-into-id`). A refused correction is a plain-text 400 (the body, the target, a reserved name, a
nameless destination, or a name another live Person holds, whose `DuplicateException` the name route catches and
answers with `PERSON_NAME_ALREADY_EXISTS`, "Person by that name already exists", offering no merge), a 404 (the Face gone
or moved, a Person or destination no longer live or of another repository) or a 409 (in the trash, or a Person named
meanwhile). The client shows a 400's message and changes nothing, and reloads the section on a 404 or 409, so a row
whose Person was named meanwhile comes back as a named Person's, offering no name or merge. The controls, the Change
combobox and what the client does with an answer are
under **Detail panel** in `altitude/views/AGENTS.md`. `PeopleActionController` serves the Change combobox's options,
`GET /htmx/people/r/:repoId/assignable` (`getAssignable`: the live people who are not a bad match, named first, then by
`name_for_sort`, Hidden among them and marked; `htmx/assignable_people`), with the Suggested group below when asked with
`faceId`, and a Person's results title on its own, `GET /htmx/people/r/:repoId/p/:personId/title` (`htmx/person_inner`),
read again after a correction because the title's Hide and Discard links depend on the count.

- **Suggested people** (`suggestedPeople(faceId)`): the people whose Faces are nearest the Face's own vector, closest first,
  at most `PersonService.SUGGESTED_PEOPLE` (3), so that most wrong matches take one click. The candidates are what
  recognition sees, through the same statement with one more filter, so no new Setting: enrolled Faces of people who are
  not a bad match, within `face.recognition.cosine_distance_threshold`, in the context repository, read inside
  `withFaceVectorReadOnly`, a read since nothing is stored (`FaceDao.searchNearestFacesOfOthers`, beside `searchClosestFaceMatches`: on PostgreSQL
  `CLOSEST_OF_OTHERS_SQL`, the HNSW scan carrying on past the excluded Faces, on SQLite the full scan filtered afterwards).
  Unlike recognition it leaves out the Face's current Person, in the SQL, since a Person with thousands of Faces would
  otherwise fill every candidate slot; the Face's vector is read from the store (`FaceDao.getFeatures`), since the model
  never carries it. The nearest `SUGGESTION_CANDIDATES` (20) Faces are reduced to their first three distinct people.
  Nothing is stored and nothing votes: the suggestion only orders what the user chooses from. Nobody is suggested when no
  Face is near enough, as for a poor Face, whose vector has drifted; Hidden people are suggested and marked. The route
  renders the group first (`htmx/assignable_people`: the ARIA listbox group pattern, `role="group"` labelled by its
  heading, each option marked `data-suggested`) and leaves those people out of the list after it, so each Person is one
  option; a Face gone or of another repository is a 404.

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
candidate eligibility, the tie-break and the no-candidate rule on known vectors (`TestContext.addTestFace`,
`TestContext.unitVector` and `between`), a Face's vector read back and the nearest Faces of other people (the Suggested
group's search: closest first, the Face's Person, bad matches, match-only Faces, those past the threshold and another
repository's left out), a stored Face's checksum against the SHA-256 of its stored crop, the same crop twice in one asset,
and the Video rules on clips synthesized by [TestVideos](../altitude/test/src/altitude/test/TestVideos.scala);
[FaceClusteringTests](../altitude/test/src/altitude/core/unit/FaceClusteringTests.scala) covers the pure clustering;
[FaceCorrectionTests](../altitude/test/src/altitude/core/integration/FaceCorrectionTests.scala) covers Remove and
Change on both engines, their four races included (`contend`: two moves of one Person's Faces on an asset, a move of a Face
a concurrent request removed, two moves to one new name, and a recycle of an asset during a removal of a Face on it), the
Name and Merge of a Face's Person with their refusals and their races (a name or a merge during a move of the Face, a
move during a merge, two merges of one Person, a move to a name during a Name to it), a merge's one Face per Person per
Video, and the suggested people of a Face, and
[AssetActionControllerTests](../altitude/test/src/altitude/core/controller/AssetActionControllerTests.scala) their
routes, [PeopleActionControllerTests](../altitude/test/src/altitude/core/controller/PeopleActionControllerTests.scala) the
assignable list with its Suggested group.
Gaps are listed in [test-coverage.md](test-coverage.md).
