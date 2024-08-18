package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.models.Metadata
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class MetadataParserTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("normalize metadata") {
    val metadata: Metadata = getMetadata("images/6.jpg")
    // metadata.data.toSeq.sortBy(_._1).foreach{case (key, value) => println(s"$key -> $value")}
    val verify = Map(
      "Image Height" -> "8",
      "Image Width" -> "10",
      "Exposure Time" -> "0.0025",
      "F-Number" -> "8.0",
      "Flash" -> "false",
      "Focal Length" -> "16.3",
      "Iso Speed" -> "100",
      "Make" -> "FUJIFILM",
      "Model" -> "FinePix F50fd",
    )

    verify.foreach { case (k, v) =>
      metadata.data.keys.toSeq should contain(k)
      metadata(k).map(_.value) should contain(v)
    }

//    metadata.get("X Resolution") shouldNot be(None)
//    metadata("X Resolution").map(_.value) should contain ("72 dots per inch")
//    metadata.get("Y Resolution") shouldNot be(None)
//    metadata("Y Resolution").map(_.value) should contain ("72 dots per inch")
  }

  private def getMetadata(relPath: String): Metadata = {
    val importAsset = IntegrationTestUtil.getImportAsset(relPath)
    val mediaType = testApp.service.assetImport.detectAssetType(importAsset)
    testApp.service.metadataExtractor.extract(importAsset, mediaType)
  }
}
