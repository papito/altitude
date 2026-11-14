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

  /** The revisions of the asset's stored renditions, of each kind */
  private def renditions(assetId: String): Map[String, Set[Int]] =
    List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY).map {
      kind =>
        kind -> (1 to 20)
          .filter(rev => Files.isRegularFile(testApp.service.fileStore.editRenditionPath(assetId, rev, kind)))
          .toSet
    }.toMap

  private def both(revs: Int*): Map[String, Set[Int]] =
    Map(Const.DataStore.PREVIEW -> revs.toSet, Const.DataStore.DISPLAY -> revs.toSet)

  /** A rendition's image, decoded as stored */
  private def decoded(assetId: String, rev: Int, kind: String): Mat =
    val bytes = Files.readAllBytes(testApp.service.fileStore.editRenditionPath(assetId, rev, kind))
    Imgcodecs.imdecode(new MatOfByte(bytes*), Imgcodecs.IMREAD_UNCHANGED)

  /** An asset imported through the whole pipeline from a gray JPEG of `width` × `height` */
  private def importedJpeg(width: Int, height: Int): Asset =
    val image = new Mat(height, width, CvType.CV_8UC3, new Scalar(90, 120, 150))
    val bytes = new MatOfByte()
    Imgcodecs.imencode(".jpg", image, bytes)
    val staged = testApp.service.staging.stage(bytes.toArray)
    testApp.service.library.addAsset(AssetWithData(testContext.makeAsset(), staged))

  test("A render of an edited head stores both renditions under the next revision, and drops the previous revision's") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, rendered; then a second step, rendered.
     *
     * Assertions:
     *
     * The first render stores the edited Preview and the Display image under revision 1, which the asset's `edit_rev` and the
     * Edit's state (`rev`) name, with step 1 rendered. The second stores revision 2, names it, has step 2 rendered, and the files
     * of revision 1 are gone.
     *
     * Edge cases:
     *
     * A render of a head rendered already stores nothing: the state stays as it is.
     */
    withDebounce {
      val asset = testContext.persistAsset()
      val assetId = asset.persistedId

      edits.addStep(assetId, 0, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)
      val first = edits.status(assetId)
      (first.head, first.rendered, first.rev) shouldBe (1, Some(1), 1)
      testApp.service.asset.getById(assetId).editRev shouldBe 1
      renditions(assetId) shouldBe both(1)

      edits.addStep(assetId, 1, warmer, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(2, 2)
      val second = edits.status(assetId)
      (second.head, second.rendered, second.rev) shouldBe (2, Some(2), 2)
      renditions(assetId) shouldBe both(2)

      renders.render(assetId) shouldBe Outcome.UpToDate
      edits.status(assetId).rev shouldBe 2
    }
  }

  test("A render of the Original sets the revision to 0 and drops every rendition, and no revision is used twice") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, rendered; then undone to the Original and rendered; then redone and rendered.
     *
     * Assertions:
     *
     * The Original's render stores nothing, sets `edit_rev` to 0 with step 0 rendered, and drops revision 1's files. The redone
     * step is rendered as revision 2, never 1 again, so a URL naming revision 1 cannot name other bytes.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.addStep(assetId, 0, brighter, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 1)

      edits.moveHead(assetId, 1, 0, testContext.user)
      renders.render(assetId) shouldBe Outcome.Original(0)
      val original = edits.status(assetId)
      (original.head, original.rendered, original.rev) shouldBe (0, Some(0), 0)
      testApp.service.asset.getById(assetId).editRev shouldBe 0
      renditions(assetId) shouldBe both()
      lastRev(assetId) shouldBe 1

      edits.moveHead(assetId, 0, 1, testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 2)
      renditions(assetId) shouldBe both(2)
    }
  }

  test("A head moved during a render throws the render away and queues the asset again") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, whose render is given a second step between its read and its write.
     *
     * Assertions:
     *
     * The render is discarded: nothing is stored and step 1 is never recorded rendered. The asset is queued again, and the queue
     * renders step 2 as revision 1.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce(edits.addStep(assetId, 0, brighter, testContext.user))

    renders.render(assetId, beforeWrite = () => edits.addStep(assetId, 1, warmer, testContext.user): Unit) shouldBe
      Outcome.Discarded
    renditions(assetId) shouldBe both()
    edits.status(assetId).rendered shouldBe None

    eventually {
      val state = edits.status(assetId)
      (state.head, state.rendered, state.rev) shouldBe (2, Some(2), 1)
    }
    renditions(assetId) shouldBe both(1)
  }

  test("A step made after an undo past the rendered step, which takes that step's seq, is not taken as rendered") {

    /**
     * Setup:
     *
     * An imported image with two steps of its Edit. A render of step 2 that, between its read and its write, sees the Edit undone
     * to step 1 and given a new step, which is step 2 again with another Edit. Then a third step, rendered, undone to step 1 and
     * given a new step again.
     *
     * Assertions:
     *
     * The late render of the old step 2 is discarded, though the head's seq is still 2, and the head stays unrendered. A new step
     * made at step 1 after step 3 was rendered drops step 3 and takes seq 2: the head is not taken as rendered.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.addStep(assetId, 0, brighter, testContext.user)
      edits.addStep(assetId, 1, warmer, testContext.user)

      renders.render(
        assetId,
        beforeWrite = () => {
          edits.moveHead(assetId, 2, 1, testContext.user)
          edits.addStep(assetId, 1, brighter.copy(grain = 20), testContext.user): Unit
        }) shouldBe Outcome.Discarded

      val state = edits.status(assetId)
      (state.head, state.rendered) shouldBe (2, None)

      edits.addStep(assetId, 2, warmer.copy(grain = 30), testContext.user)
      renders.render(assetId) shouldBe Outcome.Rendered(3, 1)
      edits.moveHead(assetId, 3, 1, testContext.user)
      edits.addStep(assetId, 1, warmer, testContext.user).rendered shouldBe None
    }
  }

  test("A render's write waits for a move of the head under way, and then stores nothing of the old head") {

    /**
     * Setup:
     *
     * An imported image with two steps of its Edit. A transaction undoing step 2, held open until a render of the asset, whose
     * write locks the Edit, waits for it (for the Edit's row on PostgreSQL, for the one write connection on SQLite). The undo
     * writes the Edit's row alone, so the asset's own lock does not hold the render back.
     *
     * Assertions:
     *
     * The render never stores step 2 as rendered: on PostgreSQL, where it read step 2 before the undo committed, it is discarded
     * once its lock is granted; on SQLite, where it waits before it reads, it renders step 1.
     */
    withDebounce {
      val assetId = testContext.persistAsset().persistedId
      edits.addStep(assetId, 0, brighter, testContext.user)
      edits.addStep(assetId, 1, warmer, testContext.user)

      val outcome = new java.util.concurrent.atomic.AtomicReference[Outcome]()
      contend(
        first = edits.moveHead(assetId, 2, 1, testContext.user): Unit,
        second = outcome.set(renders.render(assetId))
      )
      outcome.get should (be(Outcome.Discarded).or(be(Outcome.Rendered(1, 1))))
      edits.status(assetId).rendered should not be Some(2)
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

  test("A render's write and a step of the Edit take the asset's and the Edit's locks in one order, so neither deadlocks") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, rendered. A transaction that takes the locks a render's write takes, in its
     * order: the asset's first, then, once a second step of the Edit is waiting for it or done, the Edit's.
     *
     * Assertions:
     *
     * Both commit, one after the other, and the second step is the head. On PostgreSQL a step that locked the Edit before the
     * asset would wait for the asset (its step's foreign key) while the render's write waits for the Edit: a deadlock the
     * database breaks by failing one of them.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce {
      edits.addStep(assetId, 0, brighter, testContext.user)
      renders.render(assetId)
    }

    interleave(
      firstLocks = testApp.DAO.asset.lockUnpurged(assetId) shouldBe true,
      second = edits.addStep(assetId, 1, warmer, testContext.user): Unit,
      firstThen = testApp.DAO.assetEdit.getForUpdate(assetId).map(_.headSeq) should not be empty
    )
    edits.get(assetId).head shouldBe 2
  }

  test("Changes in quick succession are rendered once, at the head they end on") {

    /**
     * Setup:
     *
     * An imported image given two steps of its Edit at once, each queueing a render.
     *
     * Assertions:
     *
     * The queue renders once, after the last change: step 2 as revision 1, step 1 never rendered.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    edits.addStep(assetId, 1, warmer, testContext.user)

    eventually {
      val state = edits.status(assetId)
      (state.head, state.rendered, state.rev) shouldBe (2, Some(2), 1)
    }
    lastRev(assetId) shouldBe 1
  }

  test("The startup queues every Edit whose head is not rendered") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, rendered by the queue; then its rendered step cleared by hand, as a stop before
     * its render leaves it; then the startup's requeue.
     *
     * Assertions:
     *
     * The requeue renders the head again, as the next revision.
     */
    val assetId = testContext.persistAsset().persistedId
    edits.addStep(assetId, 0, brighter, testContext.user)
    eventually(edits.status(assetId).rev shouldBe 1)

    testApp.txManager.withTransaction(update("UPDATE asset_edit SET rendered_seq = NULL WHERE asset_id = ?", assetId))
    renders.requeuePendingRenders()

    eventually {
      val state = edits.status(assetId)
      (state.rendered, state.rev) shouldBe (Some(1), 2)
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
      edits.addStep(small, 0, halfCrop, testContext.user)
      renders.render(small) shouldBe Outcome.Rendered(1, 1)

      val display = decoded(small, 1, Const.DataStore.DISPLAY)
      (display.width, display.height) shouldBe (75, 150)
      val preview = decoded(small, 1, Const.DataStore.PREVIEW)
      (preview.width, preview.height, preview.channels) shouldBe (200, 200, 4)
      preview.get(100, 10)(3) shouldBe 0.0
      preview.get(100, 190)(3) shouldBe 0.0

      val large = importedJpeg(4000, 300).persistedId
      edits.addStep(large, 0, brighter, testContext.user)
      renders.render(large) shouldBe Outcome.Rendered(1, 1)
      val wide = decoded(large, 1, Const.DataStore.DISPLAY)
      (wide.width, wide.height) shouldBe (3840, 288)
    }
  }

  test("A render of a purged asset stores nothing, and an original gone or undecodable is recorded rendered as the original") {

    /**
     * Setup:
     *
     * Three imported images with a step of their Edit each: one recycled and purged; one, rendered once, whose Edit is then given
     * a second step and its stored original deleted; and one whose stored original is overwritten with bytes no decoder reads.
     *
     * Assertions:
     *
     * The purged asset's render finds it gone and stores nothing. The others cannot be rendered: each is recorded rendered at its
     * head with revision 0, so it shows its original, keeps no rendition, and is not queued again at the next start.
     */
    val purged = testContext.persistAsset().persistedId
    val gone = testContext.persistAsset().persistedId
    val corrupt = testContext.persistAsset().persistedId
    withDebounce {
      for (assetId <- List(purged, gone, corrupt)) edits.addStep(assetId, 0, brighter, testContext.user)
      renders.render(gone) shouldBe Outcome.Rendered(1, 1)

      testApp.service.library.recycleAssets(Set(purged))
      testApp.service.library.purgeSelectedAssets(Set(purged))
      eventually(testApp.txManager.asReadOnly(query("SELECT id FROM asset WHERE id = ?", purged)) shouldBe Nil)
      renders.render(purged) shouldBe Outcome.Gone
      renditions(purged) shouldBe both()

      edits.addStep(gone, 1, warmer, testContext.user)
      Files.delete(testApp.service.fileStore.assetFile(gone))
      Files.write(testApp.service.fileStore.assetFile(corrupt), "not an image".getBytes): Unit
      renders.render(gone) shouldBe Outcome.Unrenderable(2)
      renders.render(corrupt) shouldBe Outcome.Unrenderable(1)
      for ((assetId, head) <- List(gone -> 2, corrupt -> 1)) {
        val state = edits.status(assetId)
        (state.head, state.rendered, state.rev) shouldBe (head, Some(head), 0)
        renditions(assetId) shouldBe both()
      }
      testApp.txManager.asReadOnly(testApp.DAO.assetEdit.pendingRenders()) should contain noneOf (gone, corrupt)
    }
  }

  test("A revision whose files a failed render left behind is never used again") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, and files of revision 1 in its rendition directories, as a render whose
     * transaction failed after it stored them leaves them; then the step rendered.
     *
     * Assertions:
     *
     * The render takes revision 2, past the one on disk, and the leftover files of revision 1 are deleted with the revisions
     * below.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce {
      edits.addStep(assetId, 0, brighter, testContext.user)
      for (kind <- List(Const.DataStore.PREVIEW, Const.DataStore.DISPLAY))
        testApp.service.fileStore.addEditRendition(assetId, 1, kind, "left behind".getBytes)
      renders.render(assetId) shouldBe Outcome.Rendered(1, 2)
      edits.status(assetId).rev shouldBe 2
      renditions(assetId) shouldBe both(2)
    }
  }

  test("A render service that has stopped queues nothing more") {

    /**
     * Setup:
     *
     * An imported image with a step of its Edit, not rendered, and a render service of its own with a delay of 10 ms, stopped.
     *
     * Assertions:
     *
     * Its `enqueue` neither fails nor renders: a second later the head is still not rendered, which the next start would queue.
     */
    val assetId = testContext.persistAsset().persistedId
    withDebounce(edits.addStep(assetId, 0, brighter, testContext.user))
    val stopped = EditRenderService(testApp)
    stopped.debounce = scala.concurrent.duration.DurationInt(10).millis
    stopped.shutdown()

    stopped.enqueue(assetId)
    Thread.sleep(1000)
    edits.status(assetId).rendered shouldBe None
  }
}
