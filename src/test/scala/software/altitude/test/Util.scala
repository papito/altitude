package software.altitude.test

import software.altitude.core.models.ImportAsset
import software.altitude.test.core.IntegrationTestCore.fileToImportAsset

import java.io.File

object Util {
  def getImportAsset(path: String): ImportAsset = {
    val _path = getClass.getResource(s"/import/$path").getPath
    val fileImportAsset = fileToImportAsset(new File(_path))
    fileImportAsset
  }
}
