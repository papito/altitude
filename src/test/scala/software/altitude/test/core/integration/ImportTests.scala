package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.DuplicateException
import software.altitude.core.models.Asset
import software.altitude.core.models.Preview
import software.altitude.test.core.IntegrationTestCore
import software.altitude.test.core.IntegrationTestCore.fileToImportAsset

import java.io.File

@DoNotDiscover class ImportTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("import duplicate") {
    importFile("images/1.jpg")
    val path = getClass.getResource("/import/images/1.jpg").getPath
    val importAsset = fileToImportAsset(new File(path))

    intercept[DuplicateException] {
      altitude.service.assetImport.importAsset(importAsset)
    }
  }

  test("import image") {
    val asset = importFile("images/1.jpg")
    val preview: Preview = altitude.service.library.getPreview(asset.id.get)
    preview.mimeType should equal("application/octet-stream")
    preview.data.length should not be 0
  }

  private def importFile(path: String): Asset = {
    val _path = getClass.getResource(s"/import/$path").getPath
    val fileImportAsset = fileToImportAsset(new File(_path))
    val importedAsset = altitude.service.assetImport.importAsset(fileImportAsset).get
    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.path should not be empty
    importedAsset.checksum should not be empty

    val asset = altitude.service.library.getById(importedAsset.id.get): Asset
    asset.assetType should equal(importedAsset.assetType)
    asset.path should not be empty
    asset.checksum should not be empty
    asset.sizeBytes should not be 0
    asset
  }

}
