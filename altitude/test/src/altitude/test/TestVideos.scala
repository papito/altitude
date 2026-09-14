package altitude.test

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Videos synthesized at test time, so no binaries live in git: short MP4s (the `mpeg4` codec, which the LGPL FFmpeg build
 * encodes) made of stills held for a span each, the `people/` images letterboxed on black. Each named clip is encoded once per
 * JVM into a temporary directory that is removed on exit.
 */
object TestVideos {
  val WIDTH = 640
  val HEIGHT = 480
  val FPS = 10

  Loader.load(classOf[opencv_java])
  avutil.av_log_set_level(avutil.AV_LOG_ERROR)

  /** A still, in BGR at the clip's size, held for a span of the clip */
  case class Scene(image: Mat, seconds: Double)

  /** A `people/` image letterboxed on black */
  def person(image: String, seconds: Double): Scene = Scene(personFrame(image), seconds)

  def black(seconds: Double): Scene = Scene(blackFrame(), seconds)

  def blackFrame(): Mat = Mat.zeros(HEIGHT, WIDTH, org.opencv.core.CvType.CV_8UC3)

  def personFrame(image: String): Mat = {
    val path = getClass.getResource(s"/import/people/$image").getPath
    val still = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
    if (still.empty()) throw new RuntimeException(s"Could not read $path")

    val scale = Math.min(WIDTH.toDouble / still.cols(), HEIGHT.toDouble / still.rows())
    val scaled = new Mat()
    Imgproc.resize(still, scaled, new Size(), scale, scale, Imgproc.INTER_AREA)
    still.release()

    val frame = blackFrame()
    val inset = new Rect((WIDTH - scaled.cols()) / 2, (HEIGHT - scaled.rows()) / 2, scaled.cols(), scaled.rows())
    scaled.copyTo(frame.submat(inset))
    scaled.release()
    frame
  }

  /**
   * Encodes the scenes in order into an MP4 and releases their images. `displayRotation` is written to the container's display
   * matrix in javacv's convention, degrees counter-clockwise, so a phone's portrait recording is `-90`.
   */
  def clip(scenes: Seq[Scene], displayRotation: Double = 0, metadata: Map[String, String] = Map.empty): Path = {
    val file = Files.createTempFile(directory, "clip", ".mp4")
    file.toFile.deleteOnExit()

    val recorder = new FFmpegFrameRecorder(file.toFile, WIDTH, HEIGHT)
    recorder.setFormat("mp4")
    recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4)
    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P)
    recorder.setFrameRate(FPS)
    // Enough that a face survives encoding recognizably
    recorder.setVideoBitrate(4_000_000)
    if (displayRotation != 0) recorder.setDisplayRotation(displayRotation)
    metadata.foreach { case (key, value) => recorder.setMetadata(key, value) }

    val converter = new OpenCVFrameConverter.ToOrgOpenCvCoreMat()
    try {
      recorder.start()
      scenes.foreach {
        scene =>
          val frame = converter.convert(scene.image)
          (1 to Math.round(scene.seconds * FPS).toInt).foreach(_ => recorder.record(frame))
      }
      recorder.stop()
    } finally {
      recorder.release()
      converter.close()
      scenes.foreach(_.image.release())
    }
    file
  }

  /** One person for three seconds */
  lazy val oneFace: Path = clip(Seq(person("affleck.jpg", 3)))

  /** Two people, one after the other, three seconds each */
  lazy val twoPeopleInSequence: Path = clip(Seq(person("bullock.jpg", 3), person("damon.jpg", 3)))

  /** Two seconds of black, then one person for three seconds */
  lazy val blackLeader: Path = clip(Seq(black(2), person("affleck.jpg", 3)))

  /** A clip whose container records when it was made, as the UTC instant 2023-06-09 12:34:56 */
  lazy val dated: Path = clip(Seq(black(1)), metadata = Map("creation_time" -> "2023-06-09T12:34:56Z"))

  /** A landscape encoding whose container says to show it as portrait, the way a phone held upright records */
  lazy val portrait: Path = clip(Seq(person("affleck.jpg", 2)), displayRotation = -90)

  /** An audio file, for a container FFmpeg opens but that has no video stream */
  def audio(name: String): Path = new File(getClass.getResource(s"/import/audio/$name").getPath).toPath

  private lazy val directory: Path = {
    val dir = Files.createTempDirectory("altitude-test-videos")
    dir.toFile.deleteOnExit()
    dir
  }
}
