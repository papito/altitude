package altitude.core.util

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

import altitude.core.Const
import altitude.core.ImageException

object ImageUtil:

  // Get OPENCV image Mat from a byte array
  def matFromBytes(data: Array[Byte]): Mat = decode(data, Imgcodecs.IMREAD_ANYCOLOR, keepAlpha = false)

  /**
   * Decodes an image with OpenCV or, for a format the bundled OpenCV has no codec for (GIF among them), with ImageIO, which gives
   * an animated image's first frame. `keepAlpha` is what the flags ask of OpenCV, for the fallback to do the same. Data neither
   * decodes is an [[ImageException]]: the size is read from the header alone, so a file whose data is corrupt is found here.
   */
  private def decode(data: Array[Byte], flags: Int, keepAlpha: Boolean): Mat =
    val image = Imgcodecs.imdecode(new MatOfByte(data*), flags)
    if !image.empty() then return image

    val decoded =
      try Option(ImageIO.read(new ByteArrayInputStream(data)))
      catch case ex: IOException => throw ImageException(s"Cannot decode the image: ${ex.getMessage}")
    matFromImage(decoded.getOrElse(throw ImageException("Neither OpenCV nor ImageIO can decode the image")), keepAlpha)

  /** An image ImageIO decoded, laid out as OpenCV decodes one: BGR, or BGRA when asked to keep an alpha channel it has */
  private def matFromImage(image: BufferedImage, keepAlpha: Boolean): Mat =
    val withAlpha = keepAlpha && image.getColorModel.hasAlpha
    val (imageType, matType) =
      if withAlpha then (BufferedImage.TYPE_4BYTE_ABGR, CvType.CV_8UC4) else (BufferedImage.TYPE_3BYTE_BGR, CvType.CV_8UC3)

    val converted = new BufferedImage(image.getWidth, image.getHeight, imageType)
    val drawing = converted.createGraphics()
    drawing.drawImage(image, 0, 0, null)
    drawing.dispose()

    // The raster holds the bytes of each pixel in the order its type names: B, G, R or A, B, G, R
    val mat = new Mat(image.getHeight, image.getWidth, matType)
    mat.put(0, 0, converted.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData)
    if !withAlpha then return mat

    val bgra = new Mat(image.getHeight, image.getWidth, CvType.CV_8UC4)
    Core.mixChannels(java.util.List.of(mat), java.util.List.of(bgra), new MatOfInt(0, 3, 1, 0, 2, 1, 3, 2))
    mat.release()
    bgra

  /**
   * An animated GIF's playing time, the sum of its frames' delays, from the header, without decoding a frame; `None` for a still
   * one. A frame without a delay, or one of 10 ms or less, plays for 100 ms, as browsers show it. The reader's input is set with
   * its metadata and able to seek back (`reader.setInput(input)`), for the frames to be counted and read.
   */
  def gifPlayingTimeMs(reader: ImageReader): Option[Long] =
    val frames = reader.getNumImages(true)
    Option.when(frames > 1) {
      (0 until frames).map {
        frame =>
          val metadata = reader.getImageMetadata(frame)
          val control = metadata
            .getAsTree(metadata.getNativeMetadataFormatName)
            .asInstanceOf[IIOMetadataNode]
            .getElementsByTagName("GraphicControlExtension")
          val delayCs = Option(control.item(0)).map(_.asInstanceOf[IIOMetadataNode].getAttribute("delayTime").toInt).getOrElse(0)
          if delayCs <= 1 then 100L else delayCs * 10L
      }.sum
    }

  def determineImageScale(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Double =
    val scaleX = targetWidth.toDouble / sourceWidth
    val scaleY = targetHeight.toDouble / sourceHeight
    Math.min(scaleX, scaleY)

  /** The EXIF orientation of an image's bytes; an image without the tag, or without EXIF at all, is upright (1) */
  private def exifOrientation(data: Array[Byte]): Int =
    val exifDirectory =
      ImageMetadataReader.readMetadata(new ByteArrayInputStream(data)).getFirstDirectoryOfType(classOf[ExifIFD0Directory])
    try exifDirectory.getInt(ExifDirectoryBase.TAG_ORIENTATION)
    catch case _: Exception => 1

  /**
   * The Preview of an image's bytes: decoded with its alpha channel, turned upright by its EXIF orientation and fitted into the
   * square box by the `Mat` overload.
   *
   * https://sirv.com/help/articles/rotate-photos-to-be-upright/
   */
  def makeImageThumbnail(data: Array[Byte], previewBoxSize: Int): Array[Byte] =
    val image = decode(data, Imgcodecs.IMREAD_UNCHANGED | Imgcodecs.IMREAD_IGNORE_ORIENTATION, keepAlpha = true)
    try makeImageThumbnail(image, exifOrientation(data), previewBoxSize)
    finally image.release()

  /**
   * The Preview of a decoded image, which stays the caller's to release: scaled to fit the box with `INTER_AREA`, turned upright
   * by its EXIF `orientation` (1 for an image that is upright already, such as a Video frame), centered in the square box on a
   * transparent border and encoded by [[encodePreview]]. The box being square, the image is scaled before it is turned, when it
   * is smallest.
   */
  def makeImageThumbnail(image: Mat, orientation: Int, previewBoxSize: Int): Array[Byte] =
    val scale = determineImageScale(image.width(), image.height(), previewBoxSize, previewBoxSize)
    val scaled = new Mat()
    Imgproc.resize(image, scaled, new Size(), scale, scale, Imgproc.INTER_AREA)
    val upright = orient(scaled, orientation)
    scaled.release()
    val box = letterbox(upright, previewBoxSize)
    upright.release()
    try encodePreview(box)
    finally box.release()

  /**
   * `image` turned upright for its EXIF `orientation`, as a new image, the input left to the caller: 2 is mirrored across the
   * vertical axis, 3 turned around, 4 mirrored across the horizontal axis, 5 transposed, 6 turned a quarter clockwise, 7
   * transposed across the other diagonal (transposed, then turned around), 8 turned a quarter counter-clockwise. 1, and a value
   * the tag cannot hold, is a copy.
   */
  def orient(image: Mat, orientation: Int): Mat =
    val upright = new Mat()
    orientation match
      case 2 => Core.flip(image, upright, 1)
      case 3 => Core.rotate(image, upright, Core.ROTATE_180)
      case 4 => Core.flip(image, upright, 0)
      case 5 => Core.transpose(image, upright)
      case 6 => Core.rotate(image, upright, Core.ROTATE_90_CLOCKWISE)
      case 7 =>
        val transposed = new Mat()
        Core.transpose(image, transposed)
        Core.rotate(transposed, upright, Core.ROTATE_180)
        transposed.release()
      case 8 => Core.rotate(image, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
      case _ => image.copyTo(upright)
    upright

  /**
   * `image` as a new image in 8-bit BGRA, what [[encodePreview]] takes: a BGR or a gray image gains an opaque alpha channel, and
   * a 16-bit one keeps the high byte of each sample, since WebP holds 8-bit samples only.
   */
  def toBgra(image: Mat): Mat =
    val bgra = new Mat()
    image.channels() match
      case 4 => image.copyTo(bgra)
      case 3 => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_BGR2BGRA)
      case 1 => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_GRAY2BGRA)
      case channels => throw ImageException(s"An image of $channels channels has no preview")
    if bgra.depth() == CvType.CV_16U then bgra.convertTo(bgra, CvType.CV_8U, 1.0 / 256)
    bgra

  /**
   * `image` centered in a square box of `boxSize` a side on a transparent border, a new image in 8-bit BGRA ([[toBgra]]). A
   * portrait image is centered horizontally and a landscape one vertically, the odd pixel of the border after the image.
   */
  private def letterbox(image: Mat, boxSize: Int): Mat =
    val bgra = toBgra(image)
    val left = (boxSize - bgra.width()) / 2
    val top = (boxSize - bgra.height()) / 2
    val box = new Mat()
    Core.copyMakeBorder(
      bgra,
      box,
      top,
      boxSize - bgra.height() - top,
      left,
      boxSize - bgra.width() - left,
      Core.BORDER_CONSTANT,
      new Scalar(0, 0, 0, 0))
    bgra.release()
    box

  /**
   * A Preview's bytes: `box`, an 8-bit BGRA image, as a lossy WebP at [[Const.AssetView.PREVIEW_WEBP_QUALITY]], its alpha plane
   * kept exact.
   */
  def encodePreview(box: Mat): Array[Byte] =
    val buffer = new MatOfByte()
    val quality = new MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, Const.AssetView.PREVIEW_WEBP_QUALITY)
    try
      if !Imgcodecs.imencode(".webp", box, buffer, quality) then throw ImageException("The preview cannot be encoded as WebP")
      buffer.toArray
    finally
      buffer.release()
      quality.release()
