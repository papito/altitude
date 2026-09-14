package altitude.core.service

import com.typesafe.config.Config
import java.nio.file.Path
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Const
import altitude.core.VideoException
import altitude.core.models.VideoInfo

object VideoService:

  /** One Sampled frame of a Video: its Frame time and the decoded image, upright, in BGR. The receiver releases the image. */
  case class SampledFrame(timeMs: Long, image: Mat)

  /**
   * The Frame times of a Video's Sampled frames: evenly spaced from its start at whichever is longer, `intervalMs` or the
   * duration divided by `maxFrames`, so no video yields more than `maxFrames` of them. A video with no duration yields its first
   * frame.
   */
  def sampleTimes(durationMs: Long, intervalMs: Long, maxFrames: Int): Seq[Long] =
    if durationMs <= 0 then return Seq(0L)
    // Rounded up, so a duration just over a multiple of maxFrames does not yield one frame too many
    val interval = Math.max(intervalMs, (durationMs + maxFrames - 1) / maxFrames)
    0L until durationMs by interval

  /** Mean brightness of a BGR image, 0 to 255 */
  def meanLuminance(image: Mat): Double =
    val gray = new Mat()
    Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
    try Core.mean(gray).`val`(0)
    finally gray.release()

  /**
   * The clockwise rotation, 0, 90, 180 or 270, that shows a Video's frames upright. The container's display matrix reports how
   * far the frames are to be turned counter-clockwise, which is what FFmpeg's own autorotate negates before turning.
   */
  def uprightRotation(displayRotation: Double): Int =
    val clockwise = Math.round(-displayRotation / 90.0).toInt * 90
    ((clockwise % 360) + 360) % 360

/**
 * Decodes Videos through FFmpeg (javacv). Probes a container for its display size and duration, and reads its Sampled frames as
 * OpenCV images, rotated upright, for the Preview and for face detection. A grabber is opened per call and nothing is shared
 * across threads.
 */
class VideoService(config: Config):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  // The org.opencv API is used on the frames, whose natives the bytedeco loader links; see FaceDetectionService
  Loader.load(classOf[opencv_java])
  // FFmpeg reports every stream it opens on stderr at its default level
  avutil.av_log_set_level(avutil.AV_LOG_ERROR)

  private val sampleIntervalMs: Long = config.getLong(Const.Conf.VIDEO_FACES_SAMPLE_INTERVAL_MS)
  private val maxSampledFrames: Int = config.getInt(Const.Conf.VIDEO_FACES_MAX_SAMPLED_FRAMES)
  private val previewMinLuminance: Double = config.getDouble(Const.Conf.VIDEO_PREVIEW_MIN_LUMINANCE)

  def probe(path: Path): VideoInfo = withGrabber(path) {
    grabber =>
      val rotation = VideoService.uprightRotation(grabber.getDisplayRotation)
      val (width, height) =
        if rotation == 90 || rotation == 270 then (grabber.getImageHeight, grabber.getImageWidth)
        else (grabber.getImageWidth, grabber.getImageHeight)

      val info = VideoInfo(
        width = width,
        height = height,
        durationMs = Math.round(grabber.getLengthInTime / 1000.0),
        frameRate = grabber.getFrameRate,
        hasAudio = grabber.getAudioChannels > 0)
      logger.debug(s"Probed $path: $info, rotation $rotation")
      info
  }

  /** The Frame times of the Video's Sampled frames for a duration, see [[VideoService.sampleTimes]] */
  def sampleTimes(durationMs: Long): Seq[Long] =
    VideoService.sampleTimes(durationMs, sampleIntervalMs, maxSampledFrames)

  /**
   * Decodes the frame at each Frame time, in order, and hands the frames to `consume` as an iterator that decodes as it is
   * advanced; a time past the last frame yields nothing. The Video stays open for the duration of the call, so the iterator must
   * not escape it. Each frame's image is released by the consumer.
   */
  def sampledFrames[A](path: Path, times: Seq[Long])(consume: Iterator[VideoService.SampledFrame] => A): A =
    withGrabber(path) {
      grabber =>
        val rotation = VideoService.uprightRotation(grabber.getDisplayRotation)
        // The converter hands out one Mat over the grabber's own frame buffer, so every frame is copied out of it
        val converter = new OpenCVFrameConverter.ToOrgOpenCvCoreMat()

        try
          val frames = times.iterator.flatMap {
            timeMs =>
              grabber.setTimestamp(timeMs * 1000)
              Option(grabber.grabImage()).map {
                frame =>
                  val image = upright(converter.convert(frame), rotation)
                  VideoService.SampledFrame(Math.round(frame.timestamp / 1000.0), image)
              }
          }
          consume(frames)
        finally converter.close()
    }

  /**
   * The Video's Preview: the first Sampled frame whose mean brightness clears the floor (`video.preview.min_luminance`), which
   * skips a black leader, or the frame at a tenth of the duration when none does
   */
  def previewFrame(path: Path): VideoService.SampledFrame =
    val durationMs = probe(path).durationMs

    val bright = sampledFrames(path, sampleTimes(durationMs)) {
      frames =>
        frames.find {
          frame =>
            VideoService.meanLuminance(frame.image) >= previewMinLuminance || {
              frame.image.release()
              false
            }
        }
    }

    bright.getOrElse {
      logger.info(s"No Sampled frame of $path clears the brightness floor; the Preview is the frame at a tenth of the duration")
      sampledFrames(path, Seq(durationMs / 10))(_.nextOption())
        .getOrElse(throw RuntimeException(s"No frame could be decoded from $path"))
    }

  /**
   * Opens the Video for the call and closes it after; a file FFmpeg cannot open or that has no video stream is a
   * [[VideoException]]
   */
  private def withGrabber[A](path: Path)(f: FFmpegFrameGrabber => A): A =
    val grabber = new FFmpegFrameGrabber(path.toFile)
    try
      try grabber.start()
      catch case ex: FFmpegFrameGrabber.Exception => throw VideoException(s"Cannot open $path: ${ex.getMessage}")

      if !hasVideoStream(grabber) then throw VideoException(s"No video stream in $path")
      f(grabber)
    finally grabber.release()

  /** Whether any stream is video proper: an audio file's cover art is a one-frame picture stream FFmpeg would otherwise decode */
  private def hasVideoStream(grabber: FFmpegFrameGrabber): Boolean =
    val context = grabber.getFormatContext
    (0 until context.nb_streams()).exists {
      i =>
        val stream = context.streams(i)
        stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO &&
        (stream.disposition() & avformat.AV_DISPOSITION_ATTACHED_PIC) == 0
    }

  /** A copy of the frame turned clockwise by the rotation; the source stays the converter's */
  private def upright(frame: Mat, rotation: Int): Mat =
    val image = new Mat()
    rotation match
      case 90 => Core.rotate(frame, image, Core.ROTATE_90_CLOCKWISE)
      case 180 => Core.rotate(frame, image, Core.ROTATE_180)
      case 270 => Core.rotate(frame, image, Core.ROTATE_90_COUNTERCLOCKWISE)
      case _ => frame.copyTo(image)
    image
