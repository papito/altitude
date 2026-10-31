package altitude.core.util

import java.time.Duration

import altitude.core.models.Asset
import altitude.core.models.CaptureDateSource
import altitude.core.models.Settings

/**
 * The rule that puts two still images in one Sequence: frames of one burst, shot moments apart on one camera, looking alike. Pure
 * over the models, so it is tested on its own; `SequenceService` reads the candidates and applies it.
 */
object SequenceRule:

  /**
   * The capture-time rungs a camera's own clock sets. A time from a file name, a container or GPS cannot space the frames of a
   * burst, so an image with one is measured and hashed but never joins a Sequence.
   */
  val CameraClockSources: Set[CaptureDateSource] =
    Set(CaptureDateSource.ExifOriginal, CaptureDateSource.ExifDigitized, CaptureDateSource.XmpCreateDate)

  /**
   * Whether an asset can be in a Sequence at all: a still image (a Video and an animated image have a duration) in triage, with a
   * capture time from the camera's clock, the camera's model and a perceptual hash. This is the authority;
   * `AssetDao.sequenceCandidatesStatement` prefilters the candidates in SQL by a derived copy of it (all but the device model,
   * which sits inside the metadata JSON), to spare reading full rows, so a change here is made there too.
   */
  def isEligible(asset: Asset): Boolean =
    asset.assetType.mediaType == "image" && asset.durationMs.isEmpty && asset.isTriaged &&
      asset.originalCreatedAt.isDefined && asset.originalCreatedAtSource.exists(CameraClockSources.contains) &&
      asset.perceptualHash.isDefined && asset.publicMetadata.deviceModel.isDefined

  /**
   * Whether a candidate is a frame of the asset's burst: both eligible, shot at most `sequence.max_gap_ms` apart on the same
   * camera at the same size, and their hashes at most `sequence.max_hash_distance` bits apart. The check is symmetric on purpose,
   * so the rule stands on its own: `SequenceService.assign` gates the asset's eligibility once before reading candidates, and
   * pays for it again here per candidate, which is a few field tests.
   */
  def matches(asset: Asset, candidate: Asset, settings: Settings): Boolean =
    isEligible(asset) && isEligible(candidate) &&
      gapMs(asset, candidate) <= settings.sequenceMaxGapMs &&
      asset.publicMetadata.deviceModel == candidate.publicMetadata.deviceModel &&
      asset.width == candidate.width && asset.height == candidate.height &&
      asset.perceptualHash.get.distance(candidate.perceptualHash.get) <= settings.sequenceMaxHashDistance

  /** How far apart two eligible assets were captured, in milliseconds, whichever came first */
  private def gapMs(asset: Asset, candidate: Asset): Long =
    Duration.between(asset.originalCreatedAt.get, candidate.originalCreatedAt.get).abs.toMillis
