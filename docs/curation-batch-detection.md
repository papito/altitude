# Curation: batch detection, quality metrics, face grading

Research notes for a Curate view in Triage, where the user picks the best image out of a batch of related shots.
The user selects the batch themselves in the first version; these notes cover what it would take to propose batches
automatically, to measure image quality, and to grade a batch on its faces. Written 2026-10-31 against the stack of
that day.

**Implemented** (see **Sequences and Image quality** in [altitude/AGENTS.md](../altitude/AGENTS.md)): every still image
gets an Image quality at import (`ImageMeasurement`: sharpness over the face boxes or the frame, luminance, clipped
highlights, crushed shadows, and a 0 to 100 score), a pHash (`PerceptualHash`), and a Sequence when the import finds it
shot in one burst (`SequenceService.assign`: capture time to the subsecond, same camera and size, hashes within
`sequence.max_hash_distance`, within `sequence.max_gap_ms`; the two Triage Settings). **Not implemented**: the Curate
view itself, session grouping by a global image embedding (section 1's later layers), learned quality models (BRISQUE,
NIMA, section 2), per-image face grading and cast coverage (section 3), and anything for videos and animated images.
The notes below are the research as written and describe options, not the code.

## What the project already has

The findings below lean on what ships today, so most of it needs no new native dependency:

- **OpenCV 4.9** via bytedeco, with the contrib modules: `img_hash`, `quality`, `features2d` (SIFT is in the main
  module since 4.4), `xfeatures2d`, `photo`, `ximgproc`, `dnn`, `dnn_superres`. The build file lists the presets
  (`build.mill`, `bytedecoPresetLibs`).
- **ffmpeg 6.1** and **openblas** via bytedeco.
- **Faces**: YuNet detection with five landmarks and a score, ArcFace 512-float embeddings, and the embedding norm as
  `Face.quality` (see [faces.md](faces.md)). The Video path clusters detections across Sampled frames with the pure
  `cluster` and `mergeClusters` functions of `FaceRecognitionService`.
- **Vectors**: a pgvector column on PostgreSQL and the `sqlite-vector` extension on SQLite, wrapped by `withFaceVector`.
- **Metadata**: metadata-extractor 2.19 feeds `ExtractedMetadata` and the `PublicMetadata` fields (device model,
  f-number, focal length, ISO, exposure time, date/time original), plus `originalCreatedAt` with its
  `CaptureDateSource`, `width`, `height`, GPS, and a SHA-256 `checksum` of the file.
- **Import pipeline** stages that already hold the decoded BGR `Mat` of each image (`DetectFacesFlow`), so per-image
  metrics can be computed there without decoding again.

## 1. Detecting that images belong together

Treat it as layers, cheapest first, and escalate only for borderline pairs. Group with union-find over pairs that
pass, restricted to a sliding window over assets sorted by capture time (the previous five or so assets within the
time window), so the cost stays linear in the number of assets.

### Metadata (already extracted, free)

- **Capture time gap** is the strongest single signal for bursts. Add the EXIF subsecond tag (`SubSecTimeOriginal`,
  0x9291): many bodies shoot over ten frames a second and whole seconds collide.
- **Same device model, dimensions, orientation and focal length**, and sequential file names, confirm a candidate.
- **Maker sequence tags**: Apple writes a burst UUID in its MakerNote, Sony writes sequence numbers; metadata-extractor
  exposes these directories.
- Time alone cannot tell two subjects shot back to back apart, so this layer only proposes candidates.

### Perceptual hash (`opencv_img_hash`, ships)

- `PHash` (DCT over a 32×32 downscale, 64 bits) or `BlockMeanHash`; `RadialVarianceHash` tolerates rotation,
  `ColorMomentHash` tolerates colour edits. Compare by Hamming distance.
- Nails re-exports, resized copies and static-scene bursts. Almost free when computed on the image the detector
  already downscaled to 1280 px.
- Weak for reframing and zoom, which is what a burst with a moving photographer does.

### Global image embedding (the modern answer)

- A small vision encoder run through OpenCV DNN, compared by cosine, stored in the same vector infrastructure the
  faces use (a vector column on the asset or a side table).
- **DINOv2 small** (ViT-S/14, 384 floats, ~85 MB ONNX) is best for "same scene, different framing": self-supervised
  and layout sensitive. DINOv3 is the 2025 successor.
- **CLIP** or **SigLIP 2** is better for semantic similarity and also gives text search and an aesthetic score for
  free (see **Learned quality** below). **MobileCLIP 2** is the small, fast option.
- Caveat to verify early: OpenCV's ONNX importer handles transformer layers (LayerNorm, GELU) since 4.8, but a specific
  export may hit an unsupported op. **ONNX Runtime for Java** (`com.microsoft.onnxruntime`) or the **Deep Java
  Library** are the fallback inference engines; neither is used here today.

### Geometric verification for doubtful pairs (`features2d`, ships)

- SIFT or AKAZE keypoints, a brute-force or FLANN matcher, then RANSAC via `findHomography`. The inlier ratio proves
  two frames see the same physical scene from a nearby viewpoint. This is what Lightroom-style auto-stacking and
  panorama tools do.
- Tens of milliseconds a pair at 1280 px, so it runs only where the layers above disagree.

### People as a signal

The Jaccard overlap of Person sets between two images is a strong tie-breaker, and the pipeline already has it.

### Working thresholds (to calibrate on real files)

| Signal | Burst | Same session |
|---|---|---|
| Capture gap | under 3 s | under 60 s |
| pHash Hamming | 10 or less | not reliable |
| DINOv2 cosine | 0.90 and up | 0.80 and up |
| Homography inliers | 30% and up | 15% and up |

### Other open-source references

digiKam's similarity search (Haar wavelet signatures), czkawka (pHash family), imagededup (Python, CNN plus hashes).
Useful for threshold ideas, not as dependencies.

## 2. Quality parameters

Compute these at import, in the stage that already holds the decoded `Mat`, and store them on the asset. Score
within a batch by z-scores rather than absolute thresholds: lighting shifts every absolute number.

### Technical, no model needed

- **Sharpness**: variance of the Laplacian (`Laplacian` then `meanStdDev`) is the classic; Tenengrad (Sobel gradient
  energy) is more robust to noise. Measure it on the subject region (the face boxes, or a saliency map from
  `ximgproc`), not the whole frame, otherwise a flat sky drags a sharp portrait down. Normalize for resolution.
- **Motion blur vs defocus**: gradient anisotropy separates them; exposure time times focal length from EXIF predicts
  shake.
- **Exposure**: luminance mean, clipped-highlight and crushed-shadow percentages, histogram entropy, dynamic range,
  EXIF exposure bias.
- **Noise**: Immerkær's estimate from the Laplacian is cheap; ISO from EXIF corroborates it.
- **Colour and contrast**: RMS contrast, Hasler and Süsstrunk colourfulness, white balance cast from the mean a and b
  channels in Lab.
- **Composition**: horizon tilt from Hough lines, subject placement against thirds, headroom, and faces cut by the
  frame edge, which the YuNet boxes tell directly.

### Learned no-reference quality

- **BRISQUE** ships in `opencv_quality`; it needs the two small model files from opencv_contrib
  (`brisque_model_live.yml`, `brisque_range_live.yml`).
- **NIMA** (aesthetic and technical heads, MobileNet backbone, small ONNX exports) gives a 1 to 10 aesthetic score.
- **LAION aesthetic predictor** is a linear head on a CLIP embedding: free once CLIP runs for grouping.
- **MUSIQ**, **TOPIQ**, **CLIP-IQA**, **ARNIQA** are the research-grade scorers (the `pyiqa` zoo). Heavier, and only
  worth it if the simple metrics prove insufficient.

### Face-specific

- Face sharpness on the crop, size relative to the frame, box fully inside the frame.
- **Head pose** by `solvePnP` on the five YuNet landmarks against a 3D template: yaw, pitch, roll.
- The **ArcFace norm** already stored as `Face.quality` is the MagFace idea: it tracks blur and occlusion.
- **Eyes open and smile** need a second model; five landmarks cannot see an eyelid. The best single addition is the
  **MediaPipe Face Landmarker** (478 landmarks plus blendshapes: blink left and right, smile, and pose in one pass;
  TFLite, with ONNX conversions available). Lighter alternatives: the OpenCV Zoo facial expression model (smile only),
  or a 106-landmark model (InsightFace 2d106, PIPNet) with the eye aspect ratio for blink.

## 3. Grading a batch on its faces

The key reuse: a burst is a Video with a handful of frames, and the Video path of the face pipeline already solves most
of this. Clustering the detections across the batch with `cluster` and `mergeClusters` gives each Person a cluster
with **support**, the number of frames they appear in, and the per-frame quality of each appearance. Within a burst
the identities are near certain, so batch-level voting can resolve a face that a single-frame match would drop.

### Per face in a frame

- ArcFace norm (`Face.quality`).
- Cosine distance to the Person's centroid over the batch: lowest on the most canonical, frontal, sharp look.
- YuNet detection score.
- Head pose, eye state, smile (with the models above).
- Box fully inside the frame.

### Per frame

- Aggregate so the **worst face dominates**: a group shot with one blink is a bad group shot. Use the minimum or a soft
  minimum (a generalized mean with a negative exponent), never the mean.
- **Coverage**: the expected cast is the union of Persons across the batch. A frame where someone dropped below the
  keep threshold lost them to a turn or a blink, and is penalized hard.
- Rank frames by the combined score and show the rationale as per-face chips in the Curate view, so the user sees why
  one frame beat another and can overrule it.

### Standards-based option

**OFIQ** (Open Source Face Image Quality, German BSI, 2024) implements ISO/IEC 29794-5 with a unified score and
component measures: eyes open, mouth closed, pose, illumination, sharpness, occlusion. It is a C++ library on ONNX
Runtime, so the checklist is worth borrowing; the dependency is not. Related research-grade face quality scorers:
SER-FIQ, CR-FIQA, SDD-FIQA.

## Suggested order of work

1. Metadata plus pHash grouping, the Laplacian and exposure metrics, and the batch face score on the Video clustering
   code. One import stage, no new model. This shows quickly whether a global embedding is needed.
2. A global embedding (DINOv2 small first) in a vector column planned from the start: it also opens similar-image
   search, text search with a CLIP-family model, and the free aesthetic score.
3. The MediaPipe landmarker for blink, smile and pose, if the face grading needs them.

## Open questions

- Is a batch a burst of seconds or a session of minutes? Session grouping needs the embedding; bursts mostly do not.
- Is the automatic batch computed at import and stored, or proposed on demand over the current triage selection?
- Is a second model of roughly 85 MB acceptable next to the ArcFace net?
- Does "Curate" become a glossary term in `CONTEXT.md`, and does picking the best shot move the rest to the trash or
  keep them as a stack under the pick?
