package altitude.core.util

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
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

  // OpenCV applies the EXIF orientation as it decodes (the ImageIO fallback does not); the alpha channel is dropped
  def matFromBytes(data: Array[Byte]): Mat = decode(data, Imgcodecs.IMREAD_ANYCOLOR, keepAlpha = false)

  /**
   * With OpenCV or, for a format the bundled OpenCV has no codec for (GIF among them), with ImageIO, which gives an animated
   * image's first frame. `keepAlpha` tells the fallback what `flags` ask of OpenCV. The size is read from the header alone, so
   * corrupt data is first found here.
   */
  private def decode(data: Array[Byte], flags: Int, keepAlpha: Boolean): Mat =
    val image = Imgcodecs.imdecode(new MatOfByte(data*), flags)
    if !image.empty() then return image

    val decoded =
      try Option(ImageIO.read(new ByteArrayInputStream(data)))
      catch case ex: IOException => throw ImageException(s"Cannot decode the image: ${ex.getMessage}")
    matFromImage(decoded.getOrElse(throw ImageException("Neither OpenCV nor ImageIO can decode the image")), keepAlpha)

  /** Laid out as OpenCV decodes: BGR, or BGRA when asked to keep an alpha channel the image has */
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
   * The sum of an animated GIF's frame delays, without decoding a frame; `None` for a still one. A frame of 10 ms or less, or
   * with no delay, plays for 100 ms, as browsers show it. The reader's input must be set seekable and with metadata
   * (`reader.setInput(input)`).
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

  /** Upright (1) without the tag or without EXIF at all */
  def exifOrientation(data: Array[Byte]): Int = orientationOf(ImageMetadataReader.readMetadata(new ByteArrayInputStream(data)))

  def exifOrientation(file: Path): Int = orientationOf(ImageMetadataReader.readMetadata(file.toFile))

  private def orientationOf(metadata: Metadata): Int =
    val exifDirectory = metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory])
    try exifDirectory.getInt(ExifDirectoryBase.TAG_ORIENTATION)
    catch case _: Exception => 1

  /**
   * Decoded with its alpha channel and turned upright by its EXIF orientation.
   *
   * https://sirv.com/help/articles/rotate-photos-to-be-upright/
   */
  def makeImageThumbnail(data: Array[Byte], previewBoxSize: Int): Array[Byte] =
    val image = decodeUnchanged(data)
    try makeImageThumbnail(image, exifOrientation(data), previewBoxSize)
    finally image.release()

  /** Read from the header with no decode; None for a non-JPEG (by its start of image marker) or an unreadable header */
  def jpegSize(data: Array[Byte]): Option[(Int, Int)] =
    if data.length < 3 || (data(0) & 0xff) != 0xff || (data(1) & 0xff) != 0xd8 || (data(2) & 0xff) != 0xff then return None
    val input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))
    try
      val readers = ImageIO.getImageReaders(input)
      Option.when(readers.hasNext)(readers.next()).flatMap {
        reader =>
          try
            reader.setInput(input)
            Some((reader.getWidth(0), reader.getHeight(0)))
          catch case _: IOException => None
          finally reader.dispose()
      }
    finally input.close()

  /**
   * A JPEG reduced by the decoder itself, in 8-bit BGR with its EXIF orientation ignored; each side is the stored one divided and
   * rounded up. The caller releases it.
   */
  def decodeReduced(data: Array[Byte], reduction: Int): Mat =
    val flag = reduction match
      case 2 => Imgcodecs.IMREAD_REDUCED_COLOR_2
      case 4 => Imgcodecs.IMREAD_REDUCED_COLOR_4
      case 8 => Imgcodecs.IMREAD_REDUCED_COLOR_8
      case _ => throw IllegalArgumentException(s"No reduced decode by [$reduction]")
    val bytes = new MatOfByte(data*)
    try
      val image = Imgcodecs.imdecode(bytes, flag | Imgcodecs.IMREAD_IGNORE_ORIENTATION)
      if image.empty() then throw ImageException("OpenCV cannot decode the JPEG")
      image
    finally bytes.release()

  /** Depth and alpha kept, EXIF orientation ignored, for [[orient]] to turn upright. The caller releases it. */
  def decodeUnchanged(data: Array[Byte]): Mat =
    decode(data, Imgcodecs.IMREAD_UNCHANGED | Imgcodecs.IMREAD_IGNORE_ORIENTATION, keepAlpha = true)

  /**
   * `image` stays the caller's to release; `orientation` is 1 for an upright one such as a Video frame. The box being square, the
   * image is scaled before it is turned, while it is smallest.
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

  /** A new image, upright for the EXIF `orientation`; 1, or a value the tag cannot hold, gives a copy */
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

  /** A new 8-bit BGRA image; a 16-bit one keeps the high byte of each sample, since WebP holds 8-bit samples only */
  def toBgra(image: Mat): Mat =
    val bgra = new Mat()
    image.channels() match
      case 4 => image.copyTo(bgra)
      case 3 => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_BGR2BGRA)
      case 1 => Imgproc.cvtColor(image, bgra, Imgproc.COLOR_GRAY2BGRA)
      case channels => throw ImageException(s"An image of $channels channels has no preview")
    if bgra.depth() == CvType.CV_16U then bgra.convertTo(bgra, CvType.CV_8U, 1.0 / 256)
    bgra

  /** A new 8-bit BGRA image, centered on a transparent border, the odd border pixel after the image */
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

  def encodePreview(box: Mat): Array[Byte] = encodeWebp(box, Const.AssetView.PREVIEW_WEBP_QUALITY)

  /**
   * Lossy; `quality` (0 to 100) governs the color planes only: libwebp keeps the alpha plane exact, and drops it when every pixel
   * is opaque
   */
  def encodeWebp(image: Mat, quality: Int): Array[Byte] =
    val buffer = new MatOfByte()
    val params = new MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, quality)
    try
      if !Imgcodecs.imencode(".webp", image, buffer, params) then throw ImageException("The image cannot be encoded as WebP")
      buffer.toArray
    finally
      buffer.release()
      params.release()
