package altitude.core.models

import java.util.Locale

import scala.util.Try

import altitude.core.ValidationException
import altitude.core.util.JsonCodec

/** A crop rectangle as fractions of the frame (the turned and mirrored image): its left and top edges, its width and height */
final case class EditCrop(x: Double, y: Double, w: Double, h: Double)

/** The sections of the Edit panel, in its order, each holding the keys of the Edit it sets */
enum EditSection(val label: String, val keys: Set[String]):
  case CropRotate
    extends EditSection(
      "Crop & rotate",
      Set(
        "rotate",
        "flipH",
        "flipV",
        "straighten",
        "perspectiveV",
        "perspectiveH",
        "crop",
        "aspect",
        "portrait",
        "keepInside",
        "cropFitted"))
  case Light
    extends EditSection("Light", Set("exposure", "contrast", "highlights", "shadows", "whites", "blacks", "gamma", "curve"))
  case Color extends EditSection("Color", Set("temperature", "tint", "vibrance", "saturation"))
  case Effects extends EditSection("Effects", Set("sharpen", "clarity", "vignette", "grain"))

object EditRecipe:
  val VERSION = 1

  // Before `Identity`, whose curve it is
  val IDENTITY_CURVE: List[(Double, Double)] = List((0.0, 0.0), (1.0, 1.0))

  /** The Edit that changes nothing, with the Crop tool's default settings: the Original */
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

  /**
   * A slider of the Edit panel: its range, how many steps it has per unit (1 for whole numbers, 20 for Exposure's 0.05), how many
   * decimals its value is written with, whether it has two sides (a sign) and what follows its value
   */
  private case class Slider(
      key: String,
      label: String,
      min: Double,
      max: Double,
      stepsPerUnit: Int = 1,
      decimals: Int = 0,
      suffix: String = ""):
    val signed: Boolean = min < 0

    /** The slider's value written as a step's label writes it: "+0.40", "−12", "40", "+2.3°" */
    def format(value: Double): String =
      val digits = s"%.${decimals}f".formatLocal(Locale.ROOT, Math.abs(value))
      val sign = if !signed || value == 0 then "" else if value > 0 then "+" else "−"
      s"$sign$digits$suffix"

  private val SLIDERS: Map[String, Slider] = List(
    Slider("straighten", "Straighten", -45, 45, stepsPerUnit = 10, decimals = 1, suffix = "°"),
    Slider("perspectiveV", "Vertical perspective", -100, 100),
    Slider("perspectiveH", "Horizontal perspective", -100, 100),
    Slider("exposure", "Exposure", -5, 5, stepsPerUnit = 20, decimals = 2),
    Slider("contrast", "Contrast", -100, 100),
    Slider("highlights", "Highlights", -100, 100),
    Slider("shadows", "Shadows", -100, 100),
    Slider("whites", "Whites", -100, 100),
    Slider("blacks", "Blacks", -100, 100),
    Slider("gamma", "Gamma", -100, 100),
    Slider("temperature", "Temperature", -100, 100),
    Slider("tint", "Tint", -100, 100),
    Slider("vibrance", "Vibrance", -100, 100),
    Slider("saturation", "Saturation", -100, 100),
    Slider("sharpen", "Sharpen", 0, 150),
    Slider("clarity", "Clarity", -100, 100),
    Slider("vignette", "Vignette", -100, 100),
    Slider("grain", "Grain", 0, 100)
  ).map(slider => slider.key -> slider).toMap

  // Every key of a stored Edit, in the order its JSON is written
  private val KEYS: List[String] = "version" :: Identity.productElementNames.toList

  private val CROP_KEYS = List("x", "y", "w", "h")

  // The keys of the crop itself: its rectangle and the Crop tool's lock, which a crop session changes together
  private val CROP_SETTINGS = Set("crop", "aspect", "portrait", "cropFitted")

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
   * The label of the step from `before` to `after`, which every client shows: the server derives it so that all say the same
   * words. One slider changed is its name and value ("Exposure +0.40"); an Edit back to one that changes no pixel is "Revert to
   * original"; changes in several sections name them; a section whose changes all go back to its defaults is "Reset" and its
   * name, and other changes in one section name it, Crop & rotate's being named by what it turned, flipped or cropped.
   */
  def describeChange(before: EditRecipe, after: EditRecipe): String =
    require(before != after, "A step changes the Edit")
    val differing = before.productElementNames
      .zip(before.productIterator.zip(after.productIterator))
      .collect { case (key, (was, is)) if was != is => key }
      .toList
    // A crop the Crop tool fits re-fits, at the same aspect, to every change of Straighten, a perspective or keeping it inside:
    // the step is that change, not the crop's
    val refit = before.cropFitted && after.cropFitted && before.aspect == after.aspect && before.portrait == after.portrait
    val changed = if refit && differing.size > 1 then differing.filterNot(_ == "crop") else differing
    val sections = EditSection.values.toList.filter(section => changed.exists(section.keys.contains))

    // Back to the default Edit, or to one that changes no pixel from one that did; an aspect picked with no crop is no revert
    if after == Identity || (after.isIdentity && !before.isIdentity) then "Revert to original"
    else if sections.size > 1 then
      val labels = sections.map(_.label)
      s"${labels.init.mkString(", ")} and ${labels.last}"
    else
      val section = sections.head
      val atDefaults = section.keys.forall(key => after.valueOf(key) == Identity.valueOf(key))
      section match
        case EditSection.CropRotate if changed.size > 1 && atDefaults => s"Reset ${section.label}"
        case EditSection.CropRotate => geometryLabel(changed.toSet, before, after)
        case _ if changed.size == 1 => keyLabel(changed.head, after)
        case _ if atDefaults => s"Reset ${section.label}"
        case _ => section.label

  /** One key's change, by its new value: a slider's name and value, or the tone curve */
  private def keyLabel(key: String, after: EditRecipe): String =
    SLIDERS.get(key) match
      case Some(slider) => s"${slider.label} ${slider.format(after.valueOf(key).asInstanceOf[Number].doubleValue)}"
      case None => "Tone curve"

  /**
   * A change of Crop & rotate: a turn or a flip, which carries the crop along, is named as such, a turn by the way the picture
   * turns as shown; then the crop, with the lock's ratio as its chip reads it; then a lone slider; then the section
   */
  private def geometryLabel(changed: Set[String], before: EditRecipe, after: EditRecipe): String =
    if changed.contains("rotate") then
      // A mirror on one axis is applied after the turn and reverses its sense as shown; both mirrors make a half turn
      val turn = (after.rotate - before.rotate + 360) % 360
      val shown = if after.flipH != after.flipV then (360 - turn) % 360 else turn
      shown match
        case 90 => "Rotate right"
        case 270 => "Rotate left"
        case _ => "Rotate 180°"
    else if changed.contains("flipH") && changed.contains("flipV") then "Flip"
    else if changed.contains("flipH") then "Flip horizontal"
    else if changed.contains("flipV") then "Flip vertical"
    else if changed.exists(CROP_SETTINGS.contains) then
      val ratio = after.aspect.split(':') match
        case Array(long, short) => Some(if after.portrait.contains(true) then s"$short:$long" else s"$long:$short")
        case _ => None
      ratio.fold("Crop")(r => s"Crop $r")
    else if changed == Set("keepInside") then if after.keepInside then "Keep the crop inside" else "Let the crop reach outside"
    else if changed.size == 1 then keyLabel(changed.head, after)
    else EditSection.CropRotate.label

/**
 * An asset's Edit at one step (`CONTEXT.md`): every control's value, as `asset_edit_step.recipe` stores it (`version` 1, every
 * key present) and the client sends it. Built by [[EditRecipe.parse]], which normalizes and checks every value, or as the default
 * with changes. Its geometry is in the upright original: the original turned by its EXIF orientation.
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
   * count. An asset is edited when its head step is not the identity.
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

  /** The value of a key, as [[toJson]] names it */
  private[models] def valueOf(key: String): Any = productElement(productElementNames.indexOf(key))

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
