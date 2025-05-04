package software.altitude.test.core.integration
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.FieldConst
import software.altitude.core.IllegalOperationException
import software.altitude.core.models._
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class LibraryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Rename asset and attempt to rename a recycled asset") {
    var asset: Asset = testContext.persistAsset()
    var updatedAsset: Asset = testApp.service.asset.rename(asset.persistedId, "newName")
    updatedAsset.fileName shouldBe "newName"

    // get the asset again to make sure it has been updated
    updatedAsset = testApp.service.asset.getById(asset.persistedId)
    updatedAsset.fileName shouldBe "newName"

    // attempt to rename a recycled asset
    asset = testApp.service.library.recycleAsset(asset.persistedId)

    intercept[IllegalOperationException] {
      testApp.service.asset.rename(asset.persistedId, "newName2")
    }
  }

  test("Folder filtering") {
    /*
    folder1
    folder2
      folder2_1
    */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")

    val folder2_1: Folder = testApp.service.folder.add(
      name = "folder2_1", parentId = folder2.id)

    // fill up the hierarchy with assets x times over
    1 to 2 foreach {n =>
      testContext.persistAsset(folder = Some(folder1))
      testContext.persistAsset(folder = Some(folder2))
      testContext.persistAsset(folder = Some(folder2_1))
    }

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 2

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2_1.persistedId))
    ).records.length shouldBe 2

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2.persistedId))
    ).records.length shouldBe 4
  }

  test("Move asset to a different folder") {
    /*
    folder1
    folder2
    */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 1

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder2.persistedId)

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 0

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2.persistedId))
    ).records.length shouldBe 1

    // SECOND REPO
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)

    testApp.service.library.query(new Query()).isEmpty shouldBe true

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).isEmpty shouldBe true
  }

  test("Move asset to same folder") {
    /*
    folder1
    folder2
    */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)

    // same but recycled
    testApp.service.library.recycleAsset(asset.persistedId)
    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)
  }
}
