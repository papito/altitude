package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.ValidationException
import altitude.core.models.EditCrop
import altitude.core.models.EditRecipe

@DoNotDiscover class EditRecipeTests extends funsuite.AnyFunSuite with TestFocus {

  /** The identity Edit as a client sends it, every key present */
  private def identityJson: ujson.Obj = ujson
    .read("""{
      "version": 1,
      "rotate": 0, "flipH": false, "flipV": false,
      "straighten": 0, "perspectiveV": 0, "perspectiveH": 0,
      "crop": null, "aspect": "original", "portrait": null, "keepInside": true, "cropFitted": true,
      "exposure": 0, "contrast": 0, "highlights": 0, "shadows": 0, "whites": 0, "blacks": 0, "gamma": 0,
      "curve": [[0, 0], [1, 1]],
      "temperature": 0, "tint": 0, "vibrance": 0, "saturation": 0,
      "sharpen": 0, "clarity": 0, "vignette": 0, "grain": 0
    }""")
    .obj

  /** The identity Edit's JSON with `changes` set over it */
  private def jsonWith(changes: (String, ujson.Value)*): ujson.Obj = {
    val json = identityJson
    changes.foreach { case (key, value) => json(key) = value }
    json
  }

  private def refused(json: ujson.Value): ValidationException = intercept[ValidationException](EditRecipe.parse(json))

  test("A full Edit parses, rounds each number to its step, and writes every key back") {

    /**
     * Setup:
     *
     * The identity Edit's JSON, and one with a value off its step for every kind of number: Straighten, Exposure, an integer
     * slider, the crop and a curve point.
     *
     * Assertions:
     *
     * The identity parses to the default Edit, and its JSON is the input, every key present. The other parses with each number
     * rounded to its step: Straighten to 0.1, Exposure to 0.05, a slider to a whole number, the crop to 4 decimals and a curve
     * point to 3; its JSON carries the rounded values, and parses back to the same Edit.
     */
    EditRecipe.parse(identityJson) shouldBe EditRecipe.Identity
    EditRecipe.Identity.toJson shouldBe identityJson

    val offStep = EditRecipe.parse(
      jsonWith(
        "straighten" -> 2.34,
        "exposure" -> 0.4249,
        "contrast" -> 12.6,
        "crop" -> ujson.Obj("x" -> 0.123456, "y" -> 0.2, "w" -> 0.5, "h" -> 0.49999),
        "curve" -> ujson.Arr(ujson.Arr(0, 0), ujson.Arr(0.50049, 0.6), ujson.Arr(1, 1))
      ))

    offStep.straighten shouldBe 2.3
    offStep.exposure shouldBe 0.40
    offStep.contrast shouldBe 13
    offStep.crop shouldBe Some(EditCrop(0.1235, 0.2, 0.5, 0.5))
    offStep.curve shouldBe List((0.0, 0.0), (0.5, 0.6), (1.0, 1.0))
    offStep.toJson("exposure").num shouldBe 0.4
    EditRecipe.parse(offStep.toJson) shouldBe offStep
    EditRecipe.parse(ujson.write(offStep.toJson)) shouldBe offStep
  }

  test("An Edit with a key missing, an unknown key, another version or a value of the wrong type is refused") {

    /**
     * Setup:
     *
     * The identity Edit's JSON, changed each time in one way.
     *
     * Assertions:
     *
     * Each is refused with a `ValidationException` saying why: no key may be left out (defaults are not filled in), no key may be
     * added, the version must be 1, and each value must have its type: a number for a slider, a boolean for a flip, one of the
     * aspects for `aspect`, a boolean or null for `portrait`, an object or null for `crop`, an array for `curve`.
     *
     * Edge cases:
     *
     * A body that is no JSON object at all, and text that is not JSON, are refused the same way.
     */
    val missing = identityJson
    missing.value.remove("exposure")
    refused(missing).message should include("exposure")

    refused(jsonWith("dehaze" -> 10)).message should include("dehaze")
    refused(jsonWith("version" -> 2)).message should include("version")
    refused(jsonWith("exposure" -> "0.5")).message should include("exposure")
    refused(jsonWith("flipH" -> 1)).message should include("flipH")
    refused(jsonWith("aspect" -> "4:3")).message should include("aspect")
    refused(jsonWith("portrait" -> "yes")).message should include("portrait")
    refused(jsonWith("crop" -> ujson.Arr(0, 0, 1, 1))).message should include("crop")
    refused(jsonWith("curve" -> "linear")).message should include("curve")
    refused(jsonWith("rotate" -> 45)).message should include("rotate")
    refused(jsonWith("crop" -> ujson.Obj("x" -> 0, "y" -> 0, "w" -> 1))).message should include("crop")
    refused(jsonWith("crop" -> ujson.Obj("x" -> 0, "y" -> 0, "w" -> 1, "h" -> 1, "angle" -> 0))).message should include("crop")

    refused(ujson.Arr(1, 2))
    intercept[ValidationException](EditRecipe.parse("{not json"))
  }

  test("A value outside its range, a crop outside the frame and a curve out of shape are refused") {

    /**
     * Setup:
     *
     * The identity Edit's JSON, changed each time in one way.
     *
     * Assertions:
     *
     * Each is refused: Straighten past ±45, Exposure past ±5, a slider past ±100, Sharpen below 0 or past 150, Grain past 100, a
     * crop that reaches past the frame's edge or is narrower or shorter than 0.01, a curve of one point or of 17, a curve whose x
     * does not increase, and a curve point outside [0, 1].
     *
     * Edge cases:
     *
     * The bounds themselves are accepted: Straighten 45, Sharpen 150, a crop of 0.01 at the frame's edge, and 16 curve points.
     */
    refused(jsonWith("straighten" -> 45.2))
    refused(jsonWith("exposure" -> -5.1))
    refused(jsonWith("contrast" -> 101))
    refused(jsonWith("sharpen" -> -1))
    refused(jsonWith("sharpen" -> 151))
    refused(jsonWith("grain" -> 101))
    refused(jsonWith("crop" -> ujson.Obj("x" -> 0.5, "y" -> 0, "w" -> 0.6, "h" -> 1)))
    refused(jsonWith("crop" -> ujson.Obj("x" -> -0.1, "y" -> 0, "w" -> 0.5, "h" -> 1)))
    refused(jsonWith("crop" -> ujson.Obj("x" -> 0, "y" -> 0, "w" -> 0.009, "h" -> 1)))
    refused(jsonWith("curve" -> ujson.Arr(ujson.Arr(0, 0))))
    refused(jsonWith("curve" -> ujson.Arr.from((0 to 16).map(i => ujson.Arr(i / 16.0, i / 16.0)))))
    refused(jsonWith("curve" -> ujson.Arr(ujson.Arr(0, 0), ujson.Arr(0.5, 0.5), ujson.Arr(0.5, 0.7), ujson.Arr(1, 1))))
    refused(jsonWith("curve" -> ujson.Arr(ujson.Arr(0, 0), ujson.Arr(1, 1.2))))

    EditRecipe.parse(jsonWith("straighten" -> 45)).straighten shouldBe 45.0
    EditRecipe.parse(jsonWith("sharpen" -> 150)).sharpen shouldBe 150
    EditRecipe.parse(jsonWith("crop" -> ujson.Obj("x" -> 0.99, "y" -> 0, "w" -> 0.01, "h" -> 1))).crop shouldBe
      Some(EditCrop(0.99, 0, 0.01, 1))
    EditRecipe
      .parse(jsonWith("curve" -> ujson.Arr.from((0 to 15).map(i => ujson.Arr(i / 15.0, i / 15.0)))))
      .curve
      .size shouldBe 16
  }

  test("An Edit is the identity when it changes no pixel, whatever its Crop tool settings") {

    /**
     * Setup:
     *
     * The default Edit; the default with only the Crop tool's settings changed (the aspect, its orientation, keeping the crop
     * inside and whether the crop was fitted); and the default with one thing changed each that moves a pixel.
     *
     * Assertions:
     *
     * The first two are the identity. Each of the others is not: a quarter turn, a flip, Straighten, perspective, a crop, a
     * slider, a curve that is not the diagonal, and an Effects slider.
     */
    EditRecipe.Identity.isIdentity shouldBe true
    EditRecipe.Identity
      .copy(aspect = "3:2", portrait = Some(true), keepInside = false, cropFitted = false)
      .isIdentity shouldBe true

    List(
      EditRecipe.Identity.copy(rotate = 90),
      EditRecipe.Identity.copy(flipV = true),
      EditRecipe.Identity.copy(straighten = 0.1),
      EditRecipe.Identity.copy(perspectiveH = -3),
      EditRecipe.Identity.copy(crop = Some(EditCrop(0, 0, 1, 1))),
      EditRecipe.Identity.copy(exposure = 0.05),
      EditRecipe.Identity.copy(curve = List((0.0, 0.1), (1.0, 1.0))),
      EditRecipe.Identity.copy(grain = 1)
    ).foreach(recipe => withClue(recipe.toJson)(recipe.isIdentity shouldBe false))
  }

  test("The edited size is the crop's share of the turned frame, in whole pixels") {

    /**
     * Setup:
     *
     * An upright original of 4000 × 3000 and Edits with no crop, a quarter turn, a half crop, and a quarter turn with a crop.
     *
     * Assertions:
     *
     * With no crop the size is the original's, or swapped for a quarter turn (90 or 270, not 180). A crop is a fraction of the
     * turned frame, rounded to whole pixels: half the width and a third of the height of the turned 3000 × 4000 frame is 1500 ×
     * 1333.
     *
     * Edge cases:
     *
     * A crop of a tiny image never rounds to no pixel at all.
     */
    EditRecipe.Identity.editedSize(4000, 3000) shouldBe (4000, 3000)
    EditRecipe.Identity.copy(rotate = 90).editedSize(4000, 3000) shouldBe (3000, 4000)
    EditRecipe.Identity.copy(rotate = 180).editedSize(4000, 3000) shouldBe (4000, 3000)
    EditRecipe.Identity.copy(rotate = 270).editedSize(4000, 3000) shouldBe (3000, 4000)
    EditRecipe.Identity.copy(crop = Some(EditCrop(0.25, 0, 0.5, 0.5))).editedSize(4000, 3000) shouldBe (2000, 1500)
    EditRecipe.Identity
      .copy(rotate = 90, crop = Some(EditCrop(0, 0, 0.5, 0.3333)))
      .editedSize(4000, 3000) shouldBe (1500, 1333)
    EditRecipe.Identity.copy(crop = Some(EditCrop(0, 0, 0.01, 0.01))).editedSize(20, 20) shouldBe (1, 1)
  }

  test("A crop is normalized by its edges, so one that reaches the frame's edge stays within it") {

    /**
     * Setup:
     *
     * A crop of a 4000-pixel-tall frame from its first row to its last edge (y = 1/4000, h = 3999/4000), whose top and height
     * each round up to 4 decimals; and a crop whose left rounds up.
     *
     * Assertions:
     *
     * Each is accepted. The left and top edges are rounded to 4 decimals, and so are the right and bottom edges (x + w, y + h);
     * the width and height are what lies between them: the first crop's top is 0.0003 and its bottom stays at the frame's edge,
     * so its height is 0.9997. The second's left is 0.1235 and its right 0.6235, so its width is 0.5.
     */
    val edgeTouching =
      EditRecipe.parse(jsonWith("crop" -> ujson.Obj("x" -> 0, "y" -> 1.0 / 4000, "w" -> 1, "h" -> 3999.0 / 4000)))
    edgeTouching.crop shouldBe Some(EditCrop(0, 0.0003, 1, 0.9997))

    EditRecipe.parse(jsonWith("crop" -> ujson.Obj("x" -> 0.12346, "y" -> 0, "w" -> 0.5, "h" -> 1))).crop shouldBe
      Some(EditCrop(0.1235, 0, 0.5, 1))
  }

  test("Two curve points closer than 0.001 in x are refused") {

    /**
     * Setup:
     *
     * A curve with two points 0.0004 apart in x, and one with two points 0.001 apart.
     *
     * Assertions:
     *
     * The first is refused, saying the points must be at least 0.001 apart in x, rather than rounded onto one x; the second is
     * accepted with both points.
     */
    val close = jsonWith("curve" -> ujson.Arr(ujson.Arr(0, 0), ujson.Arr(0.5, 0.4), ujson.Arr(0.5004, 0.6), ujson.Arr(1, 1)))
    refused(close).message should include("0.001")

    val apart = jsonWith("curve" -> ujson.Arr(ujson.Arr(0, 0), ujson.Arr(0.5, 0.4), ujson.Arr(0.501, 0.6), ujson.Arr(1, 1)))
    EditRecipe.parse(apart).curve shouldBe List((0.0, 0.0), (0.5, 0.4), (0.501, 0.6), (1.0, 1.0))
  }

  test("Every Edit of the shared vectors parses as written, and has the edited size they give") {

    /**
     * Setup:
     *
     * The recipes of `test/resources/edit/vectors.json`, which the Edit panel's modules read too: those of its tone and
     * saturation groups, and those of its geometry group with the upright size of each.
     *
     * Assertions:
     *
     * Each parses to an Edit whose JSON is the recipe as written, so it is normalized already. Each geometry recipe's edited size
     * is the one the vectors give for its upright size.
     */
    val vectors = ujson.read(getClass.getResourceAsStream("/edit/vectors.json").readAllBytes())
    val recipes = List("tone", "saturation", "geometry").flatMap(group => vectors(group).arr.map(_("recipe")))
    recipes.size should be > 30
    recipes.foreach(json => EditRecipe.parse(json).toJson shouldBe json)
    vectors("geometry").arr.foreach {
      vector =>
        val Seq(width, height) = vector("upright").arr.map(_.num.toInt).toSeq: @unchecked
        val (editedWidth, editedHeight) = EditRecipe.parse(vector("recipe")).editedSize(width, height)
        ujson.Arr(editedWidth, editedHeight) shouldBe vector("edited")
    }
  }
}
