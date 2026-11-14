package altitude.core.integration

import java.nio.file.Files
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.EditCrop
import altitude.core.models.EditRecipe
import altitude.core.service.EditRenderService
import altitude.core.service.EditRenderService.Outcome

@DoNotDiscover class EditRenderServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def edits = testApp.service.assetEdit
  private def renders = testApp.service.editRender

  private val brighter = EditRecipe.Identity.copy(exposure = 0.4)
  private val warmer = brighter.copy(temperature = 15)
  private val contrasty = warmer.copy(contrast = 20)
  private val halfCrop = EditRecipe.Identity.copy(crop = Some(EditCrop(0, 0, 0.5, 1)), aspect = "free", cropFitted = false)

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  /**
   * Runs `f` with the render delay at an hour, so a render the test runs itself is never raced by the one a change queued; the
   * delay is put back after
   */
  private def withDebounce[T](f: => T): T = {
    renders.debounce = scala.concurrent.duration.DurationInt(1).hour
    try f
    finally renders.debounce = EditRenderService.DEBOUNCE
  }

  /** The asset's `last_rev`, as stored */
  private def lastRev(assetId: String): Int = testApp.txManager.asReadOnly {
    query("SELECT last_rev FROM asset_edit WHERE asset_id = ?", assetId).head("last_rev").toString.toInt
  }

  /** The kinds of the asset's edited images on disk: its edited Preview and its Display image */
  private def editedImages(assetId: String): Set[String] =
    Set(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)
      .filter(kind => Files.isRegularFile(testApp.service.fileStore.editedImagePath(assetId, kind)))

  private val BOTH = Set(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)

  /** The bytes of the asset's edited image of `kind` on disk */
  private def bytesOf(assetId: String, kind: String): Array[Byte] =
    Files.readAllBytes(testApp.service.fileStore.editedImagePath(assetId, kind))

  /** The temporary files a render writes its edited images to before it publishes them, of both kinds */
  private def stagedFiles(assetId: String): List[java.nio.file.Path] =
    List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY).map {
      kind =>
        val path = testApp.service.fileStore.editedImagePath(assetId, kind)
        path.resolveSibling(s".${path.getFileName}.tmp")
    }

  /** An edited image, decoded as stored */
  private def decoded(assetId: String, kind: String): Mat =
    Imgcodecs.imdecode(new MatOfByte(bytesOf(assetId, kind)*), Imgcodecs.IMREAD_UNCHANGED)

  /** An asset imported through the whole pipeline from a gray JPEG of `width` × `height` */
  private def importedJpeg(width: Int, height: Int): Asset =
    val image = new Mat(height, width, CvType.CV_8UC3, new Scalar(90, 120, 150))
    val bytes = new MatOfByte()
    Imgcodecs.imencode(".jpg", image, bytes)
    val staged = testApp.service.staging.stage(bytes.toArray)
    testApp.service.library.addAsset(AssetWithData(testContext.makeAsset(), staged))

  test("A render of an edited Edit stores both edited images under the next revision, replacing the ones before") {

    /**
     * Setup:
     *
     * An imported image saved once and rendered; then saved again and rendered.
     *
     * Assertions:
     *
     * The first render stores the edited Preview and the Display image as revision 1, which the asset's `edit_rev` and the Edit's
     * state (`rev`) name, with version 1 rendered. The second stores revision 2, names it, has version 2 rendered, and the asset
     * still has one file of each kind, now holding the second render's bytes.
     *
     * Edge cases:
     *
     * A render of an Edit rendered already stores nothing: the state stays as it is.
     */
    withDebounce {
      val asset = testContext.persistAsset()
      val assetId = asset.persistedId

      edits.save(assetId, 0, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)
      val first = edits.status(assetId)
      (first.editVersion, first.renderedVersion, first.rev) shouldBe (1, Some(1), 1)
      testApp.service.asset.getById(assetId).editRev shouldBe 1
      editedImages(assetId) shouldBe BOTH
      val firstPreview = bytesOf(assetId, Const.DataStore.PREVIEW)

      edits.save(assetId, 1, warmer, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(2, 2)
      val second = edits.status(assetId)
      (second.editVersion, second.renderedVersion, second.rev) shouldBe (2, Some(2), 2)
      editedImages(assetId) shouldBe BOTH
      bytesOf(assetId, Const.DataStore.PREVIEW).sameElements(firstPreview) shouldBe false

      renders.render(assetId) shouldBe Outcome.UpToDate
      edits.status(assetId).rev shouldBe 2
    }
  }

  test("A render of the identity sets the revision to 0 and deletes the edited images, and no revision is used twice") {

    /**
     * Setup:
     *
     * An imported image saved and rendered; then reverted to the identity and rendered; then saved with its Edit again and
     * rendered.
     *
     * Assertions:
     *
     * The identity's render stores nothing, sets `edit_rev` to 0 with version 2 rendered, and deletes both edited images, while
     * `last_rev` keeps 1. The Edit saved again is rendered as revision 2, never 1 again, so a URL naming revision 1 is never
     * built for the new images.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.save(assetId, 0, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)

      edits.save(assetId, 1, EditRecipe.Identity, testContext.user)
      renders.render(assetId) shouldBe Outcome.Original(2)
      val original = edits.status(assetId)
      (original.editVersion, original.renderedVersion, original.rev) shouldBe (2, Some(2), 0)
      testApp.service.asset.getById(assetId).editRev shouldBe 0
      editedImages(assetId) shouldBe Set.empty
      lastRev(assetId) shouldBe 1

      edits.save(assetId, 2, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(3, 2)
      editedImages(assetId) shouldBe BOTH
    }
  }

  test("The identity's render leaves the edited images a later render stored before its delete") {

    /**
     * Setup:
     *
     * An imported image saved and rendered, then reverted to the identity; the identity's render, between its commit and its
     * delete of the edited images, sees the Edit saved again and that save rendered.
     *
     * Assertions:
     *
     * The identity's render is `Original`, yet the later render's images stay: its delete runs under the asset's lock and only
     * while the asset shows its original, which it no longer does. The asset shows revision 2.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.save(assetId, 0, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)
      edits.save(assetId, 1, EditRecipe.Identity, testContext.user)

      renders.render(
        assetId,
        beforeDelete = () => {
          edits.save(assetId, 2, warmer, testContext.user)
          renders.render(assetId) shouldBe Outcome.Rendered(3, 2): Unit
        }) shouldBe Outcome.Original(2)

      editedImages(assetId) shouldBe BOTH
      edits.status(assetId).rev shouldBe 2
    }
  }

  test("An Edit saved during a render throws the render away and queues the asset again") {

    /**
     * Setup:
     *
     * An imported image saved once, whose render sees a second save between its read and its write.
     *
     * Assertions:
     *
     * The render is discarded: nothing is stored, no temporary file of its images is left, and edit version 1 is never recorded
     * rendered. The asset is queued again, and the queue renders edit version 2 as revision 1.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce(edits.save(assetId, 0, brighter, testContext.user))

    renders.render(assetId, beforeWrite = () => edits.save(assetId, 1, warmer, testContext.user): Unit) shouldBe
      Outcome.Discarded
    editedImages(assetId) shouldBe Set.empty
    stagedFiles(assetId).filter(Files.exists(_)) shouldBe Nil
    edits.status(assetId).renderedVersion shouldBe None

    eventually {
      val state = edits.status(assetId)
      (state.editVersion, state.renderedVersion, state.rev) shouldBe (2, Some(2), 1)
    }
    editedImages(assetId) shouldBe BOTH
  }

  test("A render's write waits for a save under way, and then stores nothing of the Edit before it") {

    /**
     * Setup:
     *
     * An imported image saved twice. A transaction saving a third Edit, held open until a render of the asset, whose write locks
     * the asset and the Edit, waits for it (for the asset's row on PostgreSQL, for the one write connection on SQLite).
     *
     * Assertions:
     *
     * The render never stores version 2 as rendered: on PostgreSQL, where it read version 2 before the save committed, it is
     * discarded once its locks are granted; on SQLite, where it waits before it reads, it renders version 3.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.save(assetId, 0, brighter, testContext.user)
      edits.save(assetId, 1, warmer, testContext.user)

      val outcome = new java.util.concurrent.atomic.AtomicReference[Outcome]()
      contend(
        first = edits.save(assetId, 2, contrasty, testContext.user): Unit,
        second = outcome.set(renders.render(assetId))
      )
      outcome.get should (be(Outcome.Discarded).or(be(Outcome.Rendered(3, 1))))
      edits.status(assetId).renderedVersion should not be Some(2)
    }
  }

  /**
   * Runs `firstLocks` in a transaction on a thread of its own, then `second` in a transaction on another, and once `second` waits
   * for the first (for a lock on PostgreSQL, for the one write connection on SQLite) or has ended, `firstThen` in the first's
   * transaction, which then commits. What either throws, a deadlock the database broke included, is thrown here.
   */
  private def interleave(firstLocks: => Unit, second: => Unit, firstThen: => Unit): Unit = {
    val held = new java.util.concurrent.CountDownLatch(1)
    val go = new java.util.concurrent.CountDownLatch(1)
    val secondBackend = new java.util.concurrent.atomic.AtomicReference[Option[AnyRef]](None)
    val (firstThread, firstFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        firstLocks
        held.countDown()
        go.await(30, java.util.concurrent.TimeUnit.SECONDS): Unit
        firstThen
      }
    }
    try {
      held.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      val (secondThread, secondFailure) = onAnotherThread {
        testApp.txManager.withTransaction {
          if (testApp.dataSourceType == Const.DbEngineName.POSTGRES)
            secondBackend.set(Some(query("SELECT pg_backend_pid() AS pid").head("pid")))
          second
        }
      }
      def secondIsWaiting: Boolean = testApp.dataSourceType match {
        case Const.DbEngineName.POSTGRES =>
          secondBackend.get.exists(
            pid =>
              testApp.txManager.asReadOnly {
                query("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid).exists(_("wait_event_type") == "Lock")
              })
        case _ => secondThread.getState == Thread.State.WAITING || secondThread.getState == Thread.State.TIMED_WAITING
      }
      eventually((secondIsWaiting || !secondThread.isAlive) shouldBe true)
      go.countDown()
      secondThread.join()
      firstThread.join()
      firstFailure.get.foreach(throw _)
      secondFailure.get.foreach(throw _)
    } finally {
      go.countDown()
      firstThread.join()
    }
  }

  test("A render's write and a save take the asset's and the Edit's locks in one order, so neither deadlocks") {

    /**
     * Setup:
     *
     * An imported image saved once and rendered. A transaction that takes the locks a render's write takes, in its order: the
     * asset's first, then, once a second save is waiting for it or done, the Edit's.
     *
     * Assertions:
     *
     * Both commit, one after the other, and the second save is the Edit's version 2. On PostgreSQL a save that locked the Edit
     * before the asset would hold the Edit while waiting for the asset, which the render's write holds while waiting for the
     * Edit: a deadlock the database breaks by failing one of them.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce {
      edits.save(assetId, 0, brighter, testContext.user)
      renders.render(assetId)
    }

    interleave(
      firstLocks = testApp.DAO.asset.lockUnpurged(assetId) shouldBe true,
      second = edits.save(assetId, 1, warmer, testContext.user): Unit,
      firstThen = testApp.DAO.assetEdit.getForUpdate(assetId).map(_.editVersion) should not be empty
    )
    edits.get(assetId).editVersion shouldBe 2
  }

  test("Saves in quick succession are rendered once, at the version they end on") {

    /**
     * Setup:
     *
     * An imported image saved twice at once, each save queueing a render.
     *
     * Assertions:
     *
     * The queue renders once, after the last save: version 2 as revision 1, version 1 never rendered.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.save(assetId, 0, brighter, testContext.user)
    edits.save(assetId, 1, warmer, testContext.user)

    eventually {
      val state = edits.status(assetId)
      (state.editVersion, state.renderedVersion, state.rev) shouldBe (2, Some(2), 1)
    }
    lastRev(assetId) shouldBe 1
  }

  test("The startup queues every Edit whose version is not rendered") {

    /**
     * Setup:
     *
     * An imported image saved once, rendered by the queue; then its rendered version cleared by hand, as a stop before its render
     * leaves it; then the startup's requeue.
     *
     * Assertions:
     *
     * The requeue renders the Edit again, as the next revision.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.save(assetId, 0, brighter, testContext.user)
    eventually(edits.status(assetId).rev shouldBe 1)

    testApp.txManager.withTransaction(update("UPDATE asset_edit SET rendered_version = NULL WHERE asset_id = ?", assetId))
    renders.requeuePendingRenders()

    eventually {
      val state = edits.status(assetId)
      (state.renderedVersion, state.rev) shouldBe (Some(1), 2)
    }
  }

  test("The Display image is the edited size up to a 3840 px long edge, and the edited Preview a letterboxed 200 px square") {

    /**
     * Setup:
     *
     * An imported 150 × 150 image cropped to its left half, and an imported 4000 × 300 image with an Exposure change, each
     * rendered.
     *
     * Assertions:
     *
     * The first's Display image is its edited size, 75 × 150; its edited Preview is 200 × 200, the image 100 px wide in the
     * middle and transparent on either side. The second's Display image is 3840 × 288, its long edge brought down to the cap.
     */
    withDebounce {
      val small = testContext.persistAsset().persistedId
      edits.save(small, 0, halfCrop, testContext.user)
      renders.render(small) shouldBe Outcome.Rendered(1, 1)

      val display = decoded(small, Const.DataStore.DISPLAY)
      (display.width, display.height) shouldBe (75, 150)
      val preview = decoded(small, Const.DataStore.PREVIEW)
      (preview.width, preview.height, preview.channels) shouldBe (200, 200, 4)
      preview.get(100, 10)(3) shouldBe 0.0
      preview.get(100, 190)(3) shouldBe 0.0

      val large = importedJpeg(4000, 300).persistedId
      edits.save(large, 0, brighter, testContext.user)
      renders.render(large) shouldBe Outcome.Rendered(1, 1)
      val wide = decoded(large, Const.DataStore.DISPLAY)
      (wide.width, wide.height) shouldBe (3840, 288)
    }
  }

  test("A render of a purged asset stores nothing, and an original gone or undecodable is recorded rendered as the original") {

    /**
     * Setup:
     *
     * Three imported images saved once each: one recycled and purged; one, rendered once, then saved again and its stored
     * original deleted; and one whose stored original is overwritten with bytes no decoder reads.
     *
     * Assertions:
     *
     * The purged asset's render finds it gone and stores nothing. The others cannot be rendered: each is recorded rendered at its
     * version with revision 0, so it shows its original, keeps no edited image (the first render's are deleted), and is not
     * queued again at the next start.
     */
    val purged = testContext.persistAsset().persistedId
    val gone = testContext.persistAsset().persistedId
    val corrupt = testContext.persistAsset().persistedId
    withDebounce {
      for (assetId <- List(purged, gone, corrupt)) edits.save(assetId, 0, brighter, testContext.user)
      renders.render(gone) shouldBe Outcome.Rendered(1, 1)

      testApp.service.library.recycleAssets(Set(purged))
      testApp.service.library.purgeSelectedAssets(Set(purged))
      eventually(testApp.txManager.asReadOnly(query("SELECT id FROM asset WHERE id = ?", purged)) shouldBe Nil)
      renders.render(purged) shouldBe Outcome.Gone
      editedImages(purged) shouldBe Set.empty

      edits.save(gone, 1, warmer, testContext.user)
      Files.delete(testApp.service.fileStore.assetFile(gone))
      Files.write(testApp.service.fileStore.assetFile(corrupt), "not an image".getBytes): Unit
      renders.render(gone) shouldBe Outcome.Unrenderable(2)
      renders.render(corrupt) shouldBe Outcome.Unrenderable(1)
      for ((assetId, version) <- List(gone -> 2, corrupt -> 1)) {
        val state = edits.status(assetId)
        (state.editVersion, state.renderedVersion, state.rev) shouldBe (version, Some(version), 0)
        editedImages(assetId) shouldBe Set.empty
      }
      testApp.txManager.asReadOnly(testApp.DAO.assetEdit.pendingRenders()) should contain noneOf (gone, corrupt)
    }
  }

  test("Edited images a failed render left behind are replaced by the next render") {

    /**
     * Setup:
     *
     * An imported image saved once, never rendered, with bytes that are no image at the paths of its edited images, as a render
     * whose transaction failed after it stored them leaves them; then the Edit rendered.
     *
     * Assertions:
     *
     * The render takes revision 1, the next after `last_rev`, and both files now hold images it rendered.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce {
      edits.save(assetId, 0, brighter, testContext.user)
      for (kind <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)) {
        testApp.service.fileStore.stageEditedImage(assetId, kind, "left behind".getBytes)
        testApp.service.fileStore.publishEditedImage(assetId, kind)
      }
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)
      edits.status(assetId).rev shouldBe 1
      for (kind <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY)) withClue(s"$kind: ") {
        decoded(assetId, kind).empty shouldBe false
      }
    }
  }

  test("A render that fails is tried again after a delay, and stores its images once the cause is gone") {

    /**
     * Setup:
     *
     * An imported image whose Display image cannot be staged (a directory with a file in it stands where its temporary file
     * goes), saved once with the render delay at 100 ms; the directory removed a second later.
     *
     * Assertions:
     *
     * The queue's render fails and nothing is rendered while the directory stands; once it is gone, a later try, queued again
     * after a growing delay, renders the Edit as revision 1 with both edited images stored.
     */
    val assetId = testContext.persistAsset().persistedId
    val blocking = stagedFiles(assetId).last
    // Not empty, so the render's cleanup of what it staged cannot delete it
    Files.createDirectories(blocking)
    Files.write(blocking.resolve("in-the-way"), Array[Byte](1)): Unit
    renders.debounce = scala.concurrent.duration.DurationInt(100).millis
    try {
      edits.save(assetId, 0, brighter, testContext.user)
      Thread.sleep(1000)
      edits.status(assetId).renderedVersion shouldBe None
      Files.delete(blocking.resolve("in-the-way"))
      Files.delete(blocking)

      eventually {
        val state = edits.status(assetId)
        (state.renderedVersion, state.rev) shouldBe (Some(1), 1)
      }
      editedImages(assetId) shouldBe BOTH
    } finally renders.debounce = EditRenderService.DEBOUNCE
  }

  test("A render service that has stopped queues nothing more") {

    /**
     * Setup:
     *
     * An imported image saved once, not rendered, and a render service of its own with a delay of 10 ms, stopped.
     *
     * Assertions:
     *
     * Its `enqueue` neither fails nor renders: a second later the Edit is still not rendered, which the next start would queue.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce(edits.save(assetId, 0, brighter, testContext.user))
    val stopped = EditRenderService(testApp)
    stopped.debounce = scala.concurrent.duration.DurationInt(10).millis
    stopped.shutdown()

    stopped.enqueue(assetId)
    Thread.sleep(1000)
    edits.status(assetId).renderedVersion shouldBe None
  }
}
