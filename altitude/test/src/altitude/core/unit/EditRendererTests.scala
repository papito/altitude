package altitude.core.unit

import altitude.test.TestFocus
import java.nio.file.Files
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.EditCrop
import altitude.core.models.EditRecipe
import altitude.core.util.EditRenderer
import altitude.core.util.ImageUtil

/** The renderer of an Edit (`EditRenderer`): the spec's geometry and per-pixel math, checked by hand, on synthetic images */
@DoNotDiscover class EditRendererTests extends AnyFunSuite with TestFocus {

  Loader.load(classOf[opencv_java])

  private val identity = EditRecipe.Identity

  /** An 8-bit BGR image of `width` × `height` whose pixel (x, y) is `color(x, y)` as (B, G, R) */
  private def image(width: Int, height: Int)(color: (Int, Int) => (Int, Int, Int)): Mat =
    val mat = new Mat(height, width, CvType.CV_8UC3)
    for (y <- 0 until height; x <- 0 until width) {
      val (b, g, r) = color(x, y)
      mat.put(y, x, Array[Byte](b.toByte, g.toByte, r.toByte))
    }
    mat

  /** The 8-bit channels of a pixel, unsigned */
  private def pixel(mat: Mat, x: Int, y: Int): Seq[Int] =
    val bytes = new Array[Byte](mat.channels())
    mat.get(y, x, bytes)
    bytes.toSeq.map(_ & 0xff)

  /** A color of its own for each pixel of a small image, so where a pixel lands tells which it was */
  private def distinct(x: Int, y: Int): (Int, Int, Int) = (10 + x * 20, 10 + y * 20, 200 - x * 10 - y * 10)

  private def rendered(source: Mat, recipe: EditRecipe, longEdge: Option[Int] = None): Mat =
    EditRenderer.render(source, recipe, longEdge)

  private def near(expected: Double, tolerance: Double = 1e-4) = (be >= (expected - tolerance)).and(be <= (expected + tolerance))

  test("The tone steps follow the spec: white balance, exposure, black and white points, shadows, highlights, gamma, contrast") {

    /**
     * Setup:
     *
     * Single slider Edits, and the sRGB values each is computed for by hand from the spec.
     *
     * Assertions:
     *
     * Temperature +100 gives the gains (1.28, 1, 0.72) divided by their luminance, so a neutral gray keeps its luminance.
     * Exposure +1 takes 0.5 to 0.6858 (its linear value doubled). Whites +100 takes 0.85 to white, Blacks −100 takes 0.1 to
     * black. Shadows +100 lifts 0.25 by 0.95 × 0.25 × 0.75², Highlights −100 takes 0.95 to its shoulder 0.7278, Highlights +100
     * lifts 0.675 by 0.3 × smoothstep(0.5). Gamma +100 (γ 2) takes 0.25 to 0.5. Contrast +100 takes 0.25 to 0.1620 along the
     * cosine S, and −100 to 0.3208 along its inverse.
     *
     * Edge cases:
     *
     * Every step clamps its result to [0, 1], and the identity leaves a value as it is.
     */
    val (gr, gg, gb) = EditRenderer.whiteBalanceGains(identity.copy(temperature = 100))
    val luminance = 0.2126 * 1.28 + 0.7152 + 0.0722 * 0.72
    gr should near(1.28 / luminance, 1e-9)
    gg should near(1 / luminance, 1e-9)
    gb should near(0.72 / luminance, 1e-9)
    0.2126 * gr + 0.7152 * gg + 0.0722 * gb should near(1, 1e-12)

    def channel(recipe: EditRecipe, v: Double): Double = EditRenderer.channelValue(v, 1, recipe)

    channel(identity, 0.37) should near(0.37, 1e-9)
    channel(identity.copy(exposure = 1), 0.5) should near(0.68584)
    channel(identity.copy(whites = 100), 0.85) should near(1)
    channel(identity.copy(whites = 100), 0.9) shouldBe 1.0
    channel(identity.copy(blacks = -100), 0.1) should near(0, 1e-9)
    channel(identity.copy(blacks = -100), 0.05) shouldBe 0.0
    channel(identity.copy(shadows = 100), 0.25) should near(0.25 + 0.95 * 0.25 * 0.75 * 0.75)
    channel(identity.copy(highlights = -100), 0.95) should near(0.95 - 0.5 + 0.5 / 1.8)
    channel(identity.copy(highlights = 100), 0.675) should near(0.675 + 0.3 * 0.5)
    channel(identity.copy(gamma = 100), 0.25) should near(0.5)
    channel(identity.copy(contrast = 100), 0.25) should near(0.25 + 0.85 * (0.5 - 0.5 * Math.cos(Math.PI / 4) - 0.25))
    channel(identity.copy(contrast = -100), 0.25) should near(0.25 + 0.85 * (1.0 / 3 - 0.25))
  }

  test("The curve is a monotone cubic through its points, held flat outside them, and the tables read it between entries") {

    /**
     * Setup:
     *
     * The curve through (0, 0), (0.5, 0.75) and (1, 1), whose Fritsch–Carlson tangents are 1.5, 1 and 0.5; one from (0.2, 0.1) to
     * (0.8, 0.9); and the tables of an Edit with Exposure +1.
     *
     * Assertions:
     *
     * The first curve passes through its points and gives 0.40625 at 0.25, the Hermite value by hand. The second is 0.1 below 0.2
     * and 0.9 above 0.8. A table holds 4096 entries per channel, entry i being the steps run on i / 4095, and a value between two
     * entries is read between them linearly.
     */
    val curve = EditRenderer.Curve(List((0.0, 0.0), (0.5, 0.75), (1.0, 1.0)))
    curve(0) should near(0, 1e-12)
    curve(0.5) should near(0.75, 1e-12)
    curve(1) should near(1, 1e-12)
    curve(0.25) should near(0.40625, 1e-12)

    val held = EditRenderer.Curve(List((0.2, 0.1), (0.8, 0.9)))
    held(0.1) shouldBe 0.1
    held(0.9) shouldBe 0.9

    val recipe = identity.copy(exposure = 1)
    val tables = EditRenderer.tables(recipe)
    tables.map(_.length).toSeq shouldBe Seq(4096, 4096, 4096)
    tables(0)(2048).toDouble should near(EditRenderer.channelValue(2048 / 4095.0, 0, recipe), 1e-6)
    val between = (1000.25) / 4095
    EditRenderer.lookup(tables(1), between) should near(tables(1)(1000) * 0.75 + tables(1)(1001) * 0.25, 1e-9)
    EditRenderer.lookup(tables(1), 2.0) shouldBe tables(1)(4095).toDouble
  }

  test("Saturation, vibrance, vignette and grain follow the spec") {

    /**
     * Setup:
     *
     * A color (0.6, 0.4, 0.2), and the weights of the vignette and the grain's hash.
     *
     * Assertions:
     *
     * Saturation −100 turns the color into its luminance Y. Vibrance +100 scales its distance from Y by 1 + (1 − min(1, 1.6 ×
     * 0.4)). The vignette's weight is 0 at the center and smoothstep((1 − 0.39375) / 0.65625) at a corner, where d is 1. The hash
     * is the grain's 32-bit integer hash (`docs/image-edits.md`, step 10): in [0, 1], the same for the same cell, and 0 at (0,
     * 0). The grain's cell is 1.75 px at a 3840 px long edge, its strength whole; at a 1000 px long edge the cell is floored at a
     * pixel and the strength is scaled by the 1.75 × 1000 / 3840 px the cell would have been.
     */
    val y = 0.2126 * 0.6 + 0.7152 * 0.4 + 0.0722 * 0.2
    val gray = EditRenderer.saturate(0.6, 0.4, 0.2, identity.copy(saturation = -100))
    gray._1 should near(y, 1e-12)
    gray._3 should near(y, 1e-12)
    val vivid = EditRenderer.saturate(0.6, 0.4, 0.2, identity.copy(vibrance = 100))
    val k = 1 + (1 - Math.min(1, 1.6 * 0.4))
    vivid._1 should near(y + (0.6 - y) * k, 1e-12)

    EditRenderer.vignetteWeight(50, 50, 100, 100) shouldBe 0.0
    val t = (1 - 0.39375) / 0.65625
    EditRenderer.vignetteWeight(0, 0, 100, 100) should near(t * t * (3 - 2 * t), 1e-12)

    (EditRenderer.grainCell(3840, 2560), EditRenderer.grainScale(3840, 2560)) shouldBe (1.75, 1.0)
    EditRenderer.grainCell(1000, 667) shouldBe 1.0
    EditRenderer.grainScale(1000, 667) should near(1.75 * 1000 / 3840, 1e-12)

    EditRenderer.hash(0, 0) shouldBe 0.0
    EditRenderer.hash(17, 4) shouldBe EditRenderer.hash(17, 4)
    (0 until 50).map(i => EditRenderer.hash(i, i * 3)).foreach(h => h should ((be >= 0.0).and(be <= 1.0)))
  }

  test("The geometry turns, mirrors, warps and inverts the warp as the spec says") {

    /**
     * Setup:
     *
     * A 4 × 2 upright original, and a 4 × 4 frame with Straighten 90 or a perspective.
     *
     * Assertions:
     *
     * A quarter turn clockwise makes a 2 × 4 frame and takes (1, 0.5) to (1.5, 1); a flip mirrors in the turned frame. Straighten
     * 90 takes a point right of the center to below it. A full horizontal perspective takes the middle of the right edge in to 1
     * / 1.55 of the half width, and the inverse warp takes every warped point back.
     *
     * Edge cases:
     *
     * The inverse has no source where 1 − a·uₓ − b·u_y is 0.001 or less.
     */
    val quarter = identity.copy(rotate = 90)
    EditRenderer.frameSize(4, 2, quarter) shouldBe (2, 4)
    EditRenderer.turn(1, 0.5, 4, 2, quarter) shouldBe (1.5, 1.0)
    EditRenderer.turn(1, 0.5, 4, 2, quarter.copy(flipH = true)) shouldBe (0.5, 1.0)
    EditRenderer.turn(1, 0.5, 4, 2, identity.copy(rotate = 270, flipV = true)) shouldBe (0.5, 1.0)

    val (x, y) = EditRenderer.warp(3, 2, 4, 4, identity.copy(straighten = 90))
    x should near(2, 1e-12)
    y should near(3, 1e-12)

    val keystone = identity.copy(perspectiveH = 100, perspectiveV = -40, straighten = 7.5)
    EditRenderer.warp(4, 2, 4, 4, identity.copy(perspectiveH = 100))._1 should near(2 + 2 / 1.55, 1e-12)
    for (px <- List(0.5, 1.7, 3.2); py <- List(0.3, 2.0, 3.9)) {
      val (wx, wy) = EditRenderer.warp(px, py, 4, 4, keystone)
      val (bx, by) = EditRenderer.unwarp(wx, wy, 4, 4, keystone).get
      bx should near(px, 1e-9)
      by should near(py, 1e-9)
    }

    // Far to the right, past where the perspective's plane meets the horizon
    EditRenderer.unwarp(4 + 2 / 0.55, 2, 4, 4, identity.copy(perspectiveH = 100)) shouldBe None
  }

  test("The renderer meets the shared vectors, which agree with the spec where it is checked by hand") {

    /**
     * Setup:
     *
     * `test/resources/edit/vectors.json`, which the Edit panel's modules are checked against too (`_doc` there says what each
     * group holds), read with the tolerance it states per group.
     *
     * Assertions:
     *
     * Every value of every group is what the renderer computes: the white balance gains, the table entries and lookups of each
     * tone recipe, the curve samples, the saturation, the frame, edited size, outline, turned, warped and unwarped points of each
     * geometry recipe with whether each warped point shows the image, and the hash, grain, vignette and output sizes. The vectors
     * agree with the spec by hand: the identity's table entry i is i / 4095, a quarter turn takes (x, y) to (H₀ − y, x), the
     * outline of an unwarped frame is its corners, an unwarped point warped again is the point, a point shows the image exactly
     * where its source exists and lies within the frame, and the hash of (0, 0) is 0.
     *
     * Edge cases:
     *
     * A point beyond the perspective's horizon has no source (null), and the narrowest crop of a small image is 1 × 1.
     */
    val vectors = ujson.read(getClass.getResourceAsStream("/edit/vectors.json").readAllBytes())
    val tolerance = vectors("tolerance")
    def close(group: String)(got: Double, expected: ujson.Value): Unit =
      got should near(expected.num, tolerance(group).num)
    def closePair(group: String)(got: (Double, Double), expected: ujson.Value): Unit =
      close(group)(got._1, expected(0))
      close(group)(got._2, expected(1))

    vectors("tone").arr.foreach {
      vector =>
        withClue(s"[${vector("name").str}]: ") {
          val recipe = EditRecipe.parse(vector("recipe"))
          val tables = EditRenderer.tables(recipe)
          val (gr, gg, gb) = EditRenderer.whiteBalanceGains(recipe)
          List(gr, gg, gb).zip(vector("gains").arr).foreach(close("tone"))
          vector("table").arr.foreach {
            entry => tables.map(_(entry("index").num.toInt).toDouble).zip(entry("rgb").arr).foreach(close("tone"))
          }
          vector("lookup").arr.foreach {
            entry => tables.map(EditRenderer.lookup(_, entry("v").num)).zip(entry("rgb").arr).foreach(close("tone"))
          }
          if (recipe == identity)
            vector("table").arr.foreach(entry => entry("rgb")(0).num should near(entry("index").num / 4095, 1e-6))
        }
    }

    vectors("curves").arr.foreach {
      vector =>
        val curve = EditRenderer.Curve(vector("points").arr.map(p => (p(0).num, p(1).num)).toList)
        vector("samples").arr.foreach(sample => close("curves")(curve(sample(0).num), sample(1)))
    }

    vectors("saturation").arr.foreach {
      vector =>
        val Seq(r, g, b) = vector("in").arr.map(_.num).toSeq: @unchecked
        val (sr, sg, sb) = EditRenderer.saturate(r, g, b, EditRecipe.parse(vector("recipe")))
        List(sr, sg, sb).zip(vector("out").arr).foreach(close("saturation"))
    }

    vectors("geometry").arr.foreach {
      vector =>
        withClue(s"[${vector("name").str}]: ") {
          val recipe = EditRecipe.parse(vector("recipe"))
          val Seq(w0, h0) = vector("upright").arr.map(_.num.toInt).toSeq: @unchecked
          val (w, h) = EditRenderer.frameSize(w0, h0, recipe)
          Seq(w, h) shouldBe vector("frame").arr.map(_.num.toInt).toSeq
          val (ew, eh) = recipe.editedSize(w0, h0)
          Seq(ew, eh) shouldBe vector("edited").arr.map(_.num.toInt).toSeq
          EditRenderer.outline(w, h, recipe).zip(vector("outline").arr).foreach(closePair("geometry"))
          vector("turned").arr.foreach(
            p => closePair("geometry")(EditRenderer.turn(p("in")(0).num, p("in")(1).num, w0, h0, recipe), p("out")))
          vector("warped").arr.foreach(
            p => closePair("geometry")(EditRenderer.warp(p("in")(0).num, p("in")(1).num, w, h, recipe), p("out")))
          val warp = EditRenderer.Warp(w, h, recipe)
          vector("unwarped").arr.foreach {
            p =>
              val got = EditRenderer.unwarp(p("in")(0).num, p("in")(1).num, w, h, recipe)
              p("out") match
                case ujson.Null => got shouldBe None
                case out => closePair("geometry")(got.get, out)
              warp.covers(p("in")(0).num, p("in")(1).num) shouldBe p("covered").bool
              // By hand: covered exactly where the source exists and lies within the frame
              p("covered").bool shouldBe got.exists((x, y) => x >= 0 && x <= w && y >= 0 && y <= h)
          }

          // By hand
          if (recipe.rotate == 90 && !recipe.flipH && !recipe.flipV)
            vector("turned").arr.foreach(p => closePair("geometry")((h0 - p("in")(1).num, p("in")(0).num), p("out")))
          if (recipe.straighten == 0 && recipe.perspectiveH == 0 && recipe.perspectiveV == 0)
            vector("outline").arr.map(c => (c(0).num, c(1).num)).toList shouldBe List(
              (0.0, 0.0),
              (1.0, 0.0),
              (1.0, 1.0),
              (0.0, 1.0))
          vector("warped").arr.foreach {
            p =>
              EditRenderer
                .unwarp(p("out")(0).num, p("out")(1).num, w, h, recipe)
                .foreach(back => closePair("geometry")(back, p("in")))
          }
        }
    }
    vectors("geometry").arr.find(_("name").str.startsWith("narrowest")).get("edited") shouldBe ujson.Arr(1, 1)
    vectors("geometry").arr
      .find(_("name").str.startsWith("full keystone"))
      .get("unwarped")
      .arr
      .exists(_("out").isNull) shouldBe true

    val effects = vectors("effects")
    effects("hash").arr.foreach(v => close("effects")(EditRenderer.hash(v("x").num.toInt, v("y").num.toInt), v("h")))
    effects("hash")(0)("h").num shouldBe 0.0
    effects("grain").arr.foreach {
      v => close("effects")(EditRenderer.grainNoise(v("x").num.toInt, v("y").num.toInt, v("cell").num), v("noise"))
    }
    effects("grainSizes").arr.foreach {
      v =>
        val (w, h) = (v("width").num.toInt, v("height").num.toInt)
        close("effects")(EditRenderer.grainCell(w, h), v("cell"))
        close("effects")(EditRenderer.grainScale(w, h), v("scale"))
    }
    effects("vignette").arr.foreach {
      v =>
        close("effects")(
          EditRenderer.vignetteWeight(v("x").num.toInt, v("y").num.toInt, v("width").num.toInt, v("height").num.toInt),
          v("weight"))
    }
    effects("outputSizes").arr.foreach {
      v =>
        val Seq(ew, eh) = v("edited").arr.map(_.num.toInt).toSeq: @unchecked
        val (ow, oh) = EditRenderer.outputSize(ew, eh, v("longEdge").numOpt.map(_.toInt))
        Seq(ow, oh) shouldBe v("output").arr.map(_.num.toInt).toSeq
    }
  }

  test("The identity Edit gives back the image, and the output is the edited size") {

    /**
     * Setup:
     *
     * A 7 × 5 image of a color per pixel, rendered with the identity, and an Edit cropping it.
     *
     * Assertions:
     *
     * The identity's output is the image within 1/255, each pixel opaque, at 7 × 5. The cropped output is the Edit's edited size.
     */
    val source = image(7, 5)(distinct)
    val output = rendered(source, identity)
    (output.width, output.height) shouldBe (7, 5)
    output.`type` shouldBe CvType.CV_8UC4
    for (y <- 0 until 5; x <- 0 until 7) {
      val (b, g, r) = distinct(x, y)
      pixel(output, x, y).zip(Seq(b, g, r, 255)).foreach((got, want) => Math.abs(got - want) should be <= 1)
    }

    val cropped = identity.copy(crop = Some(EditCrop(0.1, 0.2, 0.55, 0.61)), aspect = "free", cropFitted = false)
    val croppedOutput = rendered(source, cropped)
    (croppedOutput.width, croppedOutput.height) shouldBe cropped.editedSize(7, 5)
    List(source, output, croppedOutput).foreach(_.release())
  }

  test("The composed warp samples the frame where the inverse warp says, and is transparent where it has no source") {

    /**
     * Setup:
     *
     * A 29 × 19 image of smooth gradients, with Straighten 7.3, horizontal perspective +60, vertical −60 and a crop, rendered at
     * a long edge of 18 (a scale above a half, so nothing is shrunk before the warp).
     *
     * Assertions:
     *
     * Each output pixel's frame point, through the inverse warp, is the source: where that lies inside the frame, the pixel is
     * opaque and its color is the frame sampled bilinearly there, the edge pixels repeated beyond the last pixel centers, within
     * 1/255; where it lies outside, or has no source, the pixel is transparent. The edge is hard: alpha is 0 or 255.
     */
    val (width, height) = (29, 19)
    def color(x: Int, y: Int): (Int, Int, Int) = (10 + x * 7, 10 + y * 9, 40 + (x + y) * 4)
    val source = image(width, height)(color)
    val crop = EditCrop(0.05, 0.1, 0.8, 0.85)
    val recipe =
      identity.copy(
        straighten = 7.3,
        perspectiveH = 60,
        perspectiveV = -60,
        crop = Some(crop),
        aspect = "free",
        cropFitted = false)
    val output = rendered(source, recipe, Some(18))
    (output.width, output.height) shouldBe (18, 13)

    def sample(px: Double, py: Double): Seq[Double] =
      val (x, y) = (px - 0.5, py - 0.5)
      val (x0, y0) = (Math.floor(x).toInt, Math.floor(y).toInt)
      val (fx, fy) = (x - x0, y - y0)
      def at(cx: Int, cy: Int): Seq[Double] =
        val (b, g, r) = color(Math.max(0, Math.min(width - 1, cx)), Math.max(0, Math.min(height - 1, cy)))
        Seq(b.toDouble, g.toDouble, r.toDouble)
      val (top, bottom) =
        (
          at(x0, y0).zip(at(x0 + 1, y0)).map((a, b) => a * (1 - fx) + b * fx),
          at(x0, y0 + 1).zip(at(x0 + 1, y0 + 1)).map((a, b) => a * (1 - fx) + b * fx))
      top.zip(bottom).map((a, b) => a * (1 - fy) + b * fy)

    val (scaleX, scaleY) = (18 / (crop.w * width), 13 / (crop.h * height))
    var (inside, outside) = (0, 0)
    for (j <- 0 until 13; i <- 0 until 18) withClue(s"[$i, $j]: ") {
      val (fx, fy) = (crop.x * width + (i + 0.5) / scaleX, crop.y * height + (j + 0.5) / scaleY)
      val got = pixel(output, i, j)
      EditRenderer
        .unwarp(fx, fy, width, height, recipe)
        .filter((px, py) => px >= 0 && px <= width && py >= 0 && py <= height) match
        case Some((px, py)) =>
          inside += 1
          got(3) shouldBe 255
          got.take(3).zip(sample(px, py)).foreach((g, want) => g.toDouble should near(want, 1.0))
        case None =>
          outside += 1
          got(3) shouldBe 0
    }
    inside should be > 100
    outside should be > 5
  }

  test("Rotate, flip and crop place each pixel where the frame has it") {

    /**
     * Setup:
     *
     * A 6 × 4 image of a color per pixel.
     *
     * Assertions:
     *
     * A quarter turn is OpenCV's clockwise rotation, 4 × 6; a horizontal flip mirrors each row; both together mirror the turned
     * frame. A crop of the top right quarter is 3 × 2 holding that quarter's pixels.
     */
    val source = image(6, 4)(distinct)

    def same(output: Mat, expected: Mat): Unit =
      (output.width, output.height) shouldBe (expected.width, expected.height)
      for (y <- 0 until expected.height; x <- 0 until expected.width)
        pixel(output, x, y).take(3).zip(pixel(expected, x, y)).foreach((got, want) => Math.abs(got - want) should be <= 1)

    val turned = new Mat()
    Core.rotate(source, turned, Core.ROTATE_90_CLOCKWISE)
    same(rendered(source, identity.copy(rotate = 90)), turned)

    val mirrored = new Mat()
    Core.flip(source, mirrored, 1)
    same(rendered(source, identity.copy(flipH = true)), mirrored)

    val turnedMirrored = new Mat()
    Core.flip(turned, turnedMirrored, 1)
    same(rendered(source, identity.copy(rotate = 90, flipH = true)), turnedMirrored)

    val quarter = rendered(source, identity.copy(crop = Some(EditCrop(0.5, 0, 0.5, 0.5)), aspect = "free", cropFitted = false))
    same(quarter, source.submat(0, 2, 3, 6))
  }

  test(
    "A file renders as its upright image does, its EXIF orientation and the Edit's turn made one, and a JPEG decoded small for a small output") {

    /**
     * Setup:
     *
     * The fixture tagged with EXIF orientation 6, rendered from its file and from its image decoded and turned upright, with no
     * turn, with a quarter turn and a horizontal flip, and with three quarters and a vertical flip. A 2400 × 1600 JPEG of smooth
     * gradients, rendered at a long edge of 300 from its file (decoded at an eighth by the decoder) and from its decoded image.
     *
     * Assertions:
     *
     * The oriented file's renders are its upright image's, pixel for pixel within 1/255. The small JPEG's are of one size and
     * differ by under 2/255 on average.
     */
    val oriented = java.nio.file.Path.of(getClass.getResource("/import/images/exif/orientation-6.jpg").toURI)
    val stored = ImageUtil.decodeUnchanged(Files.readAllBytes(oriented))
    val upright = ImageUtil.orient(stored, 6)
    for (recipe <- List(identity, identity.copy(rotate = 90, flipH = true), identity.copy(rotate = 270, flipV = true)))
      withClue(s"[${recipe.rotate} ${recipe.flipH} ${recipe.flipV}]: ") {
        val fromFile = EditRenderer.render(oriented, recipe, None)
        val fromImage = rendered(upright, recipe)
        (fromFile.width, fromFile.height) shouldBe (fromImage.width, fromImage.height)
        val difference = new Mat()
        Core.absdiff(fromFile, fromImage, difference)
        Core.minMaxLoc(difference.reshape(1)).maxVal should be <= 1.0
        List(fromFile, fromImage, difference).foreach(_.release())
      }

    val large = image(2400, 1600)((x, y) => (20 + x / 12, 30 + y / 10, 60 + (x + y) / 30))
    val file = Files.createTempFile("large", ".jpg")
    try {
      Imgcodecs.imwrite(file.toString, large) shouldBe true
      ImageUtil.jpegSize(Files.readAllBytes(file)) shouldBe Some((2400, 1600))
      ImageUtil.jpegSize(Files.readAllBytes(oriented)).map(_.swap) shouldBe Some((upright.width, upright.height))
      val decoded = Imgcodecs.imread(file.toString)
      val recipe = identity.copy(exposure = 0.2)
      val small = EditRenderer.render(file, recipe, Some(300))
      val reference = rendered(decoded, recipe, Some(300))
      (small.width, small.height) shouldBe (300, 200)
      (reference.width, reference.height) shouldBe (300, 200)
      val difference = new Mat()
      Core.absdiff(small, reference, difference)
      Core.mean(difference).`val`.take(3).foreach(_ should be < 2.0)
    } finally Files.deleteIfExists(file): Unit
  }

  test("Straighten at 90° equals a quarter turn") {

    /**
     * Setup:
     *
     * A 6 × 6 image of a color per pixel, with Straighten set to 90 (outside the slider's range, which the case class holds).
     *
     * Assertions:
     *
     * Its output is the image turned a quarter clockwise, within 1/255 at every pixel.
     */
    val source = image(6, 6)(distinct)
    val turned = new Mat()
    Core.rotate(source, turned, Core.ROTATE_90_CLOCKWISE)
    val output = rendered(source, identity.copy(straighten = 90))
    for (y <- 0 until 6; x <- 0 until 6)
      pixel(output, x, y).take(3).zip(pixel(turned, x, y)).foreach((got, want) => Math.abs(got - want) should be <= 1)
  }

  test("Exposure +1 doubles linear values") {

    /**
     * Setup:
     *
     * A gray ramp from 20 to 220, rendered with Exposure +1.
     *
     * Assertions:
     *
     * Each output gray, back to linear, is twice the input's, within the 8-bit rounding.
     */
    val source = image(11, 1)((x, _) => (20 + x * 20, 20 + x * 20, 20 + x * 20))
    val output = rendered(source, identity.copy(exposure = 1))
    for (x <- 0 until 11) {
      val in = EditRenderer.srgbToLinear((20 + x * 20) / 255.0)
      val out = EditRenderer.srgbToLinear(pixel(output, x, 0).head / 255.0)
      out should near(Math.min(1, 2 * in), 0.006)
    }
  }

  test("A long edge caps the output, and a scale below a half shrinks the image before the warp") {

    /**
     * Setup:
     *
     * A 4000 × 100 gray image with a white column at its center, rendered with an Exposure change at a long edge of 3840 and of
     * 500.
     *
     * Assertions:
     *
     * The outputs are 3840 × 96 and 500 × 13: the long edge is the cap and the short side its share, rounded. The white column is
     * still at the center of the smaller one.
     *
     * Edge cases:
     *
     * A cap above the edited size leaves the size as it is.
     */
    val source = new Mat(100, 4000, CvType.CV_8UC3, new Scalar(60, 60, 60))
    Imgproc.rectangle(source, new Point(1990, 0), new Point(2009, 99), new Scalar(255, 255, 255), -1)
    val recipe = identity.copy(exposure = 0.5)

    val display = rendered(source, recipe, Some(3840))
    (display.width, display.height) shouldBe (3840, 96)
    val small = rendered(source, recipe, Some(500))
    (small.width, small.height) shouldBe (500, 13)
    pixel(small, 250, 6).head should be > 200
    pixel(small, 100, 6).head should be < 150
    val whole = rendered(source, recipe, Some(5000))
    (whole.width, whole.height) shouldBe (4000, 100)
  }

  test("Vignette darkens the corners and leaves the center, and grain is the same for a given output size") {

    /**
     * Setup:
     *
     * A 64 × 48 mid-gray image, rendered with Vignette −100; a 1024 × 768 one rendered twice with Grain 100.
     *
     * Assertions:
     *
     * The vignette leaves the center as it was and makes a corner darker. The grain changes pixels, and two renders at one size
     * are the same.
     *
     * Edge cases:
     *
     * At 1024 px the grain's cell is floored at a pixel, so each pixel is its own cell: the grain at a pixel is the hash at it,
     * less ½, at the strength scaled by 1.75 × 1024 / 3840.
     */
    val source = new Mat(48, 64, CvType.CV_8UC3, new Scalar(128, 128, 128))
    val vignetted = rendered(source, identity.copy(vignette = -100))
    pixel(vignetted, 32, 24).head shouldBe 128 +- 1
    pixel(vignetted, 0, 0).head should be < 100

    val large = new Mat(768, 1024, CvType.CV_8UC3, new Scalar(128, 128, 128))
    val grained = rendered(large, identity.copy(grain = 100))
    val again = rendered(large, identity.copy(grain = 100))
    val grainPixels = for (y <- 0 until 48; x <- 0 until 64) yield pixel(grained, x, y)
    grainPixels.map(_.head).distinct.size should be > 3
    grainPixels shouldBe (for (y <- 0 until 48; x <- 0 until 64) yield pixel(again, x, y))
    // Mid-gray as the tables leave it, so the grain's weight 1 − ½|2L − 1| is close to 1
    val l = pixel(rendered(large, identity), 7, 3).head / 255.0
    val expected = (l + (EditRenderer.hash(7, 3) - 0.5) * 0.22 * (1.75 * 1024 / 3840) * (1 - 0.5 * Math.abs(2 * l - 1))) * 255
    pixel(grained, 7, 3).head.toDouble shouldBe expected +- 1.0
  }

  test("Auto-straighten answers the Straighten that levels lines drawn at 2.7°, and nothing where no lines agree") {

    /**
     * Setup:
     *
     * A 1600 × 1200 JPEG on disk of dark lines on white, each rising 2.7° to the right, and a few short marks that are no line; a
     * blank image; and two portraits of the import fixtures, busy pictures with no horizon, `people/affleck.jpg` and
     * `people/bullock.jpg`.
     *
     * Assertions:
     *
     * The answer for the lines is 2.7 ± 0.2, the clockwise Straighten that levels them.
     *
     * Edge cases:
     *
     * The blank image, with no line, answers nothing; so do the portraits, whose edges are no level or plumb lines that agree.
     */
    val drawing = new Mat(1200, 1600, CvType.CV_8UC3, new Scalar(255, 255, 255))
    val rise = Math.tan(Math.toRadians(2.7))
    for (i <- 0 until 8) {
      val y = 200.0 + i * 110
      Imgproc.line(drawing, new Point(150, y), new Point(1450, y - 1300 * rise), new Scalar(30, 30, 30), 4, Imgproc.LINE_AA)
    }
    Imgproc.circle(drawing, new Point(800, 1100), 20, new Scalar(0, 0, 0), 3)
    val file = Files.createTempFile("lines", ".jpg")
    val blank = Files.createTempFile("blank", ".jpg")
    try {
      Imgcodecs.imwrite(file.toString, drawing) shouldBe true
      Imgcodecs.imwrite(blank.toString, new Mat(600, 800, CvType.CV_8UC3, new Scalar(255, 255, 255))) shouldBe true
      EditRenderer.autoStraightenAngle(file, identity).get should near(2.7, 0.2)
      EditRenderer.autoStraightenAngle(blank, identity) shouldBe None
      for (portrait <- List("people/affleck.jpg", "people/bullock.jpg")) withClue(portrait) {
        val path = java.nio.file.Paths.get(getClass.getResource(s"/import/$portrait").toURI)
        EditRenderer.autoStraightenAngle(path, identity) shouldBe None
      }
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(blank): Unit
    }
  }
}
