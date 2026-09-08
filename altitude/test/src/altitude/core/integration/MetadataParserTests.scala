package altitude.core.integration

import altitude.test.IntegrationTestUtil
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.{ convertToStringShouldWrapperForVerb, should }

import altitude.core.Altitude
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.util.{ CaptureDateInputs, CaptureDateResolver }

@DoNotDiscover class MetadataParserTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Detect asset type JPEG") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val assetType: AssetType = testApp.service.metadataExtractor.detectAssetType(importAsset.data)
    assetType.mediaType should be("image")
    assetType.mediaSubtype should be("jpeg")
    assetType.mime should be("image/jpeg")
  }

  test("Detect asset type PNG") {
    val importAsset = IntegrationTestUtil.getImportAsset("images/3.png")
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

  test("Resolve the capture wall clock from real extracted metadata") {
    val imported = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val metadata = testApp.service.metadataExtractor.extract(imported.data)
    val capture =
      CaptureDateResolver.resolve(CaptureDateInputs(metadata, imported.fileName), LocalDateTime.of(2026, 9, 7, 0, 0)).get
    capture.at should be(LocalDateTime.of(2011, 5, 16, 17, 46, 24))
    capture.source should be(CaptureDateSource.ExifOriginal)
  }

  test("Extract XMP properties alongside its tag count") {
    // The Fuji JPEG's XMP has duplicate exif:Orientation fields and is rejected by the library. Generate a valid packet.
    val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
      <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/">
        <exif:DateTimeOriginal>2008:04:17 11:12:02</exif:DateTimeOriginal>
      </rdf:Description></rdf:RDF></x:xmpmeta>"""
    val png = IntegrationTestUtil.pngWithInternationalTextChunk(
      IntegrationTestUtil.generateRandomImagBytesBgr(),
      "XML:com.adobe.xmp",
      packet)
    val metadata = testApp.service.metadataExtractor.extract(png)
    metadata.getFieldValues("XMP").get("exif:DateTimeOriginal") should be(Some("2008:04:17 11:12:02"))
    metadata.getFieldValues("XMP").contains("XMP Value Count") should be(true)
  }

  test("Extract PNG text keys without losing earlier chunks") {
    val png = IntegrationTestUtil.generateRandomImagBytesBgr()
    val dated = IntegrationTestUtil.pngWithTextChunk(png, "Creation Time", "Thu, 4 Jul 2024 08:09:10 GMT")
    val annotated = IntegrationTestUtil.pngWithTextChunk(dated, "Comment", "A cactus")
    val metadata = testApp.service.metadataExtractor.extract(annotated)
    metadata.getFieldValues("PNG-tEXt").get("Creation Time") should be(Some("Thu, 4 Jul 2024 08:09:10 GMT"))
    metadata.getFieldValues("PNG-tEXt").get("Comment") should be(Some("A cactus"))
    metadata.getFieldValues("PNG-tEXt").contains("Textual Data") should be(false)
  }
}
