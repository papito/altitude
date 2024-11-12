package software.altitude.test

import org.apache.commons.io.FileUtils
import software.altitude.core.Altitude
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.UserMetadata
import software.altitude.core.{Const => C}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

object IntegrationTestUtil {
  def createTestDir(testApp: Altitude): Unit = {
    val testDir = new File(testApp.config.getString(C.Conf.TEST_DIR))

    if (!testDir.exists()) {
      FileUtils.forceMkdir(testDir)
    }
  }

  def createFileStoreDir(testApp: Altitude): Unit = {
    val dataDir = new File(testApp.config.getString(C.Conf.FS_DATA_DIR))

    if (dataDir.exists()) {
      FileUtils.cleanDirectory(dataDir)
    }
    else {
      FileUtils.forceMkdir(dataDir)
    }
  }

  /**
   * Convert a file system resource to an import asset (this reads the actual binary content of the file).
   */
  def fileToImportAsset(file: File): ImportAsset = new ImportAsset(
    fileName = file.getName,
    data = FileUtils.readFileToByteArray(file),
    metadata = UserMetadata())

  def getImportAsset(relPath: String): ImportAsset = {
    val path = getClass.getResource(s"/import/$relPath").getPath
    val file  = new File(path)

    if (!file.exists()) {
      throw new RuntimeException(s"File not found: $path")
    }

    val fileImportAsset = fileToImportAsset(file)
    fileImportAsset
  }

  def generateRandomImagBytesBgr(): Array[Byte] = {
    val w = 50
    val h = 50
    val bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR)

    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val a = (Math.random * 256).toInt //alpha
        val r = (Math.random * 256).toInt //red
        val g = (Math.random * 256).toInt //green
        val b = (Math.random * 256).toInt //blue
        val p = (a << 24) | (r << 16) | (g << 8) | b //pixel
        bufferedImage.setRGB(x, y, p)
        x += 1
      }
      y += 1
    }

    val byteArrayOutputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "png", byteArrayOutputStream)
    byteArrayOutputStream.toByteArray
  }

  def generateRandomImagBytesGray(): Array[Byte] = {
    val w = 50
    val h = 50
    val bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)

    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val a = (Math.random * 256).toInt //alpha
        val r = (Math.random * 256).toInt //red
        val g = (Math.random * 256).toInt //green
        val b = (Math.random * 256).toInt //blue
        val p = (a << 24) | (r << 16) | (g << 8) | b //pixel
        bufferedImage.setRGB(x, y, p)
        x += 1
      }
      y += 1
    }

    val byteArrayOutputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "png", byteArrayOutputStream)
    byteArrayOutputStream.toByteArray
  }

}
