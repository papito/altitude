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

  /** The asset's `asset_edit` row as stored, None when it has none */
  private def storedRow(assetId: String): Option[Map[String, AnyRef]] = testApp.txManager.asReadOnly {
    query("SELECT * FROM asset_edit WHERE asset_id = ?", assetId).headOption
  }

  test("A first save stores the Edit as version 1, with its edited size and whether it is edited") {

    /**
     * Setup:
     *
     * An imported 150 × 150 still image, never edited.
     *
     * Assertions:
     *
     * Before any save the asset has no Edit (`editOf` is None) and its state is the original's: version 0, the identity, not
     * edited and no edited size. A first save cropping it to half its width, built on version 0, answers version 1, the Edit as
     * sent, edited, the edited size of 75 × 150, not rendered yet and revision 0. The same state is read back, `editOf` now has
     * it, and the asset's one `asset_edit` row holds version 1 and the Edit.
     */
    val asset = testContext.persistAsset()
    val assetId = asset.persistedId

    edits.editOf(asset) shouldBe None
    val original = edits.get(assetId)
    original.editVersion shouldBe 0
    original.recipe shouldBe EditRecipe.Identity
    original.edited shouldBe false
    (original.width, original.height) shouldBe (None, None)

    val halfCrop = EditRecipe.Identity.copy(crop = Some(EditCrop(0, 0, 0.5, 1)), aspect = "free", cropFitted = false)
    val state = edits.save(assetId, base = 0, recipe = halfCrop, account = testContext.user)

    state.editVersion shouldBe 1
    state.recipe shouldBe halfCrop
    state.edited shouldBe true
    (state.width, state.height) shouldBe (Some(75), Some(150))
    state.renderedVersion shouldBe None
    state.rev shouldBe 0

    edits.get(assetId) shouldBe state
    edits.editOf(asset).map(_.editVersion) shouldBe Some(1)
    val row = storedRow(assetId).get
    row("edit_version").toString.toInt shouldBe 1
    EditRecipe.parse(row("recipe").toString) shouldBe halfCrop
  }

  test("The Edit an asset's detail carries reads its revision and its versions in one snapshot") {

    /**
     * Setup:
     *
     * An imported image read once (as Asset detail reads it before it asks for the Edit), then saved and rendered, so its
     * edited images are revision 1.
     *
     * Assertions:
     *
     * `editOf` given the asset as read before the render answers the rendered state: edit version 1, rendered at 1, revision
     * 1, never the revision of the asset as it was read with the versions of the Edit as it is now.
     */
    val asset = testContext.persistAsset()
    val assetId = asset.persistedId
    edits.save(assetId, 0, brighter, testContext.user)
    testApp.service.editRender.render(assetId)

    val state = edits.editOf(asset).get
    (state.editVersion, state.renderedVersion, state.rev) shouldBe (1, Some(1), 1)
  }

  test("Each save replaces the Edit and moves its version on, keeping no earlier Edit") {

    /**
     * Setup:
     *
     * An imported image saved three times, each save built on the version the one before answered: Exposure +0.4, then Contrast
     * +20 with it, then Temperature +15 with both.
     *
     * Assertions:
     *
     * The versions are 1, 2 and 3; the state read back is the third Edit at version 3, and the asset has one `asset_edit` row,
     * holding it, written by the account that saved.
     */
    val assetId = testContext.persistAsset().persistedId

    val versions = List(brighter, contrasty, warmer).foldLeft(List(0)) {
      (versions, recipe) => versions :+ edits.save(assetId, versions.last, recipe, testContext.user).editVersion
    }

    versions shouldBe List(0, 1, 2, 3)
    val state = edits.get(assetId)
    (state.editVersion, state.recipe) shouldBe (3, warmer)
    val rows = testApp.txManager.asReadOnly(query("SELECT * FROM asset_edit WHERE asset_id = ?", assetId))
    rows.size shouldBe 1
    EditRecipe.parse(rows.head("recipe").toString) shouldBe warmer
    rows.head("updated_by_account_id") shouldBe testContext.user.persistedId
  }

  test("A save built on a stale version is a conflict carrying the current state, and writes nothing") {

    /**
     * Setup:
     *
     * An imported image saved twice, so its Edit is at version 2.
     *
     * Assertions:
     *
     * A save built on version 1 is refused with an `EditConflictException` whose state is the current one (version 2 and its
     * Edit), and the stored row is as it was.
     *
     * Edge cases:
     *
     * A save built on a version the Edit has not reached (3) is refused the same way.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.save(assetId, 0, brighter, testContext.user)
    val current = edits.save(assetId, 1, contrasty, testContext.user)
    val before = storedRow(assetId)

    for (stale <- List(1, 3)) withClue(s"built on version $stale: ") {
      intercept[EditConflictException](edits.save(assetId, base = stale, recipe = warmer, testContext.user)).state shouldBe
        current
    }
    storedRow(assetId) shouldBe before
  }

  test("A save equal to the current Edit writes nothing") {

    /**
     * Setup:
     *
     * An imported image saved once; and another imported image, never edited.
     *
     * Assertions:
     *
     * Saving the current Edit again answers the current state, still at version 1, and leaves the row as it was.
     *
     * Edge cases:
     *
     * Saving the identity for an asset never edited creates no Edit for it: version 0, and no row.
     */
    val assetId = testContext.persistAsset().persistedId
    val current = edits.save(assetId, 0, brighter, testContext.user)
    val before = storedRow(assetId)

    edits.save(assetId, base = 1, recipe = brighter, account = testContext.user) shouldBe current
    storedRow(assetId) shouldBe before

    val untouched = testContext.persistAsset().persistedId
    edits.save(untouched, base = 0, recipe = EditRecipe.Identity, account = testContext.user).editVersion shouldBe 0
    storedRow(untouched) shouldBe None
  }

  test("Revert to original saves the identity as the next version, and the Edit before it can be saved again") {

    /**
     * Setup:
     *
     * An imported image saved twice, the second turning it a quarter.
     *
     * Assertions:
     *
     * Saving the identity is version 3: not edited, with the original's size. Saving the second Edit again, as the browser's Undo
     * of the revert does, is version 4, edited and turned again.
     */
    val asset = testContext.persistAsset()
    val assetId = asset.persistedId
    edits.save(assetId, 0, brighter, testContext.user)
    val turned = contrasty.copy(rotate = 90)
    edits.save(assetId, 1, turned, testContext.user)

    val reverted = edits.save(assetId, base = 2, recipe = EditRecipe.Identity, account = testContext.user)
    reverted.editVersion shouldBe 3
    reverted.edited shouldBe false
    reverted.recipe shouldBe EditRecipe.Identity
    (reverted.width, reverted.height) shouldBe (Some(asset.width), Some(asset.height))

    val undone = edits.save(assetId, base = 3, recipe = turned, account = testContext.user)
    (undone.editVersion, undone.edited, undone.recipe) shouldBe (4, true, turned)
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
     * A save for each of the first three is refused with an `IllegalOperationException`, and none of them gets an Edit.
     *
     * Edge cases:
     *
     * The still GIF, a format with animated relatives, is saved.
     */
    val animated = imported("images/animated.gif")
    animated.durationMs should not be empty
    val video = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(
        IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.black(1))).toFile)))
    val recycled = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    for (asset <- List(animated, video, recycled)) withClue(s"${asset.assetType.mediaType} ${asset.fileName}: ") {
      intercept[IllegalOperationException](edits.save(asset.persistedId, 0, brighter, testContext.user))
      storedRow(asset.persistedId) shouldBe None
    }

    val still = imported("images/2.gif")
    edits.save(still.persistedId, 0, brighter, testContext.user).editVersion shouldBe 1
  }

  test("The edited size is measured in the original turned upright by its EXIF orientation") {

    /**
     * Setup:
     *
     * An imported JPEG stored 640 × 724 and tagged with EXIF orientation 6 (a quarter turn clockwise), so it shows 724 × 640.
     *
     * Assertions:
     *
     * Its upright size is 724 × 640. A save with Exposure alone has the edited size 724 × 640, and one that also turns it a
     * quarter has 640 × 724.
     */
    val asset = imported("images/exif/orientation-6.jpg")
    (asset.width, asset.height) shouldBe (640, 724)
    testApp.service.asset.uprightSize(asset) shouldBe (724, 640)

    val state = edits.save(asset.persistedId, 0, brighter, testContext.user)
    (state.width, state.height) shouldBe (Some(724), Some(640))
    val turned = edits.save(asset.persistedId, 1, brighter.copy(rotate = 90), testContext.user)
    (turned.width, turned.height) shouldBe (Some(640), Some(724))
  }

  test("Another Library's asset is not found") {

    /**
     * Setup:
     *
     * An imported image saved once in a second repository; the context in the first.
     *
     * Assertions:
     *
     * Reading its Edit, its render status and saving it are each not found, as for an unknown asset, and its Edit is unchanged.
     */
    val first = testContext.repository
    val second = testContext.persistRepository()
    switchContextRepo(second)
    val theirs = testContext.persistAsset(repository = Some(second)).persistedId
    edits.save(theirs, 0, brighter, testContext.user)
    val before = storedRow(theirs)
    switchContextRepo(first)

    intercept[NotFoundException](edits.get(theirs))
    intercept[NotFoundException](edits.status(theirs))
    intercept[NotFoundException](edits.save(theirs, 1, contrasty, testContext.user))
    storedRow(theirs) shouldBe before
  }

  test("Two saves built on the same version take turns, and the second is a conflict") {

    /**
     * Setup:
     *
     * Two imported images: one never edited, and one saved once. For each, two transactions that each save an Edit built on its
     * version, the first holding its transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second waits for the first (for the asset's row, which every save locks first, on PostgreSQL; for the one write
     * connection on SQLite) and is then refused with a conflict carrying the first's Edit, which is the one stored, one edit
     * version on.
     *
     * Edge cases:
     *
     * The image never edited has no Edit row to lock: the asset's lock alone makes the second wait for the first's insert.
     */
    val fresh = testContext.persistAsset().persistedId
    val saved = testContext.persistAsset().persistedId
    edits.save(saved, 0, brighter, testContext.user)

    for ((assetId, base) <- List(fresh -> 0, saved -> 1)) withClue(s"from version $base: ") {
      contend(
        first = edits.save(assetId, base, warmer, testContext.user): Unit,
        second = {
          val conflict =
            intercept[EditConflictException](edits.save(assetId, base, contrasty.copy(grain = 5), testContext.user))
          conflict.state.recipe shouldBe warmer
        }: Unit
      )
      val stored = edits.get(assetId)
      stored.editVersion shouldBe base + 1
      stored.recipe shouldBe warmer
    }
  }

  test("Purging an asset removes its Edit and its edited images") {

    /**
     * Setup:
     *
     * Two imported images, each saved and rendered; one of them recycled and run through the purge pipeline, with temporary files
     * beside its edited images, as a write cut off by a crash leaves them.
     *
     * Assertions:
     *
     * The purged asset has no Edit row, no edited Preview, no Display image and no temporary file left. The other keeps its row
     * and its two edited images.
     */
    val purged = testContext.persistAsset().persistedId
    val kept = testContext.persistAsset().persistedId
    for (assetId <- List(purged, kept)) {
      edits.save(assetId, 0, brighter, testContext.user)
      testApp.service.editRender.render(assetId)
    }
    def editedFiles(assetId: String): List[String] =
      for {
        kind <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)
        if java.nio.file.Files.exists(testApp.service.fileStore.editedImagePath(assetId, kind))
      } yield kind
    editedFiles(purged) shouldBe List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)
    val leftovers = for (kind <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)) yield {
      val path = testApp.service.fileStore.editedImagePath(purged, kind)
      val leftover = path.resolveSibling(s".${path.getFileName}.tmp")
      java.nio.file.Files.write(leftover, Array[Byte](1))
    }
    testApp.service.library.recycleAssets(Set(purged))
    val toPurge = testApp.service.asset.getById(purged)

    val pipelineContext = PipelineContext(testContext.repository, Some(testContext.user))
    Await.result(
      testApp.service.purgePipeline.run(Source.single((toPurge, pipelineContext)), VoidAssetSink()),
      Duration.Inf): Unit

    storedRow(purged) shouldBe None
    editedFiles(purged) shouldBe Nil
    leftovers.filter(java.nio.file.Files.exists(_)) shouldBe Nil
    storedRow(kept) should not be empty
    editedFiles(kept) shouldBe List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)
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
     * A save for the first is refused with an `IllegalOperationException` saying its original is missing, and it gets no Edit. A
     * save for the second is stored, its orientation taken as upright, so its edited size is the stored 150 × 150.
     */
    val gone = testContext.persistAsset().persistedId
    java.nio.file.Files.delete(testApp.service.fileStore.assetFile(gone))
    val unreadable = testContext.persistAsset().persistedId
    java.nio.file.Files.write(testApp.service.fileStore.assetFile(unreadable), "not an image".getBytes): Unit

    intercept[IllegalOperationException](edits.save(gone, 0, brighter, testContext.user)).getMessage shouldBe
      Const.Msg.Err.ORIGINAL_MISSING
    storedRow(gone) shouldBe None

    val state = edits.save(unreadable, 0, brighter, testContext.user)
    (state.width, state.height) shouldBe (Some(150), Some(150))
  }
}
