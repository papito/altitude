package software.altitude.test.core.integration
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core._
import software.altitude.core.models._
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class LibraryServiceRestoreTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Move recycled asset to folder") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    val folder1: Folder = testApp.service.folder.add("folder1")

    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folder1.persistedId)
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.asset.query(new Query()).records.length shouldBe 1

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 1
  }

  test("Restore recycled assets") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))
    testApp.service.asset.query(new Query()).isEmpty shouldBe false
  }

  test("Restore recycled asset to non-existing folder") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[NotFoundException] {
      testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), "bad")
    }
  }

  test("Restore an asset that was imported again") {
    val folder1: Folder = testApp.service.folder.add("folder1")

    val assetWithFolder = testContext.makeAsset().copy(folderId=folder1.persistedId)
    val dataAsset = testContext.makeAssetWithData(asset=Some(assetWithFolder))
    val persistedAsset: Asset = testApp.service.library.addAsset(dataAsset)

    // recycle the asset
    testApp.service.library.recycleAssets(Set(persistedAsset.persistedId))

    // import a new copy of it (should be allowed)
    testApp.service.library.addAsset(dataAsset)

    // now restore the previously deleted copy into itself
    intercept[DuplicateException] {
      testApp.service.library.restoreRecycledAssets(Set(persistedAsset.persistedId))
    }
  }

  test("Restoring an asset into a recycled folder should restore the folder") {
    val folder1: Folder = testApp.service.folder.add("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.library.deleteFolderById(folder1.persistedId)

    val deletedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    deletedFolder.isRecycled shouldBe true

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    val restoredFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    restoredFolder.isRecycled shouldBe false
  }
}
