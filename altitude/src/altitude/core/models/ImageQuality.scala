package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object ImageQuality:
  given JsonCodec.ReadWriter[ImageQuality] = JsonCodec.macroRW
  given Conversion[ujson.Value, ImageQuality] = json => JsonCodec.read[ImageQuality](json)

  /**
   * The Image quality read from the five `asset` columns, which are NULL together for a Video, an animated image, or a still
   * image the pipeline did not measure. Both DAO mappings build the model through this one rule.
   */
  def fromColumns(
      sharpness: Option[Double],
      luminance: Option[Double],
      clippedHighlights: Option[Double],
      crushedShadows: Option[Double],
      score: Option[Int]): Option[ImageQuality] =
    for
      sharpnessValue <- sharpness
      luminanceValue <- luminance
      clippedValue <- clippedHighlights
      crushedValue <- crushedShadows
      scoreValue <- score
    yield ImageQuality(
      sharpness = sharpnessValue,
      luminance = luminanceValue,
      clippedHighlights = clippedValue,
      crushedShadows = crushedValue,
      score = scoreValue
    )

/**
 * How technically good a still image is (the glossary's Image quality): the metrics measured at import and the 0 to 100 score
 * derived from them. It is about the whole image, where a Face's quality is about one face.
 *
 * @param sharpness
 *   variance of the Laplacian of the grayscale image fitted to the measurement box, over the face boxes when the image has faces
 *   covering at least `ImageMeasurement.MIN_FACE_AREA_PX` of it, else the whole frame
 * @param luminance
 *   mean gray level, 0 to 1
 * @param clippedHighlights
 *   fraction of pixels blown to white
 * @param crushedShadows
 *   fraction of pixels crushed to black
 * @param score
 *   0 to 100, from the sharpness and the exposure
 */
case class ImageQuality(sharpness: Double, luminance: Double, clippedHighlights: Double, crushedShadows: Double, score: Int)
