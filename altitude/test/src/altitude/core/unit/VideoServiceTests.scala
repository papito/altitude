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
    val info = service.probe(TestVideos.oneFace)

    info.durationMs shouldBe 3000L +- 100
    info.width shouldBe TestVideos.WIDTH
    info.height shouldBe TestVideos.HEIGHT
    info.frameRate shouldBe TestVideos.FPS.toDouble +- 0.01
    info.hasAudio shouldBe false
  }

  test("A probe reports the display size of a rotated clip, so a phone's portrait recording is portrait") {
    val info = service.probe(TestVideos.portrait)

    info.width shouldBe TestVideos.HEIGHT
    info.height shouldBe TestVideos.WIDTH
  }

  test("The schedule samples a short clip every second and a long one at most the maximum number of times") {
    VideoService.sampleTimes(3000, 1000, 120) shouldEqual Seq(0L, 1000L, 2000L)
    VideoService.sampleTimes(20 * 60 * 1000, 1000, 120) should have size 120
    VideoService.sampleTimes(20 * 60 * 1000 + 1, 1000, 120) should have size 120
    VideoService.sampleTimes(0, 1000, 120) shouldEqual Seq(0L)

    // The instance reads the interval and the maximum from the config
    service.sampleTimes(3000) shouldEqual Seq(0L, 1000L, 2000L)
    service.sampleTimes(2 * 60 * 60 * 1000) should have size 120
  }

  test("Sampled frames are decoded at their Frame times, in order, and carry the time they were decoded at") {
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

  test("A Frame time past the end of the clip yields no frame") {
    service.sampledFrames(TestVideos.oneFace, Seq(0L, 60_000L))(_.map(_.image.release()).size) shouldBe 1
  }

  test("A rotated clip's frames come out upright, turned the way FFmpeg's autorotate would turn them") {
    // A white band along the top of a landscape frame that the container says to show as portrait (a phone held upright);
    // turned clockwise, the band lands on the right edge of the portrait frame
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
    val preview = service.previewFrame(TestVideos.blackLeader)

    // Two seconds of black, sampled every second: the first frame that is not black is the one at two seconds
    preview.timeMs shouldBe 2000L +- 150
    VideoService.meanLuminance(preview.image) should be > 40.0
    preview.image.release()
  }

  test("The Preview of a clip that never clears the brightness floor is the frame at a tenth of the duration") {
    val preview = service.previewFrame(TestVideos.clip(Seq(TestVideos.black(5))))

    preview.timeMs shouldBe 500L +- 150
    preview.image.release()
  }

  test("The upright rotation follows the container's counter-clockwise display rotation, negated and snapped to a quarter turn") {
    VideoService.uprightRotation(0) shouldBe 0
    VideoService.uprightRotation(-90) shouldBe 90
    VideoService.uprightRotation(90) shouldBe 270
    VideoService.uprightRotation(180) shouldBe 180
    VideoService.uprightRotation(-180) shouldBe 180
    VideoService.uprightRotation(-89.9) shouldBe 90
  }

  test("A file without a video stream, and a file that is not a video, cannot be probed") {
    intercept[VideoException] {
      service.probe(TestVideos.audio("all.mp3"))
    }
    intercept[VideoException] {
      service.probe(java.nio.file.Files.createTempFile("not-a-video", ".mp4"))
    }
  }
}
