package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.models.AssetType
import software.altitude.core.models.ExtractedMetadata
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class MetadataParserTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Detect asset type JPEG") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val assetType: AssetType = testApp.service.metadataExtractor.detectAssetType(importAsset.data)
    assetType.mediaType should be("image")
    assetType.mediaSubtype should be("jpeg")
    assetType.mime should be("image/jpeg")
  }

  test("Detect asset type PNG") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/3.PNG")
    val assetType: AssetType = testApp.service.metadataExtractor.detectAssetType(importAsset.data)
    assetType.mediaType should be("image")
    assetType.mediaSubtype should be("png")
    assetType.mime should be("image/png")
  }

  test("Extract metadata") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val metadata: ExtractedMetadata = testApp.service.metadataExtractor.extract(importAsset.data)
    metadata.getFieldValues("JFIF").get("Resolution Units") should be(Some("inch"))
    metadata.getFieldValues("Exif IFD0").get("Make") should be(Some("NIKON CORPORATION"))
  }
}
