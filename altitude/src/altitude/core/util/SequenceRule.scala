package altitude.core.util

import java.time.Duration

import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.SceneModel
import altitude.core.models.Settings

/**
 * The rule that puts two still images in one Sequence: frames of one scene, shot within a minute of each other by the camera's
 * clock or, failing that, by the time in the file name, on the same camera or both naming none, looking alike by their Scene
 * embeddings under either Scene model, or by their perceptual hashes where no model embedded both; or, for images with no capture
 * time, looking alike among the recently imported images of their size. Pure over the models, so it is tested on its own;
 * `SequenceService` reads the candidates, with their embeddings, and applies it.
 */
object SequenceRule:

  /**
   * The capture-time rungs that carry the camera's own clock: the EXIF and XMP tags, and the file name, where a phone's app wrote
   * that clock to the second. The gap test needs whole seconds, which a name gives; the fraction of a second a camera writes only
   * orders the frames inside one second, and frames with none order by import time and then ID, as `CurationRule` orders them. A
   * container's creation time or a GPS timestamp is no camera clock and cannot space the frames of a burst, so an image with one
   * is measured and hashed but never joins a Sequence.
   */
  val CameraClockSources: Set[CaptureDateSource] =
    Set(
      CaptureDateSource.ExifOriginal,
      CaptureDateSource.ExifDigitized,
      CaptureDateSource.XmpCreateDate,
      CaptureDateSource.FileName)

  /**
   * Whether an asset is a dated frame: a still image in triage, with a capture time from one of the [[CameraClockSources]] and a
   * perceptual hash. No camera model is needed: a frame saved out of a messaging app names none, and [[matches]] pairs it only
   * with another that names none.
   */
  def isDated(asset: Asset): Boolean =
    isFrame(asset) && asset.originalCreatedAt.isDefined && asset.originalCreatedAtSource.exists(CameraClockSources.contains)

  /**
   * Whether an asset is an undated frame: a still image in triage with a perceptual hash and no capture time at all. A time from
   * a clock that is not the camera's (a container's creation time, a GPS timestamp) is a time, so an image with one is neither
   * kind of frame: it cannot be spaced from a burst, and it is not among the images whose time was stripped.
   */
  def isUndated(asset: Asset): Boolean =
    isFrame(asset) && asset.originalCreatedAt.isEmpty

  /**
   * Whether an asset can be in a Sequence at all: a [[isDated dated]] or an [[isUndated undated]] frame. The two kinds are read
   * and paired apart (see [[matches]]), and `SequenceService.assign` tells them apart again to choose the read. This is the
   * authority; the SQL prefilters of the candidates, `AssetDao.sequenceCandidatesStatement` for the dated frames and
   * `undatedSequenceCandidatesStatement` for the undated ones, are derived copies of the kind they read, there to spare reading
   * full rows, so a change here is made there too.
   */
  def isEligible(asset: Asset): Boolean =
    isDated(asset) || isUndated(asset)

  /**
   * What both kinds of frame need: a still image in triage with a perceptual hash. The hash is the floor, not an embedding: an
   * embedding exists only where the decode succeeded, and so does the hash.
   */
  private def isFrame(asset: Asset): Boolean =
    asset.isStillImage && asset.isTriaged && asset.perceptualHash.isDefined

  /**
   * Whether a candidate is a frame of the asset's scene: [[shotTogether]], at the same size, and [[lookAlike]] by either Scene
   * model. The check is symmetric on purpose, so the rule stands on its own: `SequenceService.assign` gates the asset's
   * eligibility once before reading candidates, and pays for it again here per candidate, which is a few field tests and, at
   * most, one dot product per Scene model.
   */
  def matches(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    shotTogether(asset, candidate, settings) &&
      asset.width == candidate.width && asset.height == candidate.height &&
      lookAlike(asset, candidate, settings)

  /**
   * Whether two frames could have been shot as one scene, before their likeness is compared: two dated frames when they were shot
   * at most `sequence.max_gap_ms` apart on the same camera (both name the same model, or neither names one, so an original and
   * its metadata-stripped copy are never a burst); two undated frames always, since nothing but their likeness and their size
   * tells them apart (their camera is not compared: a frame stripped of its time is stripped of its camera too). A dated frame
   * and an undated one never, whatever they look like: the undated frames are read by import order, the dated by a window of
   * capture time, and a Sequence across the two kinds would have no first capture to order it by. The gap is measured only here,
   * where both frames are known to have a time.
   */
  private def shotTogether(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    if isDated(asset) && isDated(candidate) then
      gapMs(asset, candidate) <= settings.sequenceMaxGapMs &&
      asset.publicMetadata.deviceModel == candidate.publicMetadata.deviceModel
    else isUndated(asset) && isUndated(candidate)

  /**
   * Whether two eligible frames look alike: by some Scene model whose embedding both carry, their cosine similarity is at least
   * that model's threshold (`sequence.dinov2.min_similarity` or `sequence.clip.min_similarity`); where no model embedded both,
   * their hashes are at most `sequence.max_hash_distance` bits apart. One model's likeness is enough, so the Sequences are the
   * union of what either model finds and a burst one model misses is caught by the other; a pair compared by any model is never
   * rescued by its hashes. The similarity replaces the hash test rather than joining it: the hash at any usable distance rejects
   * a reframed or zoomed shot of the scene, which the embedding holds under. Each model has its own threshold because their
   * cosines live on different scales (CLIP's are compressed toward 1, so one number cannot serve both). The size test in
   * [[matches]] stays beside the embedding: a portrait and a landscape frame of one scene can embed alike, and are not one
   * Sequence.
   */
  private def lookAlike(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    val similarities = SceneModel.values.toList.flatMap {
      model =>
        for
          embedding <- asset.sceneEmbeddings.of(model)
          other <- candidate.sceneEmbeddings.of(model)
        yield model -> embedding.similarity(other)
    }
    if similarities.isEmpty then
      asset.perceptualHash.get.distance(candidate.perceptualHash.get) <= settings.sequenceMaxHashDistance
    else similarities.exists((model, similarity) => similarity >= settings.sequenceMinSimilarity(model))

  /** How far apart two dated frames were captured, in milliseconds, whichever came first; never called on an undated frame */
  private def gapMs(asset: Asset, candidate: Asset): Long =
    Duration.between(asset.originalCreatedAt.get, candidate.originalCreatedAt.get).abs.toMillis
