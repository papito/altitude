package altitude.core.util

import java.time.Duration

import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.SceneModel
import altitude.core.models.Settings

/**
 * The rule that puts two still images in one Sequence: frames of one scene, shot close together by the camera's clock on the same
 * camera, at the same size, and alike by either Scene model, or by their hashes where no model embedded both. Images with no
 * capture time pair by size and likeness alone. Pure, so it is tested on its own.
 */
object SequenceRule:

  /**
   * The file name counts: a phone's app writes the camera's clock there to the second, and the gap test needs whole seconds only.
   * A container's creation time or a GPS timestamp cannot space the frames of a burst, so an image with one never joins a
   * Sequence.
   */
  val CameraClockSources: Set[CaptureDateSource] =
    Set(
      CaptureDateSource.ExifOriginal,
      CaptureDateSource.ExifDigitized,
      CaptureDateSource.XmpCreateDate,
      CaptureDateSource.FileName)

  /**
   * No camera model is needed: a frame saved out of a messaging app names none, and [[matches]] pairs it only with another that
   * names none.
   */
  def isDated(asset: Asset): Boolean =
    isFrame(asset) && asset.originalCreatedAt.isDefined && asset.originalCreatedAtSource.exists(CameraClockSources.contains)

  /**
   * No capture time at all. An image with a time from another clock than the camera's is neither kind of frame: it cannot be
   * spaced from a burst, and its time was not stripped.
   */
  def isUndated(asset: Asset): Boolean =
    isFrame(asset) && asset.originalCreatedAt.isEmpty

  /**
   * The authority: the candidates' SQL prefilters (`AssetDao.sequenceCandidatesStatement`, `undatedSequenceCandidatesStatement`)
   * are derived copies that spare reading full rows, so a change here is made there too.
   */
  def isEligible(asset: Asset): Boolean =
    isDated(asset) || isUndated(asset)

  /** The hash is the floor, not an embedding: an embedding exists only where the decode succeeded, and so does the hash */
  private def isFrame(asset: Asset): Boolean =
    asset.isStillImage && asset.isTriaged && asset.perceptualHash.isDefined

  /**
   * Symmetric on purpose, so the rule stands on its own, though the caller already gated the asset: the repeat costs a few field
   * tests per candidate.
   */
  def matches(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    shotTogether(asset, candidate, settings) &&
      asset.width == candidate.width && asset.height == candidate.height &&
      lookAlike(asset, candidate, settings)

  /**
   * Dated frames on the same camera model, or both naming none, so an original and its metadata-stripped copy are never a burst.
   * Undated frames always, their camera not compared: a frame stripped of its time is stripped of its camera too. A dated and an
   * undated frame never: a Sequence across the two would have no first capture to order it by.
   */
  private def shotTogether(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    if isDated(asset) && isDated(candidate) then
      gapMs(asset, candidate) <= settings.sequenceMaxGapMs &&
      asset.publicMetadata.deviceModel == candidate.publicMetadata.deviceModel
    else isUndated(asset) && isUndated(candidate)

  /**
   * One model's likeness is enough, so a burst one model misses the other catches. The hashes decide only where no model embedded
   * both: at any usable distance the hash rejects a reframed or zoomed shot, which the embedding holds under. Each model has its
   * own threshold, as SigLIP 2's cosines are compressed toward 1. The size test in [[matches]] stays: a portrait and a landscape
   * frame of one scene can embed alike.
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

  /** Never called on an undated frame */
  private def gapMs(asset: Asset, candidate: Asset): Long =
    Duration.between(asset.originalCreatedAt.get, candidate.originalCreatedAt.get).abs.toMillis
