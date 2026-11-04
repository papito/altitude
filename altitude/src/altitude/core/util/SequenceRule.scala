package altitude.core.util

import java.time.Duration

import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.Settings

/**
 * The rule that puts two still images in one Sequence: frames of one scene, shot within a minute of each other on one camera,
 * looking alike by their Scene embeddings, or by their perceptual hashes where either lacks the chosen Scene model's embedding.
 * Pure over the models, so it is tested on its own; `SequenceService` reads the candidates, with their embeddings, and applies
 * it.
 */
object SequenceRule:

  /**
   * The capture-time rungs a camera's own clock sets. A time from a file name, a container or GPS cannot space the frames of a
   * burst, so an image with one is measured and hashed but never joins a Sequence.
   */
  val CameraClockSources: Set[CaptureDateSource] =
    Set(CaptureDateSource.ExifOriginal, CaptureDateSource.ExifDigitized, CaptureDateSource.XmpCreateDate)

  /**
   * Whether an asset can be in a Sequence at all: a still image in triage, with a capture time from the camera's clock, the
   * camera's model and a perceptual hash. The hash is the floor, not an embedding: an embedding exists only where the decode
   * succeeded, and so does the hash. This is the authority; `AssetDao.sequenceCandidatesStatement` prefilters the candidates in
   * SQL by a derived copy of it (all but the device model, which sits inside the metadata JSON), to spare reading full rows, so a
   * change here is made there too.
   */
  def isEligible(asset: Asset): Boolean =
    asset.isStillImage && asset.isTriaged && asset.originalCreatedAt.isDefined &&
      asset.originalCreatedAtSource.exists(CameraClockSources.contains) &&
      asset.perceptualHash.isDefined && asset.publicMetadata.deviceModel.isDefined

  /**
   * Whether a candidate is a frame of the asset's scene: both eligible, shot at most `sequence.max_gap_ms` apart on the same
   * camera at the same size, and [[lookAlike]] under the Settings' Scene model. The check is symmetric on purpose, so the rule
   * stands on its own: `SequenceService.assign` gates the asset's eligibility once before reading candidates, and pays for it
   * again here per candidate, which is a few field tests and, at most, one dot product.
   */
  def matches(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    isEligible(asset) && isEligible(candidate) &&
      gapMs(asset, candidate) <= settings.sequenceMaxGapMs &&
      asset.publicMetadata.deviceModel == candidate.publicMetadata.deviceModel &&
      asset.width == candidate.width && asset.height == candidate.height &&
      lookAlike(asset, candidate, settings)

  /**
   * Whether two eligible frames look alike. When both carry the embedding of `sequence.embedding_model`, their cosine similarity
   * is at least that model's threshold (`sequence.dinov2.min_similarity` or `sequence.clip.min_similarity`); otherwise, their
   * hashes are at most `sequence.max_hash_distance` bits apart. The similarity replaces the hash test rather than joining it: the
   * hash at any usable distance rejects a reframed or zoomed shot of the scene, which the embedding holds under. Each model has
   * its own threshold because their cosines live on different scales (CLIP's are compressed toward 1, so one number cannot serve
   * both). The size test in [[matches]] stays beside the embedding: a portrait and a landscape frame of one scene can embed
   * alike, and are not one Sequence.
   */
  private def lookAlike(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    val model = settings.sequenceEmbeddingModel
    (asset.sceneEmbeddings.of(model), candidate.sceneEmbeddings.of(model)) match
      case (Some(embedding), Some(other)) => embedding.similarity(other) >= settings.sequenceMinSimilarity(model)
      case _ => asset.perceptualHash.get.distance(candidate.perceptualHash.get) <= settings.sequenceMaxHashDistance

  /** How far apart two eligible assets were captured, in milliseconds, whichever came first */
  private def gapMs(asset: Asset, candidate: Asset): Long =
    Duration.between(asset.originalCreatedAt.get, candidate.originalCreatedAt.get).abs.toMillis
