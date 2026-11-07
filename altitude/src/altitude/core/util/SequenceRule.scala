package altitude.core.util

import java.time.Duration

import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.SceneModel
import altitude.core.models.Settings

/**
 * The rule that puts two still images in one Sequence: frames of one scene, shot within a minute of each other by the camera's
 * clock or, failing that, by the time in the file name, on the same camera or both naming none, looking alike by their Scene
 * embeddings under either Scene model, or by their perceptual hashes where no model embedded both. Pure over the models, so it is
 * tested on its own; `SequenceService` reads the candidates, with their embeddings, and applies it.
 */
object SequenceRule:

  /**
   * The capture-time rungs that carry the camera's own clock: the EXIF and XMP tags, and the file name, where a phone's app wrote
   * that clock to the second. The gap test needs whole seconds, which a name gives; the fraction of a second a camera writes only
   * orders the frames inside one second, and frames with none order by ID, as `CurationRule` orders them. A container's creation
   * time or a GPS timestamp is no camera clock and cannot space the frames of a burst, so an image with one is measured and
   * hashed but never joins a Sequence.
   */
  val CameraClockSources: Set[CaptureDateSource] =
    Set(
      CaptureDateSource.ExifOriginal,
      CaptureDateSource.ExifDigitized,
      CaptureDateSource.XmpCreateDate,
      CaptureDateSource.FileName)

  /**
   * Whether an asset can be in a Sequence at all: a still image in triage, with a capture time from one of the
   * [[CameraClockSources]] and a perceptual hash. No camera model is needed: a frame saved out of a messaging app names none, and
   * [[matches]] pairs it only with another that names none. The hash is the floor, not an embedding: an embedding exists only
   * where the decode succeeded, and so does the hash. This is the authority; `AssetDao.sequenceCandidatesStatement` prefilters
   * the candidates in SQL by a derived copy of it, to spare reading full rows, so a change here is made there too.
   */
  def isEligible(asset: Asset): Boolean =
    asset.isStillImage && asset.isTriaged && asset.originalCreatedAt.isDefined &&
      asset.originalCreatedAtSource.exists(CameraClockSources.contains) && asset.perceptualHash.isDefined

  /**
   * Whether a candidate is a frame of the asset's scene: both eligible, shot at most `sequence.max_gap_ms` apart on the same
   * camera (both name the same model, or neither names one, so an original and its metadata-stripped copy are never a burst) at
   * the same size, and [[lookAlike]] by either Scene model. The check is symmetric on purpose, so the rule stands on its own:
   * `SequenceService.assign` gates the asset's eligibility once before reading candidates, and pays for it again here per
   * candidate, which is a few field tests and, at most, one dot product per Scene model.
   */
  def matches(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    isEligible(asset) && isEligible(candidate) &&
      gapMs(asset, candidate) <= settings.sequenceMaxGapMs &&
      asset.publicMetadata.deviceModel == candidate.publicMetadata.deviceModel &&
      asset.width == candidate.width && asset.height == candidate.height &&
      lookAlike(asset, candidate, settings)

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

  /** How far apart two eligible assets were captured, in milliseconds, whichever came first */
  private def gapMs(asset: Asset, candidate: Asset): Long =
    Duration.between(asset.originalCreatedAt.get, candidate.originalCreatedAt.get).abs.toMillis
