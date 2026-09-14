# Video support

## Goals

- Import video files through the same pipeline as images: store the original, extract metadata (Date Taken, GPS
  position, dimensions, duration), generate a Preview, and detect and recognize faces.
- Play a video in the asset detail view, with seeking.
- Keep a Java runtime the only requirement of the installation.

Out of scope, for later plans: transcoding to a browser-playable rendition (an iPhone HEVC `.mov` plays only where the
browser supports HEVC), hover-scrubbing in the grid, a per-person timeline of appearances, and audio-only assets.

## Design

### Media types

`LibraryService.SUPPORTED_MEDIA_TYPES` holds `image` and `video`. Tika's detector already reports `video/mp4`,
`video/quicktime`, `video/webm`, `video/x-matroska` and `video/x-msvideo`; any container FFmpeg demuxes is accepted.
"Is a video" is derived from `asset.media_type` everywhere; there is no flag column.

### Staged files instead of in-memory bytes

`AssetWithData` carries the asset and the path of its staged file, not a byte array. The pipeline element types in
`PipelineTypes` are unchanged in shape.

- Undertow already writes every multipart file item to a temp file and deletes it when the request completes.
  `ImportController.uploadFilesForm` moves that file into `data/staging/<uuid>` (`Files.move`, same filesystem as the
  store so the later rename is atomic) and computes the checksum while streaming it, through a streaming variant of
  `MurmurHash.hash32` that produces the same value as the array variant.
- `MetadataExtractionService.detectAssetType` and `extract` read from the path (`TikaInputStream.get(path)`,
  `ImageMetadataReader.readMetadata(file)`).
- `AssetWithData.bytes` reads the whole file, for the image-only consumers (`makeImageThumbnail`, `ImageIO`,
  `matFromBytes`), which are only ever handed images.
- `FileStoreFlow` renames the staged file into `files/<id[0:2]>/<id>`. A `FileStoreService.assetFile(id)` accessor
  exposes the stored file for streaming.
- An invalid asset (unsupported type, duplicate) and any failed element delete their staged file; `Altitude` startup
  empties `../../data/staging`.
- `TestContext.makeAssetWithData` and `IntegrationTestUtil.getImportAsset` write their bytes to a staged file.

### Decoding

`org.bytedeco:javacv:1.5.10` is a dependency, and the FFmpeg 6.1.1 natives are on the classpath through a classifier
dependency for the detected platform (`;classifier=linux-x86_64` and so on, next to the base jar), which is what the
platform suffix in `../../build.mill` is meant to do and does not. The default (LGPL) FFmpeg build decodes H.264, HEVC, VP9
and AV1.

A `VideoService` wraps `FFmpegFrameGrabber`:

- `probe(path): VideoInfo` with display width and height (rotation from the container's display matrix applied),
  duration in milliseconds, frame rate and audio presence.
- `sampledFrames(path, times)`: seeks to each Frame time and yields the decoded frame as an `org.opencv.core.Mat`
  (`OpenCVFrameConverter.ToOrgOpenCvCoreMat`), rotated upright. Frames are released by the consumer as it goes.
- `previewFrame(path)`: walks the Sampled frames and returns the first whose mean luminance clears
  `video.preview.min_luminance`, or the frame at 10% of the duration when none does.

A grabber is opened per call; nothing is shared across threads.

### Sampling

The Sampled frames of a Video are evenly spaced from its start at an interval of
`max(video.faces.sample_interval_ms, duration / video.faces.max_sampled_frames)`. Defaults: 1000 ms and 120 frames,
so a short clip is sampled every second and a two-hour recording every minute. Both are `Const.Conf` keys in
`reference.conf`.

### Metadata

metadata-extractor already reads MP4 and QuickTime containers into `ExtractedMetadata` under the directories `MP4`,
`MP4 Video`, `QuickTime`, `QuickTime Video` and `QuickTime Metadata`. The resolvers read only persisted inputs, as
before, so a later replay needs no file.

- Dimensions and duration come from `VideoService.probe` in `ExtractMetadataFlow` (`AssetService.getDimensions` and a
  new `getDuration` dispatch on media type). `width` and `height` hold the display size, so a portrait phone video is
  stored as portrait. `area_size` stays `width * height`.
- `CaptureDateResolver` gains two rungs below the EXIF, XMP, IPTC and PNG text rungs: `QuickTime Metadata` /
  `Creation Date` (`com.apple.quicktime.creationdate`, a local wall clock with an offset, which is discarded like every
  other offset) as `CaptureDateSource.QuickTimeCreationDate` (`qt_creation_date`); then `MP4` or `QuickTime` /
  `Creation Time`, the container's UTC instant stored as the UTC wall clock it spells, as
  `CaptureDateSource.ContainerCreationTime` (`container_creation_time`), placed with the PNG tIME rung, above the GPS
  and filename rungs. `WallClockParser` accepts the string format the extractor uses for date tags. The 1904 epoch is
  already a sentinel.
- `GeoLocationResolver` tries the EXIF `GPS` directory first, then `QuickTime Metadata` / `ISO 6709`
  (`+37.3318-122.0312+015.000/`, parsed as signed decimal degrees), then `MP4` / `Latitude` and `Longitude`.
- `Asset.getPublicMetadata` takes `deviceModel` from `QuickTime Metadata` / `Model` when the EXIF value is absent.

### Preview

`AssetService.genPreviewData` gains a `video` case: `VideoService.previewFrame` encoded to PNG, then the existing
`makeImageThumbnail`, so the 200 px PNG, `MimedPreviewData` and the file store are unchanged. The map and the grid
keep showing the Preview.

### Faces in a Video

`FaceDetectionService.extractFaces` has an overload taking a decoded `Mat`; the byte-array overload decodes and
delegates. `FaceRecognitionService.processAsset` dispatches on media type:

1. For each Sampled frame, detect faces and compute embeddings; keep, per detection, the Face (with its Frame time)
   and its `FaceImages`, and release the frame.
2. Cluster the detections of the whole video: in descending detection score, a detection joins the first cluster
   whose representative is within `face.recognition.cosine_distance_threshold` of it, or starts a new cluster. The
   representative is the cluster's first (highest-scoring) detection; its embedding, crop, bounding box and Frame time
   are what the Face stores, so the saved face images and the vector agree.
3. Each cluster's representative goes through the existing `recognizeFace` top-K vote, `PersonService.addFace` and
   `FileStoreService.addFace`. When two clusters of the same video resolve to the same Person, the lower-scoring one
   is dropped instead of raising `SamePersonDetectedTwiceException`: one Face per Person per Video, and a pose change
   that splits a person into two clusters does not fail the import.

Both pipelines get this through the service, since `IndexAndFaceRecFlow` (SQLite) and `FacialRecognitionFlow`
(Postgres) both call `processAsset`.

### Schema and models

`all.sql` for both engines, as fresh definitions:

- `asset.duration_ms BIGINT NULL` (SQLite `INTEGER`): NULL for an image.
- `face.frame_time_ms INTEGER NULL`: NULL for a Face in an image; the Frame time of the crop and box otherwise.

`Asset.durationMs: Option[Long]`, `Face.frameTimeMs: Option[Long]`, `FieldConst`, `AssetDao` and `FaceDao` read and
write them; JSON carries `duration_ms` and `frame_time_ms`.

### Serving the original

`ContentViewController` serves `file` content by streaming the stored file with a `geny.Writable` window over a
`FileChannel`: `Accept-Ranges: bytes`, a `206` with `Content-Range` for a single `bytes=a-b` range, `416` for an
unsatisfiable one, `200` with `Content-Length` otherwise. `Content-Type` is the asset's stored `mime_type`, for images
too, instead of `application/octet-stream`. Previews and faces are unchanged.

### UI

- `result_cell.scala.html`: a `data-media-type` attribute on the cell, a play badge over the thumbnail of a Video,
  and a duration line in the metadata block (`Util.humanReadableDuration`, `m:ss` or `h:mm:ss`).
- `view_image_detail_modal.scala.html` holds an `<img>` and a `<video controls preload="metadata">`; the fragment
  dataset carries the media type. `image-detail.js` and `detail-navigator.js` show one element and unload the other
  (`hidden`, and `src` cleared on the one not shown), wait for `load` or `loadedmetadata`, size the modal from the
  stored width and height for both, and pause playback on next, previous and close.

### Tests

Videos are synthesized at test time by a `TestVideos` helper that encodes short MP4s (`FFmpegFrameRecorder`, `mpeg4`
codec) from the existing `people/` images: one face for a few seconds, two people one after the other, a black leader
before the first face. Container dates and locations are covered by seeding `ExtractedMetadata`, the way
`AssetDateStorageTests` does. The unused `audio/*.mp3` fixtures cover "unsupported media type" with a real file.

## Tasks

Each group is one reviewable, mergeable unit; groups 1 to 3 change no user-visible behavior.

### 1. Decoding

1. **Dependencies.** Add `org.bytedeco:javacv:1.5.10`; replace the platform-suffixed bytedeco lines in `../../build.mill`
   with classifier dependencies for the detected platform so the FFmpeg natives resolve. Rationale: OpenCV's
   `VideoCapture` has no FFmpeg backend in this build, and the FFmpeg preset currently resolves only its Java stub.
2. **`VideoService`.** `probe`, `sampledFrames`, `previewFrame`, the sampling schedule and the luminance floor, with
   `Const.Conf` keys and `reference.conf` defaults. Unit tests over a synthesized clip: duration, display size of a
   rotated clip, frame count of the schedule for a 3 s and a 20 min duration, first non-dark frame past a black
   leader. Rationale: all later groups decode through this one service.
3. **`TestVideos` helper.** Synthesized clips from the `people/` images. Rationale: deterministic fixtures, no
   binaries in git.

### 2. Staged files

4. **`AssetWithData` carries a path.** Staging directory, streaming checksum, `detectAssetType` and `extract` from a
   path, `bytes` for image consumers, `FileStoreFlow` rename, `assetFile` accessor, staged-file cleanup on invalid
   assets, failures and startup, and the test helpers. Rationale: a video must not sit in the heap for the whole
   pipeline; images take the same path so there is one code path.
5. **Upload moves Undertow's temp file.** `ImportController` stages the file instead of `readAllBytes`. Rationale:
   Undertow deletes its temp file when the request ends, before the queued pipeline runs.

### 3. Schema and models

6. **`duration_ms` and `frame_time_ms`.** Columns in both `all.sql`, model fields, DAOs, JSON. Rationale: the cell
   shows duration, and a video Face is meaningless without its Frame time.

### 4. Import a video

7. **Accept `video`.** Allowlist entry; dimensions and duration from `probe` in `ExtractMetadataFlow`; the `video`
   Preview case; an mp3 stays invalid. Integration tests: an imported clip is a Video with duration, display size and
   a Preview file.
8. **Container dates and positions.** The two capture-date rungs and sources, the parser format, the ISO 6709 and MP4
   coordinate rungs, `deviceModel` from QuickTime metadata. Tests seed `ExtractedMetadata` for each rung and its
   priority. Rationale: Date Taken and the map work for phone videos without zone guessing.

### 5. Faces in a video

9. **`extractFaces(Mat)`.** The byte-array overload delegates. Rationale: frames are already decoded.
10. **Sampling, clustering, one Face per Person per Video.** `processAsset` video branch, Frame time on the Face,
    the same-Person merge rule. Tests: one person in a clip gives one Face with a Frame time inside the clip; two
    people in sequence give two Faces; the same person in a second clip matches the same Person; the same person in a
    photo and a clip is one Person.

### 6. Playback

11. **Range streaming.** `ContentViewController` file branch with the stored mime type. Controller tests: `200` with
    `Accept-Ranges`, `206` for `bytes=0-99`, `416` past the end, an image's `Content-Type` is `image/jpeg`.
12. **Cell and detail view.** Badge, duration line, `data-media-type`, the `<video>` branch in the detail fragment and
    navigator, pause on navigation and close.

### 7. Documentation

13. **Docs.** README no longer lists video as missing; `../../altitude/AGENTS.md` gets a Video section (staging, decoding,
    sampling, clustering, streaming) and the pipeline paragraph mentions staged files; `reference.conf` comments for
    the new keys. `../../CONTEXT.md` already defines Video, Preview, Sampled frame and Frame time.
