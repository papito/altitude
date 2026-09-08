package altitude.test

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.apache.commons.io.FileUtils

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.models.ImportAsset
import altitude.core.models.UserMetadata

object IntegrationTestUtil {

  def createTestDir(testApp: Altitude): Unit = {
    val testDir = new File(testApp.config.getString(C.Conf.TEST_DIR))

    if (!testDir.exists()) {
      FileUtils.forceMkdir(testDir)
    }

    val dbDir = new File(testDir, "db")
    if (!dbDir.exists()) {
      FileUtils.forceMkdir(dbDir)
    }
  }

  def createFileStoreDir(testApp: Altitude): Unit = {
    val dataDir = new File(testApp.config.getString(C.Conf.FS_DATA_DIR))

    if (dataDir.exists()) {
      FileUtils.cleanDirectory(dataDir)
    } else {
      FileUtils.forceMkdir(dataDir)
    }
  }

  /** Convert a file system resource to an import asset (this reads the actual binary content of the file). */
  def fileToImportAsset(file: File): ImportAsset =
    new ImportAsset(fileName = file.getName, data = FileUtils.readFileToByteArray(file), metadata = UserMetadata())

  def getImportAsset(relPath: String): ImportAsset = {
    val path = getClass.getResource(s"/import/$relPath").getPath
    val file = new File(path)

    if (!file.exists()) {
      throw new RuntimeException(s"File not found: $path")
    }

    val fileImportAsset = fileToImportAsset(file)
    fileImportAsset
  }

  def generateRandomImagBytesBgr(dimensions: Int = 50): Array[Byte] = {
    val w = dimensions
    val h = dimensions
    val bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR)

    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val a = (Math.random * 256).toInt // alpha
        val r = (Math.random * 256).toInt // red
        val g = (Math.random * 256).toInt // green
        val b = (Math.random * 256).toInt // blue
        val p = (a << 24) | (r << 16) | (g << 8) | b // pixel
        bufferedImage.setRGB(x, y, p)
        x += 1
      }
      y += 1
    }

    val byteArrayOutputStream = new ByteArrayOutputStream()
    ImageIO.write(bufferedImage, "png", byteArrayOutputStream)
    byteArrayOutputStream.toByteArray
  }

  /** Insert a valid PNG text chunk just before IEND; repeated calls preserve earlier chunks and their order. */
  def pngWithTextChunk(bytes: Array[Byte], key: String, value: String): Array[Byte] = {
    val payload = (key + "\u0000" + value).getBytes(StandardCharsets.ISO_8859_1)
    pngWithChunk(bytes, "tEXt", payload)
  }

  /** Uncompressed international text includes compression, language and translated-key fields before the UTF-8 value. */
  def pngWithInternationalTextChunk(bytes: Array[Byte], key: String, value: String): Array[Byte] =
    pngWithChunk(bytes, "iTXt", (key + "\u0000\u0000\u0000\u0000\u0000" + value).getBytes(StandardCharsets.UTF_8))

  private def pngWithChunk(bytes: Array[Byte], kind: String, payload: Array[Byte]): Array[Byte] = {
    val chunkType = kind.getBytes(StandardCharsets.US_ASCII)
    val crc = new CRC32()
    crc.update(chunkType)
    crc.update(payload)
    val output = new ByteArrayOutputStream()
    val data = new DataOutputStream(output)
    data.write(bytes, 0, bytes.length - 12)
    data.writeInt(payload.length)
    data.write(chunkType)
    data.write(payload)
    data.writeInt(crc.getValue.toInt)
    data.write(bytes, bytes.length - 12, 12)
    output.toByteArray
  }

  def generateRandomImagBytesGray(): Array[Byte] = {
    val w = 50
    val h = 50
    val bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)

    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val a = (Math.random * 256).toInt // alpha
        val r = (Math.random * 256).toInt // red
        val g = (Math.random * 256).toInt // green
        val b = (Math.random * 256).toInt // blue
        val p = (a << 24) | (r << 16) | (g << 8) | b // pixel
        bufferedImage.setRGB(x, y, p)
        x += 1
      }
      y += 1
    }

    val byteArrayOutputStream = new ByteArrayOutputStream()
    ImageIO.write(bufferedImage, "png", byteArrayOutputStream)
    byteArrayOutputStream.toByteArray
  }

}
