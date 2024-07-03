package software.altitude.test

import org.apache.commons.io.FileUtils
import software.altitude.core.Altitude
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.Metadata
import software.altitude.core.{Const => C}

import java.io.File

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
    metadata = Metadata())

  def getImportAsset(path: String): ImportAsset = {
    val _path = getClass.getResource(s"/import/$path").getPath
    val fileImportAsset = fileToImportAsset(new File(_path))
    fileImportAsset
  }
}
