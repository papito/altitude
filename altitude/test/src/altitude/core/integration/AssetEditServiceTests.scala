package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.EditConflictException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.models.Asset
import altitude.core.models.EditCrop
import altitude.core.models.EditRecipe
import altitude.core.models.EditState
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.sinks.VoidAssetSink

@DoNotDiscover class AssetEditServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def edits = testApp.service.assetEdit

  private val brighter = EditRecipe.Identity.copy(exposure = 0.4)
  private val contrasty = brighter.copy(contrast = 20)
  private val warmer = contrasty.copy(temperature = 15)

  /** An asset imported from a fixture file under `import/`, through the whole pipeline */
  private def imported(relPath: String): Asset =
    testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.getImportAsset(relPath)))

  /** The seq and label of each step, newest first, as the History lists them */
  private def history(state: EditState): List[(Int, String)] = state.steps.map(step => step.seq -> step.label)

  /** The asset's Edit rows, as stored: its `asset_edit` row (None when it has none) and its step seqs, in order */
  private def storedRows(assetId: String): (Option[Map[String, AnyRef]], List[Int]) = testApp.txManager.asReadOnly {
    (
      query("SELECT * FROM asset_edit WHERE asset_id = ?", assetId).headOption,
      query("SELECT seq FROM asset_edit_step WHERE asset_id = ? ORDER BY seq", assetId).map(_("seq").toString.toInt))
  }

  test("A first step stores the Edit at step 1, with its label, its edited size and whether it is edited") {

    /**
     * Setup:
     *
     * An imported 150 × 150 still image, never edited.
     *
     * Assertions:
     *
     * Before any step the asset has no Edit (`headOf` is None) and its state is the Original: head 0, the identity, not edited,
     * no edited size, and a History of the Original alone. A first step cropping it to half its width answers head 1, the Edit as
     * sent, edited, the edited size of 75 × 150, not rendered yet, and a History of step 1, "Crop", by the account that made it,
     * over the Original. The same state is read back, and `headOf` now has it.
     */
    val asset = testContext.persistAsset()
    val assetId = asset.persistedId

    edits.headOf(asset) shouldBe None
    val original = edits.get(assetId)
    original.head shouldBe 0
    original.recipe shouldBe EditRecipe.Identity
    original.edited shouldBe false
    (original.width, original.height) shouldBe (None, None)
    history(original) shouldBe List(0 -> "Original")

    val halfCrop = EditRecipe.Identity.copy(crop = Some(EditCrop(0, 0, 0.5, 1)), aspect = "free", cropFitted = false)
    val state = edits.addStep(assetId, base = 0, recipe = halfCrop, account = testContext.user)

    state.head shouldBe 1
    state.recipe shouldBe halfCrop
    state.edited shouldBe true
    (state.width, state.height) shouldBe (Some(75), Some(150))
    state.rendered shouldBe None
    state.rev shouldBe 0
    history(state) shouldBe List(1 -> "Crop", 0 -> "Original")
    state.steps.head.author shouldBe Some(testContext.user.name)
    state.steps.head.createdAt should not be empty

    edits.get(assetId) shouldBe state
    edits.headOf(asset).map(_.head) shouldBe Some(1)
    storedRows(assetId)._2 shouldBe List(1)
  }

  test("Undo and Redo move the head both ways, and step 0 is the original") {

    /**
     * Setup:
     *
     * An imported image with two steps: Exposure +0.4, then Contrast +20.
     *
     * Assertions:
     *
     * Moving the head from 2 to 1 (Undo) shows the first step's Edit, still edited; from 1 to 0 shows the Original: the identity,
     * not edited. Moving it from 0 to 2 (Redo, or a jump from the History) shows the second step's Edit again. The steps stay as
     * they are throughout.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    edits.addStep(assetId, 1, contrasty, testContext.user)

    val undone = edits.moveHead(assetId, base = 2, seq = 1, account = testContext.user)
    undone.head shouldBe 1
    undone.recipe shouldBe brighter
    undone.edited shouldBe true

    val original = edits.moveHead(assetId, base = 1, seq = 0, account = testContext.user)
    original.head shouldBe 0
    original.recipe shouldBe EditRecipe.Identity
    original.edited shouldBe false

    val redone = edits.moveHead(assetId, base = 0, seq = 2, account = testContext.user)
    redone.head shouldBe 2
    redone.recipe shouldBe contrasty
    history(redone) shouldBe List(2 -> "Contrast +20", 1 -> "Exposure +0.40", 0 -> "Original")
  }

  test("A change after an undo drops the steps after the head") {

    /**
     * Setup:
     *
     * An imported image with three steps, its head moved back to step 1.
     *
     * Assertions:
     *
     * A new step from step 1 is step 2, holding the new Edit: the old steps 2 and 3 are gone from the History and from the table,
     * and moving the head to 3 is not found.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    edits.addStep(assetId, 1, contrasty, testContext.user)
    edits.addStep(assetId, 2, warmer, testContext.user)
    edits.moveHead(assetId, 3, 1, testContext.user)

    val cooler = brighter.copy(temperature = -30)
    val state = edits.addStep(assetId, base = 1, recipe = cooler, account = testContext.user)

    state.head shouldBe 2
    state.recipe shouldBe cooler
    history(state) shouldBe List(2 -> "Temperature −30", 1 -> "Exposure +0.40", 0 -> "Original")
    storedRows(assetId)._2 shouldBe List(1, 2)
    intercept[NotFoundException](edits.moveHead(assetId, base = 2, seq = 3, account = testContext.user))
  }

  test("The step past the cap drops the oldest, and the Original stays reachable") {

    /**
     * Setup:
     *
     * An imported image given one step more than the cap (`Const.Edit.MAX_STEPS`, 100), each a different Contrast.
     *
     * Assertions:
     *
     * The History holds the newest 100 steps, 2 to 101, over the Original; step 1 is gone, so moving the head to it is not found,
     * while moving it to 0 shows the Original.
     */
    val assetId = testContext.persistAsset().persistedId
    val steps = Const.Edit.MAX_STEPS + 1
    val last = (1 to steps).foldLeft(0) {
      (base, n) => edits.addStep(assetId, base, EditRecipe.Identity.copy(contrast = n - 100), testContext.user).head
    }

    last shouldBe steps
    val state = edits.get(assetId)
    state.steps.map(_.seq) shouldBe (steps to 2 by -1).toList :+ 0
    storedRows(assetId)._2.size shouldBe Const.Edit.MAX_STEPS
    intercept[NotFoundException](edits.moveHead(assetId, base = steps, seq = 1, account = testContext.user))
    edits.moveHead(assetId, base = steps, seq = 0, account = testContext.user).recipe shouldBe EditRecipe.Identity
  }

  test("A step or a head move built on a stale head is a conflict carrying the current state, and writes nothing") {

    /**
     * Setup:
     *
     * An imported image with two steps, its head at 2.
     *
     * Assertions:
     *
     * A step built on head 1 is refused with an `EditConflictException` whose state is the current one (head 2, its Edit and its
     * History), and so is a head move built on head 1. Neither writes: the head, the steps and the row are as they were.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    val current = edits.addStep(assetId, 1, contrasty, testContext.user)
    val before = storedRows(assetId)

    val stepConflict = intercept[EditConflictException](edits.addStep(assetId, base = 1, recipe = warmer, testContext.user))
    stepConflict.state shouldBe current

    val moveConflict = intercept[EditConflictException](edits.moveHead(assetId, base = 1, seq = 0, testContext.user))
    moveConflict.state shouldBe current

    storedRows(assetId) shouldBe before
  }

  test("A step equal to the head writes nothing") {

    /**
     * Setup:
     *
     * An imported image with one step; and another imported image, never edited.
     *
     * Assertions:
     *
     * Sending the head's own Edit again answers the current state and writes nothing: no step is added and the Edit's row is
     * unchanged.
     *
     * Edge cases:
     *
     * Sending the identity to an asset never edited creates no Edit for it, and moving its head to 0 neither.
     */
    val assetId = testContext.persistAsset().persistedId
    val current = edits.addStep(assetId, 0, brighter, testContext.user)
    val before = storedRows(assetId)

    edits.addStep(assetId, base = 1, recipe = brighter, account = testContext.user) shouldBe current
    storedRows(assetId) shouldBe before

    val untouched = testContext.persistAsset().persistedId
    edits.addStep(untouched, base = 0, recipe = EditRecipe.Identity, account = testContext.user).head shouldBe 0
    edits.moveHead(untouched, base = 0, seq = 0, account = testContext.user).head shouldBe 0
    storedRows(untouched) shouldBe (None, Nil)
  }

  test("Revert to original is a step of the identity, which can be undone") {

    /**
     * Setup:
     *
     * An imported image with two steps.
     *
     * Assertions:
     *
     * A step of the identity is step 3, "Revert to original", not edited, with the original's size. Undoing it shows the second
     * step, edited again.
     */
    val asset = testContext.persistAsset()
    val assetId = asset.persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    edits.addStep(assetId, 1, contrasty.copy(rotate = 90), testContext.user)

    val reverted = edits.addStep(assetId, base = 2, recipe = EditRecipe.Identity, account = testContext.user)
    reverted.head shouldBe 3
    reverted.edited shouldBe false
    reverted.steps.head.label shouldBe "Revert to original"
    (reverted.width, reverted.height) shouldBe (Some(asset.width), Some(asset.height))

    edits.moveHead(assetId, base = 3, seq = 2, account = testContext.user).edited shouldBe true
  }

  test("An animated image, a Video and an asset in the trash cannot be edited") {

    /**
     * Setup:
     *
     * An imported animated GIF of two frames, an imported Video, and an imported image in the trash, each never edited; and an
     * imported still GIF.
     *
     * Assertions:
     *
     * A step for each of the first three is refused with an `IllegalOperationException`, and so is a head move for the image in
     * the trash; none of them gets an Edit.
     *
     * Edge cases:
     *
     * The still GIF, a format with animated relatives, takes a step.
     */
    val animated = imported("images/animated.gif")
    animated.durationMs should not be empty
    val video = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(
        IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.black(1))).toFile)))
    val recycled = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    for (asset <- List(animated, video, recycled)) withClue(s"${asset.assetType.mediaType} ${asset.fileName}: ") {
      intercept[IllegalOperationException](edits.addStep(asset.persistedId, 0, brighter, testContext.user))
      storedRows(asset.persistedId) shouldBe (None, Nil)
    }
    intercept[IllegalOperationException](edits.moveHead(recycled.persistedId, 0, 0, testContext.user))

    val still = imported("images/2.gif")
    edits.addStep(still.persistedId, 0, brighter, testContext.user).head shouldBe 1
  }

  test("The edited size is measured in the original turned upright by its EXIF orientation") {

    /**
     * Setup:
     *
     * An imported JPEG stored 640 × 724 and tagged with EXIF orientation 6 (a quarter turn clockwise), so it shows 724 × 640.
     *
     * Assertions:
     *
     * Its upright size is 724 × 640. A step with Exposure alone has the edited size 724 × 640, and one that also turns it a
     * quarter has 640 × 724.
     */
    val asset = imported("images/exif/orientation-6.jpg")
    (asset.width, asset.height) shouldBe (640, 724)
    testApp.service.asset.uprightSize(asset) shouldBe (724, 640)

    val state = edits.addStep(asset.persistedId, 0, brighter, testContext.user)
    (state.width, state.height) shouldBe (Some(724), Some(640))
    val turned = edits.addStep(asset.persistedId, 1, brighter.copy(rotate = 90), testContext.user)
    (turned.width, turned.height) shouldBe (Some(640), Some(724))
  }

  test("Another Library's asset is not found") {

    /**
     * Setup:
     *
     * An imported image with a step in a second repository; the context in the first.
     *
     * Assertions:
     *
     * Reading its Edit, adding a step and moving its head are each not found, as for an unknown asset, and its Edit is unchanged.
     */
    val first = testContext.repository
    val second = testContext.persistRepository()
    switchContextRepo(second)
    val theirs = testContext.persistAsset(repository = Some(second)).persistedId
    edits.addStep(theirs, 0, brighter, testContext.user)
    val before = storedRows(theirs)
    switchContextRepo(first)

    intercept[NotFoundException](edits.get(theirs))
    intercept[NotFoundException](edits.addStep(theirs, 1, contrasty, testContext.user))
    intercept[NotFoundException](edits.moveHead(theirs, 1, 0, testContext.user))
    storedRows(theirs) shouldBe before
  }

  test("Two steps built on the same head take turns, and the second is a conflict") {

    /**
     * Setup:
     *
     * Two imported images: one never edited, and one with a step. For each, two transactions that each add a step built on its
     * head, the first holding its transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second waits for the first (for the Edit's row on PostgreSQL, for the one write connection on SQLite) and is then
     * refused with a conflict carrying the first's step, which is the one stored.
     *
     * Edge cases:
     *
     * The image never edited has no row to lock until the first creates it: the second waits for that row's insert.
     */
    val fresh = testContext.persistAsset().persistedId
    val stepped = testContext.persistAsset().persistedId
    edits.addStep(stepped, 0, brighter, testContext.user)

    for ((assetId, base) <- List(fresh -> 0, stepped -> 1)) withClue(s"from head $base: ") {
      contend(
        first = edits.addStep(assetId, base, warmer, testContext.user): Unit,
        second = {
          val conflict =
            intercept[EditConflictException](edits.addStep(assetId, base, contrasty.copy(grain = 5), testContext.user))
          conflict.state.recipe shouldBe warmer
        }: Unit
      )
      val stored = edits.get(assetId)
      stored.head shouldBe base + 1
      stored.recipe shouldBe warmer
    }
  }

  test("Purging an asset removes its Edit") {

    /**
     * Setup:
     *
     * Two imported images, each with two steps; one of them recycled and run through the purge pipeline.
     *
     * Assertions:
     *
     * The purged asset has no Edit row and no step left. The other keeps both.
     */
    val purged = testContext.persistAsset().persistedId
    val kept = testContext.persistAsset().persistedId
    for (assetId <- List(purged, kept)) {
      edits.addStep(assetId, 0, brighter, testContext.user)
      edits.addStep(assetId, 1, contrasty, testContext.user)
    }
    testApp.service.library.recycleAssets(Set(purged))
    val toPurge = testApp.service.asset.getById(purged)

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    Await.result(
      testApp.service.purgePipeline.run(Source.single((toPurge, pipelineContext)), VoidAssetSink()),
      Duration.Inf): Unit

    storedRows(purged) shouldBe (None, Nil)
    storedRows(kept)._2 shouldBe List(1, 2)
  }

  test("An image whose original is gone is refused, and one whose metadata cannot be read is taken as upright") {

    /**
     * Setup:
     *
     * Two imported 150 × 150 images: the stored original of the first deleted, and that of the second overwritten with bytes no
     * metadata reader takes.
     *
     * Assertions:
     *
     * A step for the first is refused with an `IllegalOperationException` saying its original is missing, and it gets no Edit. A
     * step for the second is saved, its orientation taken as upright, so its edited size is the stored 150 × 150.
     */
    val gone = testContext.persistAsset().persistedId
    java.nio.file.Files.delete(testApp.service.fileStore.assetFile(gone))
    val unreadable = testContext.persistAsset().persistedId
    java.nio.file.Files.write(testApp.service.fileStore.assetFile(unreadable), "not an image".getBytes): Unit

    intercept[IllegalOperationException](edits.addStep(gone, 0, brighter, testContext.user)).getMessage shouldBe
      Const.Msg.Err.ORIGINAL_MISSING
    storedRows(gone) shouldBe (None, Nil)

    val state = edits.addStep(unreadable, 0, brighter, testContext.user)
    (state.width, state.height) shouldBe (Some(150), Some(150))
  }
}
