package altitude.core.util

import com.drew.imaging.ImageProcessingException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

import scala.util.control.NonFatal

import altitude.core.ImageException
import altitude.core.models.EditCrop
import altitude.core.models.EditRecipe

/**
 * Renders an Edit (`CONTEXT.md`) from the original with OpenCV, as the plan's spec of the geometry and of the per-pixel math
 * says; the Edit panel's WebGL renderer draws the same math (`static/js/edit/`), and both are checked against the shared vectors
 * (`test/resources/edit/vectors.json`). The geometry and the math are public functions of their own, so the tests and the vectors
 * read them one by one.
 *
 * Coordinates are continuous pixel coordinates, the top-left corner of the image at (0, 0) and y down, so a pixel's center is at
 * +0.5. An angle is positive clockwise.
 */
object EditRenderer:

  /** Entries of each channel's table of the tone steps (1 to 6), over the input value 0 to 1 */
  val TABLE_SIZE = 4096

  /** The output long edge at which the sizes of Sharpen's blur and of Grain's cells are given; they scale with the output */
  val REFERENCE_LONG_EDGE = 3840.0

  // The keystone of a perspective slider at ±100
  private val PERSPECTIVE_AT_FULL = 0.55

  /** The inverse warp has no source where its denominator is this or less: beyond the horizon of the perspective's plane */
  val MIN_DENOMINATOR = 0.001

  // The long edge auto-straighten reads the frame at, and the share of it a line must be as long as to count
  private val STRAIGHTEN_LONG_EDGE = 1280
  private val STRAIGHTEN_MIN_LINE = 0.08
  private val STRAIGHTEN_MIN_LINES = 3

  // ---------------------------------------------------------------------------------------------------------------- Geometry

  /** The frame's size, W × H: the upright original's, swapped for a quarter turn */
  def frameSize(uprightWidth: Int, uprightHeight: Int, recipe: EditRecipe): (Int, Int) =
    if recipe.rotate == 90 || recipe.rotate == 270 then (uprightHeight, uprightWidth) else (uprightWidth, uprightHeight)

  /** Step 1 for a point of the upright original: turned clockwise by `rotate`, then mirrored in the turned frame */
  def turn(x: Double, y: Double, uprightWidth: Int, uprightHeight: Int, recipe: EditRecipe): (Double, Double) =
    val (tx, ty) = recipe.rotate match
      case 90 => (uprightHeight - y, x)
      case 180 => (uprightWidth - x, uprightHeight - y)
      case 270 => (y, uprightWidth - x)
      case _ => (x, y)
    val (width, height) = frameSize(uprightWidth, uprightHeight, recipe)
    (if recipe.flipH then width - tx else tx, if recipe.flipV then height - ty else ty)

  /** The keystones (a, b) of the horizontal and vertical perspective */
  private def keystones(recipe: EditRecipe): (Double, Double) =
    (PERSPECTIVE_AT_FULL * recipe.perspectiveH / 100, PERSPECTIVE_AT_FULL * recipe.perspectiveV / 100)

  /**
   * Step 2 on the W × H frame, its constants worked out once: in normalized coordinates around the center (q = (p − c) / s, s
   * half the long side), the image point u goes to u / (1 + a·uₓ + b·u_y), which is turned by Straighten and scaled back
   */
  final class Warp(width: Double, height: Double, recipe: EditRecipe):
    private val s = Math.max(width, height) / 2
    private val (a, b) = keystones(recipe)
    private val theta = Math.toRadians(recipe.straighten)
    private val (cos, sin) = (Math.cos(theta), Math.sin(theta))

    /** Whether the warp moves any point: Straighten or a perspective */
    val moves: Boolean = recipe.straighten != 0 || recipe.perspectiveH != 0 || recipe.perspectiveV != 0

    def forward(x: Double, y: Double): (Double, Double) =
      val (ux, uy) = ((x - width / 2) / s, (y - height / 2) / s)
      val d = 1 + a * ux + b * uy
      val (px, py) = (ux / d, uy / d)
      ((cos * px - sin * py) * s + width / 2, (sin * px + cos * py) * s + height / 2)

    /** The denominator w of [[inverse]] at the point */
    private def denominator(qx: Double, qy: Double): Double = 1 - a * (cos * qx + sin * qy) - b * (-sin * qx + cos * qy)

    def inverse(x: Double, y: Double): Option[(Double, Double)] =
      val (qx, qy) = ((x - width / 2) / s, (y - height / 2) / s)
      val w = denominator(qx, qy)
      Option.when(w > MIN_DENOMINATOR)(((cos * qx + sin * qy) / w * s + width / 2, (-sin * qx + cos * qy) / w * s + height / 2))

    /**
     * Whether the point of the warped frame shows the image: its source ([[inverse]]) exists and lies within the frame, edges
     * included. The rendered image is opaque exactly there, with no partial coverage at the edge.
     */
    def covers(x: Double, y: Double): Boolean =
      val (qx, qy) = ((x - width / 2) / s, (y - height / 2) / s)
      val w = denominator(qx, qy)
      w > MIN_DENOMINATOR && {
        val px = (cos * qx + sin * qy) / w * s + width / 2
        val py = (-sin * qx + cos * qy) / w * s + height / 2
        px >= 0 && px <= width && py >= 0 && py <= height
      }

  /** Step 2 for a point of the W × H frame ([[Warp.forward]]) */
  def warp(x: Double, y: Double, width: Double, height: Double, recipe: EditRecipe): (Double, Double) =
    Warp(width, height, recipe).forward(x, y)

  /**
   * The inverse of [[warp]], which sampling reads: u = R(−θ)·q, w = 1 − a·uₓ − b·u_y, and the source is u / w; None where w is
   * [[MIN_DENOMINATOR]] or less
   */
  def unwarp(x: Double, y: Double, width: Double, height: Double, recipe: EditRecipe): Option[(Double, Double)] =
    Warp(width, height, recipe).inverse(x, y)

  /**
   * The image's outline in the frame: the four corners of the turned image (top left, top right, bottom right, bottom left)
   * through [[warp]], as fractions of the frame's width and height
   */
  def outline(width: Double, height: Double, recipe: EditRecipe): List[(Double, Double)] =
    List((0.0, 0.0), (width, 0.0), (width, height), (0.0, height)).map {
      (x, y) =>
        val (wx, wy) = warp(x, y, width, height, recipe)
        (wx / width, wy / height)
    }

  // ------------------------------------------------------------------------------------------------------------------- Tone

  /** The sRGB EOTF: an sRGB-encoded value to linear light */
  def srgbToLinear(v: Double): Double = if v <= 0.04045 then v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

  /** The sRGB OETF: linear light to an sRGB-encoded value, unclamped */
  def linearToSrgb(l: Double): Double = if l <= 0.0031308 then 12.92 * l else 1.055 * Math.pow(l, 1 / 2.4) - 0.055

  /** The white balance's gains of red, green and blue (step 2), divided by their luminance so a neutral gray keeps its own */
  def whiteBalanceGains(recipe: EditRecipe): (Double, Double, Double) =
    val (t, tint) = (recipe.temperature / 100.0, recipe.tint / 100.0)
    val (r, g, b) = ((1 + 0.28 * t) * (1 + 0.08 * tint), 1 - 0.2 * tint, (1 - 0.28 * t) * (1 + 0.08 * tint))
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    (r / luminance, g / luminance, b / luminance)

  private def clamp(v: Double): Double = if v < 0 then 0 else if v > 1 then 1 else v

  /** The smoothstep of t clamped to [0, 1]: t²(3 − 2t) */
  def smoothstep(t: Double): Double =
    val x = clamp(t)
    x * x * (3 - 2 * x)

  /** Step 5, the tone of one sRGB-encoded value, in [0, 1] */
  def tone(value: Double, recipe: EditRecipe): Double =
    val blackPoint = -0.1 * recipe.blacks / 100
    val whitePoint = 1 - 0.15 * recipe.whites / 100
    var v = (value - blackPoint) / (whitePoint - blackPoint)

    val x = clamp(v)
    v += 0.95 * (recipe.shadows / 100.0) * x * (1 - x) * (1 - x)

    if recipe.highlights < 0 then
      // Towards a shoulder above 0.45 that no brighter tone overtakes
      val u = Math.max(0, v - 0.45)
      val shoulder = v - u + u / (1 + 1.6 * u)
      v += (shoulder - v) * (-recipe.highlights / 100.0)
    else if recipe.highlights > 0 then v += 0.3 * (recipe.highlights / 100.0) * smoothstep((v - 0.35) / 0.65)

    if recipe.gamma != 0 && v > 0 then v = Math.pow(v, 1 / Math.pow(2, recipe.gamma / 100.0))

    if recipe.contrast != 0 then
      val c = recipe.contrast / 100.0
      val x = clamp(v)
      val s = if c > 0 then 0.5 - 0.5 * Math.cos(Math.PI * x) else Math.acos(1 - 2 * x) / Math.PI
      v = x + 0.85 * Math.abs(c) * (s - x)

    clamp(v)

  /**
   * Step 6: the monotone cubic Hermite through the points (Fritsch–Carlson), held flat outside the first and last x and clamped
   * to [0, 1]. The tangents are the playground's: the end ones the end secants, an inner one the mean of its two secants (0 where
   * they differ in sign), then, segment by segment in order, both zero on a flat segment, or scaled down by 3 / √(α² + β²) when
   * that is over 3.
   */
  final class Curve(points: List[(Double, Double)]):
    private val xs = points.map(_._1).toArray
    private val ys = points.map(_._2).toArray
    private val n = xs.length
    private val tangents: Array[Double] =
      val secants = Array.tabulate(n - 1)(i => (ys(i + 1) - ys(i)) / Math.max(1e-6, xs(i + 1) - xs(i)))
      val m = new Array[Double](n)
      m(0) = secants(0)
      m(n - 1) = secants(n - 2)
      for i <- 1 until n - 1 do m(i) = if secants(i - 1) * secants(i) <= 0 then 0 else (secants(i - 1) + secants(i)) / 2
      for i <- 0 until n - 1 do
        if secants(i) == 0 then
          m(i) = 0
          m(i + 1) = 0
        else
          val (alpha, beta) = (m(i) / secants(i), m(i + 1) / secants(i))
          val s = alpha * alpha + beta * beta
          if s > 9 then
            val t = 3 / Math.sqrt(s)
            m(i) = t * alpha * secants(i)
            m(i + 1) = t * beta * secants(i)
      m

    def apply(x: Double): Double =
      if x <= xs(0) then ys(0)
      else if x >= xs(n - 1) then ys(n - 1)
      else
        // The segment whose end is the first at or past x
        var seg = 0
        while seg < n - 2 && x > xs(seg + 1) do seg += 1
        val h = xs(seg + 1) - xs(seg)
        val t = (x - xs(seg)) / h
        val (t2, t3) = (t * t, t * t * t)
        clamp(
          (2 * t3 - 3 * t2 + 1) * ys(seg) + (t3 - 2 * t2 + t) * h * tangents(seg) + (-2 * t3 + 3 * t2) * ys(seg + 1) +
            (t3 - t2) * h * tangents(seg + 1))

  /** Steps 1 to 6 on one channel's sRGB-encoded value: `channel` 0 is red, 1 green, 2 blue */
  def channelValue(v: Double, channel: Int, recipe: EditRecipe, curve: Option[Curve] = None): Double =
    val (r, g, b) = whiteBalanceGains(recipe)
    val gain = channel match
      case 0 => r
      case 1 => g
      case _ => b
    val toned = tone(linearToSrgb(srgbToLinear(v) * gain * Math.pow(2, recipe.exposure)), recipe)
    curve.getOrElse(Curve(recipe.curve))(toned)

  /** The tables of steps 1 to 6, red, green and blue: entry i of a channel is its value for the input i / 4095 */
  def tables(recipe: EditRecipe): Array[Array[Float]] =
    val curve = Curve(recipe.curve)
    Array.tabulate(3)(
      channel => Array.tabulate(TABLE_SIZE)(i => channelValue(i / (TABLE_SIZE - 1.0), channel, recipe, Some(curve)).toFloat))

  /** A table read at the value clamped to [0, 1], linearly between its two nearest entries */
  def lookup(table: Array[Float], v: Double): Double =
    val f = clamp(v) * (TABLE_SIZE - 1)
    val i = f.toInt
    if i >= TABLE_SIZE - 1 then table(TABLE_SIZE - 1) else table(i) + (table(i + 1) - table(i)) * (f - i)

  /** Step 7: each channel moved from the luminance by the saturation's and the vibrance's factor */
  def saturate(r: Double, g: Double, b: Double, recipe: EditRecipe): (Double, Double, Double) =
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))
    val vib = recipe.vibrance / 100.0
    val k = (1 + recipe.saturation / 100.0) * (1 + vib * (if vib > 0 then 1 - Math.min(1, 1.6 * chroma) else 1))
    (y + (r - y) * k, y + (g - y) * k, y + (b - y) * k)

  /**
   * Step 9's weight at the pixel (x, y), columns and rows from the top left, of a `width` × `height` output: smoothstep of the
   * distance d from the center, the offsets being fractions of the half width and half height, past the midpoint fixed at 50
   */
  def vignetteWeight(x: Int, y: Int, width: Int, height: Int): Double =
    val dx = (x - width / 2.0) / (width / 2.0)
    val dy = (y - height / 2.0) / (height / 2.0)
    smoothstep((Math.sqrt((dx * dx + dy * dy) / 2) - 0.39375) / 0.65625)

  /**
   * The playground's integer hash of a grain cell, in [0, 1]: 32-bit integer arithmetic, each product wrapping as `Math.imul` and
   * GLSL's `uint` do
   */
  def hash(x: Int, y: Int): Double =
    var h = x * 374761393 + y * 668265263
    h = (h ^ (h >>> 13)) * 1274126177
    Integer.toUnsignedLong(h ^ (h >>> 16)).toDouble / 4294967295.0

  /** Step 10's value noise at the pixel (x, y) over cells of `cell` pixels: the hash bilinearly between the cell's corners, −½ */
  def grainNoise(x: Int, y: Int, cell: Double): Double =
    val (gx, gy) = (x / cell, y / cell)
    val (x0, y0) = (Math.floor(gx).toInt, Math.floor(gy).toInt)
    val (fx, fy) = (gx - x0, gy - y0)
    (hash(x0, y0) * (1 - fx) + hash(x0 + 1, y0) * fx) * (1 - fy) +
      (hash(x0, y0 + 1) * (1 - fx) + hash(x0 + 1, y0 + 1) * fx) * fy - 0.5

  /** The output's size: the edited size, its long edge brought down to `longEdge` when that is shorter, the short side in step */
  def outputSize(editedWidth: Int, editedHeight: Int, longEdge: Option[Int]): (Int, Int) =
    val long = Math.max(editedWidth, editedHeight)
    longEdge.filter(_ < long).fold((editedWidth, editedHeight)) {
      edge =>
        val short = Math.max(1, Math.round(Math.min(editedWidth, editedHeight).toDouble * edge / long).toInt)
        if editedWidth >= editedHeight then (edge, short) else (short, edge)
    }

  // -------------------------------------------------------------------------------------------------------------- Rendering

  /** An image, and whether it is the renderer's to release: an image a caller passed in stays the caller's */
  final private class Owned(val mat: Mat, ours: Boolean):
    def release(): Unit = if ours then mat.release()

    /** `next` of the image, which is released once `next` has made another; the same image when `next` gives it back */
    def map(next: Mat => Mat): Owned =
      val result =
        try next(mat)
        catch
          case NonFatal(e) =>
            release()
            throw e
      if result eq mat then this
      else
        release()
        Owned(result, ours = true)

  /**
   * The Edit rendered from the original file at `longEdge` at most (None: the edited size), an 8-bit BGRA image the caller
   * releases. The original is decoded as stored and turned upright by its EXIF orientation, as its Preview is; metadata that
   * cannot be read is taken as upright, as `AssetService.uprightSize` takes it. A JPEG whose frame the output samples at half its
   * size or less is decoded at a half, a quarter or an eighth of its size (`IMREAD_REDUCED_*`), as fine as the output needs.
   */
  def render(original: Path, recipe: EditRecipe, longEdge: Option[Int]): Mat =
    val data = Files.readAllBytes(original)
    val orientation = orientationOf(data)
    val jpegUpright = ImageUtil.jpegSize(data).map(uprightSizeOf(_, orientation))
    val reduction = jpegUpright.fold(1)(upright => reductionFor(frameScale(upright, recipe, longEdge)))
    val stored = if reduction > 1 then ImageUtil.decodeReduced(data, reduction) else ImageUtil.decodeUnchanged(data)
    val upright = jpegUpright.getOrElse(uprightSizeOf((stored.width, stored.height), orientation))
    rendered(Owned(stored, ours = true), orientation, upright, recipe, longEdge)

  /** The Edit rendered from the upright original, as [[render]] of a file; the input stays the caller's */
  def render(upright: Mat, recipe: EditRecipe, longEdge: Option[Int]): Mat =
    rendered(Owned(upright, ours = false), 1, (upright.width, upright.height), recipe, longEdge)

  private def orientationOf(data: Array[Byte]): Int =
    try ImageUtil.exifOrientation(data)
    catch case _: ImageProcessingException | _: IOException => 1

  /** The upright size of an image stored at `size` under the EXIF orientation: turned a quarter for 5 to 8 */
  private def uprightSizeOf(size: (Int, Int), orientation: Int): (Int, Int) =
    if orientation >= 5 && orientation <= 8 then size.swap else size

  /** How many output pixels a frame pixel makes, the lesser of the two ways */
  private def frameScale(upright: (Int, Int), recipe: EditRecipe, longEdge: Option[Int]): Double =
    val (editedWidth, editedHeight) = recipe.editedSize(upright._1, upright._2)
    val (width, height) = outputSize(editedWidth, editedHeight, longEdge)
    val (frameWidth, frameHeight) = frameSize(upright._1, upright._2, recipe)
    val crop = recipe.crop.getOrElse(EditCrop(0, 0, 1, 1))
    Math.min(width / (crop.w * frameWidth), height / (crop.h * frameHeight))

  /** The largest of the decoder's reductions (2, 4, 8) that keeps a frame pixel per output pixel at `scale`, or 1 */
  private def reductionFor(scale: Double): Int = List(8, 4, 2).find(k => scale * k <= 1).getOrElse(1)

  /**
   * The image stored under the EXIF `orientation` (the upright original's at 1), rendered: `upright` is the upright original's
   * full size, of which the image may be a reduction. Each step releases the image before it once it has made its own, so a
   * render holds at most two images of the original's size at once.
   */
  private def rendered(stored: Owned, orientation: Int, upright: (Int, Int), recipe: EditRecipe, longEdge: Option[Int]): Mat =
    // Checked before anything is made of the image
    val refusal =
      if !Set(CvType.CV_8U, CvType.CV_16U).contains(stored.mat.depth()) then
        Some(s"An image of depth ${stored.mat.depth()} cannot be edited")
      else if !Set(1, 3, 4).contains(stored.mat.channels()) then
        Some(s"An image of ${stored.mat.channels()} channels cannot be edited")
      else None
    refusal.foreach {
      why =>
        stored.release()
        throw ImageException(why)
    }

    val (editedWidth, editedHeight) = recipe.editedSize(upright._1, upright._2)
    val (width, height) = outputSize(editedWidth, editedHeight, longEdge)
    val frame = stored.map(turned(_, frameOrientation(orientation, recipe)))
    val crop = recipe.crop.getOrElse(EditCrop(0, 0, 1, 1))
    // Output pixels per pixel of the frame as decoded, which may be a reduction of the frame
    val shrink = Math.min(width / (crop.w * frame.mat.width), height / (crop.h * frame.mat.height))
    val source = frame.map(shrunk(_, shrink))
    val (frameWidth, frameHeight) = frameSize(upright._1, upright._2, recipe)

    val maxValue = if source.mat.depth() == CvType.CV_16U then 65535.0 else 255.0
    val colors =
      try warped(source.mat, frameWidth, frameHeight, recipe, width, height)
      finally source.release()
    val image =
      try floatBgra(colors, 1 / maxValue)
      finally colors.release()
    try
      val warp = Warp(frameWidth, frameHeight, recipe)
      val (scaleX, scaleY) = (width / (crop.w * frameWidth), height / (crop.h * frameHeight))
      // An output pixel (column i, row j) shows the image where its frame point's source lies within the frame, which every
      // pixel's does when nothing is warped, the crop lying within the frame
      def covered(i: Int, j: Int): Boolean =
        !warp.moves || warp.covers(crop.x * frameWidth + (i + 0.5) / scaleX, crop.y * frameHeight + (j + 0.5) / scaleY)
      val luminance = toned(image, recipe, covered)
      try finished(image, luminance, recipe)
      finally luminance.release()
    finally image.release()

  /**
   * The EXIF orientation that turns the image stored under `orientation` straight into the Edit's frame: the upright original
   * turned clockwise by `rotate` and mirrored. Both are turns and mirrors of the image, so one of the eight does it, found by
   * trying each on a small image of distinct values.
   */
  private def frameOrientation(orientation: Int, recipe: EditRecipe): Int =
    val probe = new Mat(2, 3, CvType.CV_8UC1)
    probe.put(0, 0, Array[Byte](1, 2, 3, 4, 5, 6))
    def valuesOf(image: Mat): (Int, Int, Seq[Byte]) =
      val bytes = new Array[Byte](6)
      image.get(0, 0, bytes)
      (image.width, image.height, bytes.toSeq)
    val upright = ImageUtil.orient(probe, orientation)
    val frame = new Mat()
    try
      recipe.rotate match
        case 90 => Core.rotate(upright, frame, Core.ROTATE_90_CLOCKWISE)
        case 180 => Core.rotate(upright, frame, Core.ROTATE_180)
        case 270 => Core.rotate(upright, frame, Core.ROTATE_90_COUNTERCLOCKWISE)
        case _ => upright.copyTo(frame)
      (recipe.flipH, recipe.flipV) match
        case (true, true) => Core.flip(frame, frame, -1)
        case (true, false) => Core.flip(frame, frame, 1)
        case (false, true) => Core.flip(frame, frame, 0)
        case _ =>
      val wanted = valuesOf(frame)
      (1 to 8).find {
        code =>
          val candidate = ImageUtil.orient(probe, code)
          try valuesOf(candidate) == wanted
          finally candidate.release()
      }.get
    finally List(probe, upright, frame).foreach(_.release())

  /** The image turned and mirrored by the EXIF orientation `code` ([[ImageUtil.orient]]): the image itself for 1 */
  private def turned(image: Mat, code: Int): Mat = if code == 1 then image else ImageUtil.orient(image, code)

  /**
   * The image brought down by twice `scale` with `INTER_AREA` when `scale` is below a half, so the warp never shrinks by more
   * than half; an 8-bit image is shrunk in 16 bits, so the averages keep their fractions. The image itself otherwise.
   */
  private def shrunk(image: Mat, scale: Double): Mat =
    if scale >= 0.5 then image
    else
      val factor = 2 * scale
      val result = new Mat()
      filled(result) {
        val size = new Size(
          Math.max(1, Math.round(image.width * factor)).toDouble,
          Math.max(1, Math.round(image.height * factor)).toDouble)
        if image.depth() == CvType.CV_8U then
          image.convertTo(result, CvType.CV_16U, 257)
          Imgproc.resize(result, result, size, 0, 0, Imgproc.INTER_AREA)
        else Imgproc.resize(image, result, size, 0, 0, Imgproc.INTER_AREA)
      }

  /** The warped image, of the output's size, as a new BGRA image of 32-bit floats: each value times `scale`, alpha 1 if none */
  private def floatBgra(image: Mat, scale: Double): Mat =
    val bgra = new Mat()
    filled(bgra) {
      image.channels() match
        case 4 => image.copyTo(bgra)
        case 3 => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_BGR2BGRA)
        case _ => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_GRAY2BGRA)
      bgra.convertTo(bgra, CvType.CV_32F, scale)
    }

  /** `image` once `fill` has written it; released if `fill` fails, so a failed render leaves no image behind */
  private def filled(image: Mat)(fill: => Unit): Mat =
    try
      fill
      image
    catch
      case NonFatal(e) =>
        image.release()
        throw e

  /** 3 × 3 matrices, row by row */
  private type Matrix = Array[Double]

  private def times(m: Matrix, n: Matrix): Matrix =
    Array.tabulate(9)(k => (0 until 3).map(j => m(k / 3 * 3 + j) * n(j * 3 + k % 3)).sum)

  private def translation(x: Double, y: Double): Matrix = Array(1, 0, x, 0, 1, y, 0, 0, 1)
  private def scaling(x: Double, y: Double): Matrix = Array(x, 0, 0, 0, y, 0, 0, 0, 1)

  /**
   * Steps 2 and 3 on `source`, the frame (which may be a reduction of the `frameWidth` × `frameHeight` frame): warped and cropped
   * by one `warpPerspective` of the composed homography (frame → warp → crop offset → output scale) into `width` × `height`, in
   * the source's own depth and channels. Sampling is bilinear, the edge pixels repeated beyond the frame's last pixel centers
   * (`BORDER_REPLICATE`, as a texture's `CLAMP_TO_EDGE`), so no color is mixed with a border; where the image ends, the toning
   * step makes the output transparent.
   */
  private def warped(source: Mat, frameWidth: Double, frameHeight: Double, recipe: EditRecipe, width: Int, height: Int): Mat =
    val crop = recipe.crop.getOrElse(EditCrop(0, 0, 1, 1))
    val (scaleX, scaleY) = (width / (crop.w * frameWidth), height / (crop.h * frameHeight))
    val s = Math.max(frameWidth, frameHeight) / 2
    val theta = Math.toRadians(recipe.straighten)
    val (cos, sin) = (Math.cos(theta), Math.sin(theta))
    val (a, b) = keystones(recipe)
    // OpenCV's pixel coordinates have the pixel's center at 0, the spec's at 0.5
    val homography = List(
      translation(-0.5, -0.5),
      scaling(scaleX, scaleY),
      translation(-crop.x * frameWidth, -crop.y * frameHeight),
      translation(frameWidth / 2, frameHeight / 2),
      scaling(s, s),
      Array(cos, -sin, 0, sin, cos, 0, 0, 0, 1),
      Array(1, 0, 0, 0, 1, 0, a, b, 1),
      scaling(1 / s, 1 / s),
      translation(-frameWidth / 2, -frameHeight / 2),
      scaling(frameWidth / source.width, frameHeight / source.height),
      translation(0.5, 0.5)
    ).reduce(times)

    val output = new Mat()
    filled(output) {
      val matrix = new Mat(3, 3, CvType.CV_64F)
      try
        matrix.put(0, 0, homography*)
        Imgproc.warpPerspective(source, output, matrix, new Size(width, height), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
      finally matrix.release()
    }

  /**
   * Steps 4 to 7 on the warped image, in place: the tables of the tone steps and the curve, read for each channel, then the
   * saturation and the vibrance; a pixel the image does not cover (`covered`) is made transparent. Answers the luminance L of the
   * result, which the detail steps read.
   */
  private def toned(image: Mat, recipe: EditRecipe, covered: (Int, Int) => Boolean): Mat =
    val Array(red, green, blue) = tables(recipe)
    val (width, height) = (image.width, image.height)
    val saturates = recipe.saturation != 0 || recipe.vibrance != 0
    val luminance = new Mat(height, width, CvType.CV_32FC1)
    filled(luminance) {
      val row = new Array[Float](width * 4)
      val luminanceRow = new Array[Float](width)
      for j <- 0 until height do
        image.get(j, 0, row)
        for i <- 0 until width do
          val p = i * 4
          val (r0, g0, b0) = (lookup(red, row(p + 2)), lookup(green, row(p + 1)), lookup(blue, row(p)))
          val (r, g, b) = if saturates then saturate(r0, g0, b0, recipe) else (r0, g0, b0)
          row(p) = b.toFloat
          row(p + 1) = g.toFloat
          row(p + 2) = r.toFloat
          if !covered(i, j) then row(p + 3) = 0
          luminanceRow(i) = (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat
        image.put(j, 0, row)
        luminance.put(j, 0, luminanceRow)
    }

  /** L blurred by a Gaussian of `sigma` pixels (OpenCV's kernel for it, mirrored at the borders), or None for no blur asked */
  private def blurred(luminance: Mat, sigma: Option[Double]): Option[Mat] =
    sigma.map {
      s =>
        val blur = new Mat()
        Imgproc.GaussianBlur(luminance, blur, new Size(0, 0), s)
        blur
    }

  /**
   * Steps 8 to 11: Clarity's and Sharpen's deltas of L added to each channel, the vignette, the grain, then each value clamped
   * and encoded to 8 bits. Answers a new 8-bit BGRA image.
   */
  private def finished(image: Mat, luminance: Mat, recipe: EditRecipe): Mat =
    val (width, height) = (image.width, image.height)
    val long = Math.max(width, height)
    val clarity = recipe.clarity / 100.0 * 1.4
    val sharpen = recipe.sharpen / 100.0 * 2.2
    val vignette = recipe.vignette / 100.0
    val grain = recipe.grain / 100.0 * 0.22
    val grainCell = 1.75 * long / REFERENCE_LONG_EDGE

    val clarityBlur = blurred(luminance, Option.when(clarity != 0)(0.015 * Math.min(width, height)))
    try
      val sharpenBlur = blurred(luminance, Option.when(sharpen != 0)(Math.max(0.4, 0.8 * long / REFERENCE_LONG_EDGE)))
      try
        val output = new Mat(height, width, CvType.CV_8UC4)
        filled(output) {
          val row = new Array[Float](width * 4)
          val lRow = new Array[Float](width)
          val clarityRow = new Array[Float](width)
          val sharpenRow = new Array[Float](width)
          val out = new Array[Byte](width * 4)
          def byte(v: Double): Byte = Math.round(clamp(v) * 255).toByte

          for j <- 0 until height do
            image.get(j, 0, row)
            luminance.get(j, 0, lRow)
            clarityBlur.foreach(_.get(j, 0, clarityRow))
            sharpenBlur.foreach(_.get(j, 0, sharpenRow))
            for i <- 0 until width do
              val p = i * 4
              val l = lRow(i).toDouble
              var (b, g, r) = (row(p).toDouble, row(p + 1).toDouble, row(p + 2).toDouble)

              var delta = 0.0
              if clarity != 0 then
                val m = 2 * l - 1
                delta += (l - clarityRow(i)) * clarity * (1 - 0.7 * m * m)
              if sharpen != 0 then delta += (l - sharpenRow(i)) * sharpen
              r += delta
              g += delta
              b += delta

              if vignette != 0 then
                val f = vignette * vignetteWeight(i, j, width, height)
                if vignette < 0 then
                  r *= 1 + f
                  g *= 1 + f
                  b *= 1 + f
                else
                  r += (1 - r) * f
                  g += (1 - g) * f
                  b += (1 - b) * f

              if grain != 0 then
                val d = grainNoise(i, j, grainCell) * grain * (1 - 0.5 * Math.abs(2 * l - 1))
                r += d
                g += d
                b += d

              out(p) = byte(b)
              out(p + 1) = byte(g)
              out(p + 2) = byte(r)
              out(p + 3) = byte(row(p + 3))
            output.put(j, 0, out)
        }
      finally sharpenBlur.foreach(_.release())
    finally clarityBlur.foreach(_.release())

  // ------------------------------------------------------------------------------------------------------------ Straighten

  /**
   * The Straighten that levels the image's lines: the turned frame (step 1, before any Straighten) at a 1280 px long edge, its
   * edges found by Canny and its segments by `HoughLinesP`; of the segments longer than 8 % of the long edge, each one's
   * deviation from the nearer of horizontal and vertical (±45°), the length-weighted median, negated so that it turns the lines
   * level, in tenths of a degree. 0 when fewer than 3 segments qualify.
   */
  def autoStraightenAngle(original: Path, recipe: EditRecipe): Double =
    val data = Files.readAllBytes(original)
    val orientation = orientationOf(data)
    // A JPEG is decoded as small as keeps its long edge at the size read, or above
    val reduction = ImageUtil.jpegSize(data).fold(1) {
      (width, height) => List(8, 4, 2).find(k => Math.max(width, height) / k >= STRAIGHTEN_LONG_EDGE).getOrElse(1)
    }
    val stored = if reduction > 1 then ImageUtil.decodeReduced(data, reduction) else ImageUtil.decodeUnchanged(data)
    val frame = Owned(stored, ours = true)
      .map(turned(_, frameOrientation(orientation, recipe)))
      .map {
        image =>
          val scale = STRAIGHTEN_LONG_EDGE.toDouble / Math.max(image.width, image.height)
          val small = new Mat()
          filled(small) {
            Imgproc.resize(image, small, new Size(), scale, scale, if scale < 1 then Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
          }
      }
    val gray = new Mat()
    val edges = new Mat()
    val lines = new Mat()
    try
      frame.mat.channels() match
        case 4 => Imgproc.cvtColor(frame.mat, gray, Imgproc.COLOR_BGRA2GRAY)
        case 3 => Imgproc.cvtColor(frame.mat, gray, Imgproc.COLOR_BGR2GRAY)
        case _ => frame.mat.copyTo(gray)
      if gray.depth() == CvType.CV_16U then gray.convertTo(gray, CvType.CV_8U, 1.0 / 257)
      Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0)
      Imgproc.Canny(gray, edges, 50, 150)
      val minLength = STRAIGHTEN_MIN_LINE * STRAIGHTEN_LONG_EDGE
      Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 720, 60, minLength, 6)

      val deviations = (0 until lines.rows).flatMap {
        k =>
          val Array(x1, y1, x2, y2) = lines.get(k, 0)
          val length = Math.hypot(x2 - x1, y2 - y1)
          val angle = Math.toDegrees(Math.atan2(y2 - y1, x2 - x1))
          // From the nearer of horizontal and vertical, in [−45, 45)
          val deviation = ((angle + 45) % 90 + 90) % 90 - 45
          Option.when(length > minLength)((deviation, length))
      }
      if deviations.size < STRAIGHTEN_MIN_LINES then 0.0
      else
        val sorted = deviations.sortBy(_._1)
        val half = sorted.map(_._2).sum / 2
        val median =
          sorted.iterator.scanLeft((0.0, 0.0))((acc, line) => (line._1, acc._2 + line._2)).find(_._2 >= half).get._1
        // Levelled by turning the other way
        Math.round(-median * 10) / 10.0
    finally
      frame.release()
      List(gray, edges, lines).foreach(_.release())
