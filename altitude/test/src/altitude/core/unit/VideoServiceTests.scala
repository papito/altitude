package altitude.core.unit

import altitude.test.TestFocus
import altitude.test.TestVideos
import com.typesafe.config.ConfigFactory
import org.opencv.core.Scalar
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.VideoException
import altitude.core.service.VideoService

/** Decoding through [[VideoService]] over clips synthesized by [[TestVideos]], with the shipped `reference.conf` defaults */
@DoNotDiscover class VideoServiceTests extends AnyFunSuite with TestFocus {

  private val service = VideoService(ConfigFactory.defaultReference())

  test("A probe reports the duration, the encoded size, the frame rate and the absence of audio") {

    /**
     * Setup:
     *
     * The synthesized three-second, 640x480, 10 fps clip of one person (affleck.jpg), with no audio track.
     *
     * Assertions:
     *
     * The probe reports the clip as it was encoded: its duration, size and frame rate, and that it has no audio.
     */
    val info = service.probe(TestVideos.oneFace)

    info.durationMs shouldBe 3000L +- 100
    info.width shouldBe TestVideos.WIDTH
    info.height shouldBe TestVideos.HEIGHT
    info.frameRate shouldBe TestVideos.FPS.toDouble +- 0.01
    info.hasAudio shouldBe false
  }

  test("A probe reports the display size of a rotated clip, so a phone's portrait recording is portrait") {

    /**
     * Setup:
     *
     * A landscape 640x480 clip whose container asks for it to be shown rotated to portrait, as a phone held upright records.
     *
     * Assertions:
     *
     * The probe reports the display size, with width and height swapped from the encoded ones.
     */
    val info = service.probe(TestVideos.portrait)

    info.width shouldBe TestVideos.HEIGHT
    info.height shouldBe TestVideos.WIDTH
  }

  test("The schedule samples a short clip every second and a long one at most the maximum number of times") {

    /**
     * Setup:
     *
     * Durations from zero to two hours, given to the schedule both with an explicit one-second interval and 120-frame maximum and
     * through a service that reads the same values from the shipped config.
     *
     * Assertions:
     *
     * A short clip is sampled once a second from the start, and no clip, however long, yields more than the maximum number of
     * Sampled frames.
     *
     * Edge cases:
     *
     * A duration that exactly fills the maximum at one-second spacing, one a millisecond longer (which must not yield an extra
     * frame), and a zero duration, which still yields the first frame.
     */
    VideoService.sampleTimes(3000, 1000, 120) shouldEqual Seq(0L, 1000L, 2000L)
    VideoService.sampleTimes(20 * 60 * 1000, 1000, 120) should have size 120
    VideoService.sampleTimes(20 * 60 * 1000 + 1, 1000, 120) should have size 120
    VideoService.sampleTimes(0, 1000, 120) shouldEqual Seq(0L)

    // The instance reads the interval and the maximum from the config
    service.sampleTimes(3000) shouldEqual Seq(0L, 1000L, 2000L)
    service.sampleTimes(2 * 60 * 60 * 1000) should have size 120
  }

  test("Sampled frames are decoded at their Frame times, in order, and carry the time they were decoded at") {

    /**
     * Setup:
     *
     * The three-second clip of one person, decoded at 0, 1 and 2 seconds.
     *
     * Assertions:
     *
     * One full-size frame comes back per requested time, in order, each carrying a decode time close to the time requested.
     */
    val times = service.sampledFrames(TestVideos.oneFace, Seq(0L, 1000L, 2000L)) {
      frames =>
        frames.map {
          frame =>
            frame.image.cols() shouldBe TestVideos.WIDTH
            frame.image.rows() shouldBe TestVideos.HEIGHT
            frame.image.release()
            frame.timeMs
        }.toList
    }

    times should have size 3
    times.zip(Seq(0L, 1000L, 2000L)).foreach { case (actual, requested) => actual shouldBe requested +- 150 }
    times shouldEqual times.sorted
  }

  test("One open of a clip decodes more than once, seeking back, so a second pass does not reopen it") {

    /**
     * Setup:
     *
     * The three-second clip of one person opened once, then decoded at 2 seconds and, in a second pass, at 0 and 1 seconds.
     *
     * Assertions:
     *
     * Both passes decode their frames from the one open clip, the second one seeking back before the first pass's position.
     */
    val times = service.withFrames(TestVideos.oneFace) {
      decode =>
        def timesOf(times: Seq[Long]) = decode(times).map {
          frame =>
            frame.image.release(); frame.timeMs
        }.toList
        timesOf(Seq(2000L)) ++ timesOf(Seq(0L, 1000L))
    }

    times should have size 3
    times.zip(Seq(2000L, 0L, 1000L)).foreach { case (actual, requested) => actual shouldBe requested +- 150 }
  }

  test("A Frame time past the end of the clip yields no frame") {

    /**
     * Setup:
     *
     * The three-second clip of one person, decoded at its start and at one minute.
     *
     * Assertions:
     *
     * Only the frame at the start comes back - the time past the end yields nothing rather than failing.
     */
    service.sampledFrames(TestVideos.oneFace, Seq(0L, 60_000L))(_.map(_.image.release()).size) shouldBe 1
  }

  test("A rotated clip's frames come out upright, turned the way FFmpeg's autorotate would turn them") {

    /**
     * Setup:
     *
     * A one-second clip of a black landscape frame with a white band along its top, which the container says to show as portrait
     * (a phone held upright).
     *
     * Assertions:
     *
     * The decoded frame is portrait-sized and turned clockwise, so the band lands on the right edge of the portrait frame and the
     * left edge stays dark.
     */
    val frame = TestVideos.blackFrame()
    frame.rowRange(0, 40).setTo(new Scalar(255, 255, 255))
    val clip = TestVideos.clip(Seq(TestVideos.Scene(frame, 1)), displayRotation = -90)

    service.sampledFrames(clip, Seq(0L)) {
      frames =>
        val image = frames.next().image
        image.cols() shouldBe TestVideos.HEIGHT
        image.rows() shouldBe TestVideos.WIDTH

        VideoService.meanLuminance(image.colRange(image.cols() - 40, image.cols())) should be > 200.0
        VideoService.meanLuminance(image.colRange(0, 40)) should be < 20.0
        image.release()
    }
  }

  test("The Preview is the first Sampled frame past a black leader") {

    /**
     * Setup:
     *
     * A clip of two seconds of black followed by three seconds of one person, with the shipped brightness floor.
     *
     * Assertions:
     *
     * The Preview skips the black leader: it is the first Sampled frame bright enough to clear the floor.
     */
    val preview = service.previewFrame(TestVideos.blackLeader, service.probe(TestVideos.blackLeader).durationMs)

    // Two seconds of black, sampled every second: the first frame that is not black is the one at two seconds
    preview.timeMs shouldBe 2000L +- 150
    VideoService.meanLuminance(preview.image) should be > 40.0
    preview.image.release()
  }

  test("The Preview of a clip that never clears the brightness floor is the frame at a tenth of the duration") {

    /**
     * Setup:
     *
     * A five-second clip that is black throughout.
     *
     * Assertions:
     *
     * With no Sampled frame bright enough, the Preview falls back to the frame at a tenth of the duration.
     */
    val clip = TestVideos.clip(Seq(TestVideos.black(5)))
    val preview = service.previewFrame(clip, service.probe(clip).durationMs)

    preview.timeMs shouldBe 500L +- 150
    preview.image.release()
  }

  test("The upright rotation follows the container's counter-clockwise display rotation, negated and snapped to a quarter turn") {

    /**
     * Setup:
     *
     * Display rotations of zero, plus and minus a quarter and a half turn, and one just short of a quarter turn.
     *
     * Assertions:
     *
     * Each turns into the clockwise rotation, 0 to 270, that shows the frames upright.
     *
     * Edge cases:
     *
     * Plus and minus a half turn both give 180, and a rotation that is not an exact quarter turn snaps to the nearest one.
     */
    VideoService.uprightRotation(0) shouldBe 0
    VideoService.uprightRotation(-90) shouldBe 90
    VideoService.uprightRotation(90) shouldBe 270
    VideoService.uprightRotation(180) shouldBe 180
    VideoService.uprightRotation(-180) shouldBe 180
    VideoService.uprightRotation(-89.9) shouldBe 90
  }

  test("A file without a video stream, and a file that is not a video, cannot be probed") {

    /**
     * Setup:
     *
     * An MP3 file from the import fixtures, which FFmpeg opens but has no video stream, and an empty file named like an MP4.
     *
     * Assertions:
     *
     * Probing either one fails with a video error.
     */
    intercept[VideoException] {
      service.probe(TestVideos.audio("all.mp3"))
    }
    intercept[VideoException] {
      service.probe(java.nio.file.Files.createTempFile("not-a-video", ".mp4"))
    }
  }
}
