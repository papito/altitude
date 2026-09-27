# Face quality tiers and centroid video clustering

## Goals

- A poor face (blurred, obscured, seen in one frame of a video) does not start a Person, so the same person does not
  come back as several Unknowns.
- A poor face may still join a Person it clearly matches, and is reviewable there through the existing bad match and
  merge actions.
- A Video's Face stands on all of its frames, not on one.

Out of scope: a dedicated face image quality model, merge suggestions between people, and any change to the People UI.

## Face quality

`FaceDetectionService.getArcFaceEmbedding` returns an `Embedding`: the L2-normalized ArcFace vector and the norm the raw
output had before normalization. The norm is the face's quality, stored as `face.quality`. On the test portraits, sharp
faces measure 19 to 22, the same faces blurred at 5% of their width 15 to 19.6, at 8% 14 to 17, eyes covered 16 to
19.5. The thresholds in `reference.conf` are calibrated on those fixtures:

| Key | Default | Meaning |
|---|---|---|
| `face.quality.enroll_threshold` | 18.5 | At least this to start a Person and be a match candidate |
| `face.quality.keep_threshold` | 15.0 | At least this to be stored at all |
| `video.faces.min_cluster_frames` | 2 | Fewer distinct Sampled frames than this makes a Video's Face match-only |

With `face.debug.enabled=true`, every detection is labelled `q=<quality> d=<score>` on the annotated image and its
aligned 112 px crop is written as `<base>-<n>-q<quality>-aligned.png`; a Video's frames are named by Frame time. A
detection below the floor is dumped, then dropped, so the crops behind a threshold can be looked at.

## Tiers

`face.is_enrolled` is decided at import and stored, so changing a threshold later does not re-tier existing faces.

- Enrolled: quality at or above the enroll threshold, and for a Video, support of at least `min_cluster_frames`.
- Match-only: everything else at or above the keep threshold.

`FaceRecognitionService.recognizeFace(face): Option[Person]` votes over the top `match_count` enrolled faces of people who
are not a bad match within the distance threshold; most votes win and a tie goes to the closest. With no candidate an
enrolled face starts a new Person; a match-only face is `None` and is not stored. `searchClosestFaceMatches` on both
engines joins `person` and filters `face.is_enrolled` and `person.is_bad_match`.

## Video

`FaceRecognitionService.cluster` is greedy leader clustering of a Video's detections in descending quality;
`mergeClusters` then absorbs a cluster whose centroid is within the threshold of an earlier cluster's centroid. Each
cluster is one Face: crop, box, checksum, Frame time and quality of the best-quality detection, the normalized mean of
all members as the vector, and support counted as distinct Frame times. One Face per Person per Video, the
higher-quality cluster winning.

## Schema

`face.quality FLOAT NOT NULL`, `face.is_enrolled BOOLEAN NOT NULL`, and `face_01` unique on
`(repository_id, asset_id, checksum)`: two assets may share a byte-identical frame.

## Tests

- `FaceDetectionTests`: a face-relative blur lowers the norm; sharp is enrolled, `TestVideos.LIGHT_BLUR` is match-only,
  `HEAVY_BLUR` is detected but dropped.
- `FaceRecognitionServiceTests`: candidate eligibility, tie-break and the no-candidate rule on known vectors
  (`TestContext.addTestFace`); a blurred photo or clip of nobody leaves nothing; one frame of a clip starts no Person but
  joins a known one; the same person across clips and a photo is one Person.
- `FaceClusteringTests` (unit): the normalized mean, quality-ordered clustering, the representative, the merge pass.
