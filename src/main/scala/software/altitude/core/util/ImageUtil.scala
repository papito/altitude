package software.altitude.core.util

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

object ImageUtil {
  // Get OPENCV image Mat from a byte array
  def matFromBytes(data: Array[Byte]): Mat = {
    Imgcodecs.imdecode(new MatOfByte(data: _*), Imgcodecs.IMREAD_ANYCOLOR)
  }

  def determineImageScale(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Double = {
    val scaleX = targetWidth.toDouble / sourceWidth
    val scaleY = targetHeight.toDouble / sourceHeight
    Math.min(scaleX, scaleY)
  }

  def makeImageThumbnail(data: Array[Byte], previewBoxSize: Int): Array[Byte] = {
    try {
      val mt: com.drew.metadata.Metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data))
      val exifDirectory = mt.getFirstDirectoryOfType(classOf[ExifIFD0Directory])
      // val jpegDirectory = mt.getFirstDirectoryOfType(classOf[JpegDirectory])

      val orientation: Int =
        try {
          exifDirectory.getInt(ExifDirectoryBase.TAG_ORIENTATION)
        } catch {
          case _: Exception => 1
        }

      /**
       * Rotate the image if necessary
       *
       * https://sirv.com/help/articles/rotate-photos-to-be-upright/
       * https://stackoverflow.com/questions/5905868/how-to-rotate-jpeg-images-based-on-the-orientation-metadata
       */
      val imageMat = Imgcodecs.imdecode(new MatOfByte(data: _*), Imgcodecs.IMREAD_UNCHANGED | Imgcodecs.IMREAD_IGNORE_ORIENTATION)
      val scaleFactor = determineImageScale(imageMat.width(), imageMat.height(), previewBoxSize, previewBoxSize)

      val resizedMat = new Mat()
      Imgproc.resize(imageMat, resizedMat, new Size(), scaleFactor, scaleFactor, Imgproc.INTER_AREA)

      val mob = new MatOfByte
      Imgcodecs.imencode(".png", resizedMat, mob)
      val scaledImage = ImageIO.read(new ByteArrayInputStream(mob.toArray))

      val width = scaledImage.getWidth
      val height = scaledImage.getHeight

      val transform: AffineTransform = new AffineTransform()
      orientation match {
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
      }

      val op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC)

      val destinationImage = op.createCompatibleDestImage(scaledImage, null)

      val graphics = destinationImage.createGraphics()
      graphics.setBackground(Color.WHITE)
      graphics.clearRect(0, 0, destinationImage.getWidth, destinationImage.getHeight)
      val rotationCorrectScaledImage = op.filter(scaledImage, destinationImage)

      val compositeImage: BufferedImage =
        new BufferedImage(previewBoxSize, previewBoxSize, BufferedImage.TYPE_INT_ARGB)
      val G2D: Graphics2D = compositeImage.createGraphics

      val x: Int = if (rotationCorrectScaledImage.getHeight > rotationCorrectScaledImage.getWidth) {
        (previewBoxSize - rotationCorrectScaledImage.getWidth) / 2
      } else 0
      val y: Int = if (rotationCorrectScaledImage.getHeight < rotationCorrectScaledImage.getWidth) {
        (previewBoxSize - rotationCorrectScaledImage.getHeight()) / 2
      } else 0

      G2D.setComposite(AlphaComposite.Clear)
      G2D.fillRect(0, 0, previewBoxSize, previewBoxSize)
      G2D.setComposite(AlphaComposite.Src)
      G2D.drawImage(rotationCorrectScaledImage, x, y, null)
      val byteArray: ByteArrayOutputStream = new ByteArrayOutputStream
      ImageIO.write(compositeImage, "png", byteArray)
      graphics.dispose()

      byteArray.toByteArray

    } catch {
      case ex: Exception =>
        Util.logStacktrace(ex)
        throw ex
    }
  }
}
