package altitude.core.models

import scala.util.Try

import altitude.core.ValidationException
import altitude.core.util.JsonCodec

/** A crop rectangle as fractions of the frame (the turned and mirrored image): its left and top edges, its width and height */
final case class EditCrop(x: Double, y: Double, w: Double, h: Double)

object EditRecipe:
  val VERSION = 1

  // Before `Identity`, whose curve it is
  val IDENTITY_CURVE: List[(Double, Double)] = List((0.0, 0.0), (1.0, 1.0))

  /** The Edit that changes nothing, with the Crop tool's default settings: the original's, which Revert to original saves */
  val Identity: EditRecipe = EditRecipe()

  /** The Crop tool's aspect locks, as the Edit stores them; a ratio is written landscape, `portrait` turns it */
  val ASPECTS: List[String] = List("original", "free", "1:1", "5:4", "7:5", "3:2", "16:9")

  val MIN_CURVE_POINTS = 2
  val MAX_CURVE_POINTS = 16
  // The narrowest and shortest crop, as a fraction of the frame
  val MIN_CROP = 0.01

  private val ROTATIONS = Set(0, 90, 180, 270)
  // Steps per unit of a crop edge (4 decimals) and of a curve coordinate (3 decimals)
  private val CROP_STEPS = 10000
  private val CURVE_STEPS = 1000
  // The closest two curve points may be in x: one step of a curve coordinate
  val MIN_CURVE_SPACING = 0.001
  // What a difference of coordinates may stray below a step by in floating point
  private val EPSILON = 1e-9

  /** A slider of the Edit panel: its range, and how many steps it has per unit (1 for whole numbers, 20 for Exposure's 0.05) */
  private case class Slider(key: String, min: Double, max: Double, stepsPerUnit: Int = 1)

  private val SLIDERS: Map[String, Slider] = List(
    Slider("straighten", -45, 45, stepsPerUnit = 10),
    Slider("perspectiveV", -100, 100),
    Slider("perspectiveH", -100, 100),
    Slider("exposure", -5, 5, stepsPerUnit = 20),
    Slider("contrast", -100, 100),
    Slider("highlights", -100, 100),
    Slider("shadows", -100, 100),
    Slider("whites", -100, 100),
    Slider("blacks", -100, 100),
    Slider("gamma", -100, 100),
    Slider("temperature", -100, 100),
    Slider("tint", -100, 100),
    Slider("vibrance", -100, 100),
    Slider("saturation", -100, 100),
    Slider("sharpen", 0, 150),
    Slider("clarity", -100, 100),
    Slider("vignette", -100, 100),
    Slider("grain", 0, 100)
  ).map(slider => slider.key -> slider).toMap

  // Every key of a stored Edit, in the order its JSON is written
  private val KEYS: List[String] = "version" :: Identity.productElementNames.toList

  private val CROP_KEYS = List("x", "y", "w", "h")

  given JsonCodec.ReadWriter[EditRecipe] = JsonCodec.readwriter[ujson.Value].bimap(_.toJson, json => parse(json))

  /** An Edit stored as JSON text ([[parse]] of the text) */
  def parse(text: String): EditRecipe =
    val json = Try(ujson.read(text)).getOrElse(invalid("The Edit is not JSON"))
    parse(json)

  /**
   * The Edit a client sent, normalized: every number rounded to its step. Refused with a `ValidationException` saying why: a
   * `version` other than 1, an unknown or missing key (no default is filled in), a value of the wrong type or outside its range,
   * a crop outside the frame or narrower or shorter than [[MIN_CROP]], and a curve of fewer than 2 or more than 16 points, or
   * whose x does not increase by at least [[MIN_CURVE_SPACING]] from point to point, or with a point outside [0, 1]. A crop's
   * left and top edges and its right and bottom edges (x + w, y + h) are rounded to 4 decimals, its width and height being the
   * differences. The ranges are checked on the rounded values. Whether a crop stays inside the image's outline is the client's
   * concern: the renderer draws what lies outside as transparent.
   */
  def parse(json: ujson.Value): EditRecipe =
    val obj = json.objOpt.getOrElse(invalid("The Edit is not a JSON object"))
    val unknown = obj.keys.toList.filterNot(KEYS.contains)
    if unknown.nonEmpty then invalid(s"Unknown keys in the Edit: ${unknown.mkString(", ")}")
    val missing = KEYS.filterNot(obj.contains)
    if missing.nonEmpty then invalid(s"Missing keys in the Edit: ${missing.mkString(", ")}")

    if !obj("version").numOpt.contains(VERSION.toDouble) then invalid(s"The Edit's version must be $VERSION")

    def slider(key: String): Double =
      val spec = SLIDERS(key)
      val value = rounded(number(obj, key), spec.stepsPerUnit)
      if value < spec.min || value > spec.max then invalid(s"$key must be between ${spec.min.toInt} and ${spec.max.toInt}")
      value
    def whole(key: String): Int = slider(key).toInt

    val rotate = number(obj, "rotate")
    if !ROTATIONS.exists(_.toDouble == rotate) then invalid("rotate must be 0, 90, 180 or 270")

    val aspect =
      obj("aspect").strOpt.filter(ASPECTS.contains).getOrElse(invalid(s"aspect must be one of ${ASPECTS.mkString(", ")}"))
    val portrait = obj("portrait") match
      case ujson.Null => None
      case ujson.Bool(value) => Some(value)
      case _ => invalid("portrait must be true, false or null")

    EditRecipe(
      rotate = rotate.toInt,
      flipH = bool(obj, "flipH"),
      flipV = bool(obj, "flipV"),
      straighten = slider("straighten"),
      perspectiveV = whole("perspectiveV"),
      perspectiveH = whole("perspectiveH"),
      crop = cropOf(obj("crop")),
      aspect = aspect,
      portrait = portrait,
      keepInside = bool(obj, "keepInside"),
      cropFitted = bool(obj, "cropFitted"),
      exposure = slider("exposure"),
      contrast = whole("contrast"),
      highlights = whole("highlights"),
      shadows = whole("shadows"),
      whites = whole("whites"),
      blacks = whole("blacks"),
      gamma = whole("gamma"),
      curve = curveOf(obj("curve")),
      temperature = whole("temperature"),
      tint = whole("tint"),
      vibrance = whole("vibrance"),
      saturation = whole("saturation"),
      sharpen = whole("sharpen"),
      clarity = whole("clarity"),
      vignette = whole("vignette"),
      grain = whole("grain")
    )

  private def invalid(why: String): Nothing = throw ValidationException(why)

  private def number(obj: collection.Map[String, ujson.Value], key: String, name: Option[String] = None): Double =
    obj(key).numOpt.filterNot(_.isNaN).getOrElse(invalid(s"${name.getOrElse(key)} must be a number"))

  private def bool(obj: collection.Map[String, ujson.Value], key: String): Boolean =
    obj(key).boolOpt.getOrElse(invalid(s"$key must be true or false"))

  // The nearest step; Math.round, as the browser's, takes a half up
  private def rounded(value: Double, stepsPerUnit: Int): Double = Math.round(value * stepsPerUnit).toDouble / stepsPerUnit

  private def cropOf(json: ujson.Value): Option[EditCrop] = json match
    case ujson.Null => None
    case ujson.Obj(fields) =>
      if fields.keySet != CROP_KEYS.toSet then invalid("crop must be null or {x, y, w, h}")
      val raw = CROP_KEYS.map(key => key -> number(fields, key, Some(s"crop.$key"))).toMap
      // The edges are rounded, and the width and height are what lies between them, so a crop that reaches the frame's edge
      // keeps reaching it exactly, however its left or top rounds
      val (x, y) = (rounded(raw("x"), CROP_STEPS), rounded(raw("y"), CROP_STEPS))
      val (right, bottom) = (rounded(raw("x") + raw("w"), CROP_STEPS), rounded(raw("y") + raw("h"), CROP_STEPS))
      val (w, h) = (rounded(right - x, CROP_STEPS), rounded(bottom - y, CROP_STEPS))
      if List(x, y, right, bottom).exists(v => v < 0 || v > 1) then invalid("crop must lie within the frame")
      if w < MIN_CROP || h < MIN_CROP then invalid(s"crop must be at least $MIN_CROP of the frame each way")
      Some(EditCrop(x, y, w, h))
    case _ => invalid("crop must be null or {x, y, w, h}")

  private def curveOf(json: ujson.Value): List[(Double, Double)] =
    val raw = json.arrOpt.getOrElse(invalid("curve must be an array of [x, y] points")).toList.map {
      point =>
        point.arrOpt.map(_.toList.map(_.numOpt)) match
          case Some(List(Some(x), Some(y))) => (x, y)
          case _ => invalid("curve must be an array of [x, y] points")
    }
    if raw.size < MIN_CURVE_POINTS || raw.size > MAX_CURVE_POINTS then
      invalid(s"curve must have $MIN_CURVE_POINTS to $MAX_CURVE_POINTS points")
    // Spaced as sent, so that rounding never puts two points on one x: points at least a step apart round to distinct steps
    if raw.zip(raw.tail).exists((a, b) => b._1 - a._1 < MIN_CURVE_SPACING - EPSILON) then
      invalid(s"curve points must increase in x, each at least $MIN_CURVE_SPACING after the one before")
    val points = raw.map((x, y) => (rounded(x, CURVE_STEPS), rounded(y, CURVE_STEPS)))
    if points.exists((x, y) => x < 0 || x > 1 || y < 0 || y > 1) then invalid("curve points must lie in [0, 1]")
    points

/**
 * An asset's Edit (`CONTEXT.md`): every control's value, as `asset_edit.recipe` stores it (`version` 1, every key present) and
 * the client sends it. Built by [[EditRecipe.parse]], which normalizes and checks every value, or as the default with changes.
 * Its geometry is in the upright original: the original turned by its EXIF orientation.
 */
final case class EditRecipe(
    rotate: Int = 0,
    flipH: Boolean = false,
    flipV: Boolean = false,
    straighten: Double = 0,
    perspectiveV: Int = 0,
    perspectiveH: Int = 0,
    crop: Option[EditCrop] = None,
    aspect: String = "original",
    portrait: Option[Boolean] = None,
    keepInside: Boolean = true,
    cropFitted: Boolean = true,
    exposure: Double = 0,
    contrast: Int = 0,
    highlights: Int = 0,
    shadows: Int = 0,
    whites: Int = 0,
    blacks: Int = 0,
    gamma: Int = 0,
    curve: List[(Double, Double)] = EditRecipe.IDENTITY_CURVE,
    temperature: Int = 0,
    tint: Int = 0,
    vibrance: Int = 0,
    saturation: Int = 0,
    sharpen: Int = 0,
    clarity: Int = 0,
    vignette: Int = 0,
    grain: Int = 0):

  /**
   * Whether the Edit changes no pixel: no turn, flip, Straighten, perspective or crop, every slider at 0 and the curve the
   * diagonal. The Crop tool's settings (the aspect, its orientation, keeping the crop inside and whether it was fitted) do not
   * count. An asset is edited when its Edit is not the identity.
   */
  def isIdentity: Boolean =
    copy(aspect = "original", portrait = None, keepInside = true, cropFitted = true) == EditRecipe.Identity

  /**
   * The size of the edited image, from the upright original's: the frame (swapped for a quarter turn) and then the crop's share
   * of it, rounded to whole pixels and never less than one
   */
  def editedSize(uprightWidth: Int, uprightHeight: Int): (Int, Int) =
    val (frameWidth, frameHeight) =
      if rotate == 90 || rotate == 270 then (uprightHeight, uprightWidth) else (uprightWidth, uprightHeight)
    crop.fold((frameWidth, frameHeight)) {
      c => (Math.max(1, Math.round(c.w * frameWidth).toInt), Math.max(1, Math.round(c.h * frameHeight).toInt))
    }

  /** The Edit as stored and sent: every key, `version` first */
  def toJson: ujson.Obj = ujson.Obj(
    "version" -> EditRecipe.VERSION,
    "rotate" -> rotate,
    "flipH" -> flipH,
    "flipV" -> flipV,
    "straighten" -> straighten,
    "perspectiveV" -> perspectiveV,
    "perspectiveH" -> perspectiveH,
    "crop" -> crop.fold(ujson.Null: ujson.Value)(c => ujson.Obj("x" -> c.x, "y" -> c.y, "w" -> c.w, "h" -> c.h)),
    "aspect" -> aspect,
    "portrait" -> portrait.fold(ujson.Null: ujson.Value)(ujson.Bool(_)),
    "keepInside" -> keepInside,
    "cropFitted" -> cropFitted,
    "exposure" -> exposure,
    "contrast" -> contrast,
    "highlights" -> highlights,
    "shadows" -> shadows,
    "whites" -> whites,
    "blacks" -> blacks,
    "gamma" -> gamma,
    "curve" -> ujson.Arr.from(curve.map((x, y) => ujson.Arr(x, y))),
    "temperature" -> temperature,
    "tint" -> tint,
    "vibrance" -> vibrance,
    "saturation" -> saturation,
    "sharpen" -> sharpen,
    "clarity" -> clarity,
    "vignette" -> vignette,
    "grain" -> grain
  )
