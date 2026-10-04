package altitude.core.integration

import altitude.test.IntegrationTestUtil
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.{ convertNumericToPlusOrMinusWrapper, convertToStringShouldWrapperForVerb, should }

import altitude.core.Altitude
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.util.{ CaptureDateInputs, CaptureDateResolver, GeoLocationResolver, JsonCodec }

@DoNotDiscover class MetadataParserTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Detect asset type JPEG") {

    /**
     * Setup:
     *
     * The `people/meme-ben.jpg` file on disk.
     *
     * Assertions:
     *
     * Its type is detected as image, jpeg, image/jpeg.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val assetType: AssetType = testApp.service.metadataExtractor.detectAssetType(importAsset.path)
    assetType.mediaType should be("image")
    assetType.mediaSubtype should be("jpeg")
    assetType.mime should be("image/jpeg")
  }

  test("Detect asset type PNG") {

    /**
     * Setup:
     *
     * The `images/3.png` file on disk.
     *
     * Assertions:
     *
     * Its type is detected as image, png, image/png.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/3.png")
    val assetType: AssetType = testApp.service.metadataExtractor.detectAssetType(importAsset.path)
    assetType.mediaType should be("image")
    assetType.mediaSubtype should be("png")
    assetType.mime should be("image/png")
  }

  test("Extract metadata") {

    /**
     * Setup:
     *
     * The `images/cactus.jpg` file, a Nikon JPEG with JFIF and EXIF directories.
     *
     * Assertions:
     *
     * Extraction stores tag descriptions by directory: the JFIF resolution unit and the EXIF camera make read back.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val metadata: ExtractedMetadata = testApp.service.metadataExtractor.extract(importAsset.path)
    metadata.getFieldValues("JFIF").get("Resolution Units") should be(Some("inch"))
    metadata.getFieldValues("Exif IFD0").get("Make") should be(Some("NIKON CORPORATION"))
  }

  test("Resolve the capture wall clock from real extracted metadata") {

    /**
     * Setup:
     *
     * Metadata extracted from `images/cactus.jpg`, resolved against a fixed current time of 2026-09-07.
     *
     * Assertions:
     *
     * The capture time is the EXIF original date, 2011-05-16 17:46:24, as a wall clock with no zone, and its source is the EXIF
     * original.
     */
    val imported = IntegrationTestUtil.getImportAsset("images/cactus.jpg")
    val metadata = testApp.service.metadataExtractor.extract(imported.path)
    val capture =
      CaptureDateResolver.resolve(CaptureDateInputs(metadata, imported.fileName), LocalDateTime.of(2026, 9, 7, 0, 0)).get
    capture.at should be(LocalDateTime.of(2011, 5, 16, 17, 46, 24))
    capture.source should be(CaptureDateSource.ExifOriginal)
  }

  test("Extract XMP properties alongside its tag count") {

    /**
     * Setup:
     *
     * A random PNG carrying, in an international text chunk, a generated XMP packet with `exif:DateTimeOriginal`. The packet is
     * generated because the Fuji JPEG fixture's XMP has duplicate `exif:Orientation` fields, which the library rejects.
     *
     * Assertions:
     *
     * The XMP directory holds the property under its path, read from XMP's property map, alongside the ordinary XMP Value Count
     * tag.
     */
    val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
      <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/">
        <exif:DateTimeOriginal>2008:04:17 11:12:02</exif:DateTimeOriginal>
      </rdf:Description></rdf:RDF></x:xmpmeta>"""
    val png = IntegrationTestUtil.pngWithInternationalTextChunk(
      IntegrationTestUtil.generateRandomImagBytesBgr(),
      "XML:com.adobe.xmp",
      packet)
    val metadata = testApp.service.metadataExtractor.extract(testApp.service.staging.stage(png))
    metadata.getFieldValues("XMP").get("exif:DateTimeOriginal") should be(Some("2008:04:17 11:12:02"))
    metadata.getFieldValues("XMP").contains("XMP Value Count") should be(true)
  }

  test("Extract PNG text keys without losing earlier chunks") {

    /**
     * Setup:
     *
     * A random PNG with two text chunks, Creation Time and Comment.
     *
     * Assertions:
     *
     * Each chunk's keyword becomes its own key in the PNG-tEXt directory, so the second chunk does not overwrite the first and no
     * generic Textual Data tag is stored.
     */
    val png = IntegrationTestUtil.generateRandomImagBytesBgr()
    val dated = IntegrationTestUtil.pngWithTextChunk(png, "Creation Time", "Thu, 4 Jul 2024 08:09:10 GMT")
    val annotated = IntegrationTestUtil.pngWithTextChunk(dated, "Comment", "A cactus")
    val metadata = testApp.service.metadataExtractor.extract(testApp.service.staging.stage(annotated))
    metadata.getFieldValues("PNG-tEXt").get("Creation Time") should be(Some("Thu, 4 Jul 2024 08:09:10 GMT"))
    metadata.getFieldValues("PNG-tEXt").get("Comment") should be(Some("A cactus"))
    metadata.getFieldValues("PNG-tEXt").contains("Textual Data") should be(false)
  }

  test("Resolve GPS coordinates from real extracted metadata") {

    /**
     * Setup:
     *
     * Tiny EXIF JPEG fixtures under `images/exif/`: coordinates north-east, south-west and just west of Greenwich, one without
     * ref tags, and one at 0/0.
     *
     * Assertions:
     *
     * Each located fixture resolves to its signed latitude and longitude, within 1e-4 degrees, the hemisphere taken from the ref
     * tags.
     *
     * Edge cases:
     *
     * A longitude between -1 and 0 (-0.1276), whose DMS description drops the sign so only the W ref carries it; coordinates
     * without refs and 0/0 resolve to nothing.
     */
    def resolved(fixture: String): Option[(Double, Double)] = {
      val imported = IntegrationTestUtil.getImportAsset(s"images/exif/$fixture.jpg")
      GeoLocationResolver.resolve(testApp.service.metadataExtractor.extract(imported.path)).map(p => (p.latitude, p.longitude))
    }
    for (
      (fixture, (latitude, longitude)) <- List(
        "gps-north-east" -> (33.857, 151.2152),
        "gps-south-west" -> (-33.857, -151.2152),
        // The DMS description of -0.1276 reads "0° 7' 39.36\"": only the W ref carries the sign
        "gps-sub-degree-west" -> (51.5007, -0.1276)
      )
    ) {
      val point = resolved(fixture).getOrElse(fail(s"$fixture resolved to nothing"))
      point._1 should be(latitude +- 1e-4)
      point._2 should be(longitude +- 1e-4)
    }
    resolved("gps-no-ref") should be(None)
    resolved("gps-zero") should be(None)
  }

  test("A GPS coordinate without a ref has no description and is skipped rather than stored as null") {

    /**
     * Setup:
     *
     * The `images/exif/gps-no-ref.jpg` fixture, whose GPS coordinates have no ref tags.
     *
     * Assertions:
     *
     * The coordinates, having no description, are skipped: nothing is stored under GPS, no value anywhere is null, and the
     * metadata survives a JSON round trip with its other tags, such as the FUJIFILM make.
     */
    val imported = IntegrationTestUtil.getImportAsset("images/exif/gps-no-ref.jpg")
    val metadata = testApp.service.metadataExtractor.extract(imported.path)
    metadata.getFieldValues("GPS") should be(Map())
    metadata.data.values.flatMap(_.values).exists(_ == null) should be(false)
    JsonCodec.read[ExtractedMetadata](metadata.toJson).getFieldValues("Exif IFD0").get("Make") should be(Some("FUJIFILM"))
  }
}
