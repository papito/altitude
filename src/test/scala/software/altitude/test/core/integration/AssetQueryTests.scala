package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.util.Query
import software.altitude.core.{Const => C}

@DoNotDiscover class AssetQueryTests(val config: Map[String, Any]) extends IntegrationTestCore {
  test("Empty search") {
    val results = altitude.service.library.query(new Query())
    results.records.length shouldBe 0
    results.totalPages shouldBe 0
  }

  test("Search root folder") {
    val mediaType = new AssetType(mediaType = "mediaType", mediaSubtype = "mediaSubtype", mime = "mime")
    val asset = new Asset(
      userId = currentUser.id.get,
      assetType = mediaType,
      fileName = "filename.ext",
      path = Some("path"),
      checksum = "checksum",
      folderId = ctx.repo.triageFolderId,
      sizeBytes = 1L)
    altitude.service.asset.add(asset)

    val assets = altitude.service.library.query(new Query()).records
    assets.length shouldBe 1
  }

  test("Search triage folder") {
    val mediaType = new AssetType(mediaType = "mediaType", mediaSubtype = "mediaSubtype", mime = "mime")
    val asset = new Asset(
      userId = currentUser.id.get,
      assetType = mediaType,
      fileName = "filename.ext",
      path = Some("path"),
      checksum = "checksum",
      folderId = ctx.repo.triageFolderId,
      sizeBytes = 1L)
    altitude.service.asset.add(asset)

    val query = new Query(Map(C.Asset.FOLDER_ID -> ctx.repo.triageFolderId))
    val assets = altitude.service.library.query(query).records
    assets.length shouldBe 1
  }

  test("Pagination") {
    1 to 6 foreach { n =>
      altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))
    }

    val q = new Query(rpp = 2, page = 1)
    val results = altitude.service.library.query(q)
    results.total shouldBe 6
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true
    results.totalPages shouldBe 3

    val q2 = new Query(rpp = 2, page = 2)
    val results2 = altitude.service.library.query(q2)
    results2.total shouldBe 6
    results2.records.length shouldBe 2
    results2.totalPages shouldBe 3

    val q3 = new Query(rpp = 2, page = 3)
    val results3 = altitude.service.library.query(q3)
    results3.total shouldBe 6
    results3.records.length shouldBe 2
    results3.totalPages shouldBe 3

    // page too far
    val q4 = new Query(rpp = 2, page = 4)
    val results4 = altitude.service.library.query(q4)
    results4.total shouldBe 6
    results4.records.length shouldBe 0
    results4.totalPages shouldBe 3

    val q5 = new Query(rpp = 6, page = 1)
    val results5 = altitude.service.library.query(q5)
    results5.total shouldBe 6
    results5.records.length shouldBe 6
    results5.totalPages shouldBe 1

    val q6 = new Query(rpp = 20, page = 1)
    val results6 = altitude.service.library.query(q6)
    results6.total shouldBe 6
    results6.records.length shouldBe 6
    results6.totalPages shouldBe 1
  }

  test("Triage and Trash should have correct totals in query results") {
    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))
    altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    altitude.service.library.recycleAsset(asset.id.get)

    /* We now have two assets. One in triage, one trash. Each of the totals should be "1" not "2"
     */

    val query = new Query(Map(C.Asset.FOLDER_ID -> ctx.repo.triageFolderId))
    var results = altitude.service.library.query(query)
    results.records.length shouldBe 1
    results.total shouldBe 1

    results = altitude.service.library.queryRecycled(new Query())
    results.records.length shouldBe 1
    results.total shouldBe 1
  }
}
