package altitude.core.util

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

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

  def makeImageThumbnail(data: Array[Byte], previewBoxSize: Int): Array[Byte] =
    val mt: com.drew.metadata.Metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data))
    val exifDirectory = mt.getFirstDirectoryOfType(classOf[ExifIFD0Directory])
    // val jpegDirectory = mt.getFirstDirectoryOfType(classOf[JpegDirectory])

    val orientation: Int =
      try exifDirectory.getInt(ExifDirectoryBase.TAG_ORIENTATION)
      catch case _: Exception => 1

    /**
     * Rotate the image if necessary
     *
     * https://sirv.com/help/articles/rotate-photos-to-be-upright/
     * https://stackoverflow.com/questions/5905868/how-to-rotate-jpeg-images-based-on-the-orientation-metadata
     */
    val imageMat = decode(data, Imgcodecs.IMREAD_UNCHANGED | Imgcodecs.IMREAD_IGNORE_ORIENTATION, keepAlpha = true)
    val scaleFactor = determineImageScale(imageMat.width(), imageMat.height(), previewBoxSize, previewBoxSize)

    val resizedMat = new Mat()
    Imgproc.resize(imageMat, resizedMat, new Size(), scaleFactor, scaleFactor, Imgproc.INTER_AREA)

    val mob = new MatOfByte
    Imgcodecs.imencode(".png", resizedMat, mob)
    val scaledImage = ImageIO.read(new ByteArrayInputStream(mob.toArray))

    val width = scaledImage.getWidth
    val height = scaledImage.getHeight

    val transform: AffineTransform = new AffineTransform()
    orientation match
      case 1 =>
      case 2 =>
        transform.scale(-1.0, 1.0);
        transform.translate(-width, 0);
      case 3 =>
        transform.translate(width, height);
        transform.rotate(Math.PI);
      case 4 =>
        transform.scale(1.0, -1.0);
        transform.translate(0, -height);
      case 5 =>
        transform.rotate(-Math.PI / 2);
        transform.scale(-1.0, 1.0);
      case 6 =>
        transform.translate(height, 0);
        transform.rotate(Math.PI / 2);
      case 7 =>
        transform.scale(-1.0, 1.0);
        transform.translate(-height, 0);
        transform.translate(0, width);
        transform.rotate(3 * Math.PI / 2);
      case 8 =>
        transform.translate(0, width);
        transform.rotate(3 * Math.PI / 2);
      case _ =>

    val op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC)

    val destinationImage = op.createCompatibleDestImage(scaledImage, null)

    val graphics = destinationImage.createGraphics()
    graphics.setBackground(Color.WHITE)
    graphics.clearRect(0, 0, destinationImage.getWidth, destinationImage.getHeight)
    val rotationCorrectScaledImage = op.filter(scaledImage, destinationImage)

    val compositeImage: BufferedImage =
      new BufferedImage(previewBoxSize, previewBoxSize, BufferedImage.TYPE_INT_ARGB)
    val G2D: Graphics2D = compositeImage.createGraphics

    val x: Int =
      if rotationCorrectScaledImage.getHeight > rotationCorrectScaledImage.getWidth then
        (previewBoxSize - rotationCorrectScaledImage.getWidth) / 2
      else 0
    val y: Int =
      if rotationCorrectScaledImage.getHeight < rotationCorrectScaledImage.getWidth then
        (previewBoxSize - rotationCorrectScaledImage.getHeight()) / 2
      else 0

    G2D.setComposite(AlphaComposite.Clear)
    G2D.fillRect(0, 0, previewBoxSize, previewBoxSize)
    G2D.setComposite(AlphaComposite.Src)
    G2D.drawImage(rotationCorrectScaledImage, x, y, null)
    val byteArray: ByteArrayOutputStream = new ByteArrayOutputStream
    ImageIO.write(compositeImage, "png", byteArray)
    graphics.dispose()

    byteArray.toByteArray
