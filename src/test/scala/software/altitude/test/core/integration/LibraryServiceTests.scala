package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.FieldConst
import software.altitude.core.IllegalOperationException
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.models._
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class LibraryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Folder counts should check out") {
    /*
    folder1
    folder2
      folder2_1
      folder2_2
        folder2_2_1
        folder2_2_2
    */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    val folder2_1: Folder = testApp.service.library.addFolder(
      name = "folder2_1", parentId = folder2.id)

    val folder2_2: Folder = testApp.service.library.addFolder(
      name = "folder2_2", parentId = folder2.id)

    val folder2_2_1: Folder = testApp.service.library.addFolder(
      name = "folder2_2_1", parentId = folder2_2.id)

    val folder2_2_2: Folder = testApp.service.library.addFolder(
      name = "folder2_2_2", parentId = folder2_2.id)

    // fill up the hierarchy with assets x times over
    1 to 2 foreach {_ =>
      testContext.persistAsset()
      testContext.persistAsset(folder = Some(folder1))
      testContext.persistAsset(folder = Some(folder2))
      testContext.persistAsset(folder = Some(folder2_1))
      testContext.persistAsset(folder = Some(folder2_2))
      testContext.persistAsset(folder = Some(folder2_2_1))
      testContext.persistAsset(folder = Some(folder2_2_2))
    }

    // prefetch all folders for speed
    val all = testApp.service.folder.repositoryFolders()

    // test counts for individual folders
    (testApp.service.folder.getByIdWithChildAssetCounts(folder1.persistedId, all): Folder).numOfAssets shouldBe 2
    (testApp.service.folder.getByIdWithChildAssetCounts(folder2_2_1.persistedId, all): Folder).numOfAssets shouldBe 2
    (testApp.service.folder.getByIdWithChildAssetCounts(folder2_2_2.persistedId, all): Folder).numOfAssets shouldBe 2
    (testApp.service.folder.getByIdWithChildAssetCounts(folder2_2.persistedId, all): Folder).numOfAssets shouldBe 6
    (testApp.service.folder.getByIdWithChildAssetCounts(folder2_1.persistedId, all): Folder).numOfAssets shouldBe 2
    (testApp.service.folder.getByIdWithChildAssetCounts(folder2.persistedId, all): Folder).numOfAssets shouldBe 10

    // test counts for immediate children
    val rootChildren = testApp.service.folder.immediateChildren(RequestContext.getRepository.rootFolderId, all)
    rootChildren.head.numOfAssets shouldBe 2
    rootChildren.last.numOfAssets shouldBe 10

    val rootChildren2 = testApp.service.folder.immediateChildren(RequestContext.getRepository.rootFolderId)
    rootChildren2.head.numOfAssets shouldBe 2
    rootChildren2.last.numOfAssets shouldBe 10

    // test counts for hierarchy
    val hierarchy = testApp.service.folder.hierarchy()
    hierarchy.head.numOfAssets shouldBe 2
    hierarchy.last.numOfAssets shouldBe 10
  }

  test("Rename asset and attempt to rename a recycled asset") {
    var asset: Asset = testContext.persistAsset()
    var updatedAsset: Asset = testApp.service.library.renameAsset(asset.persistedId, "newName")
    updatedAsset.fileName shouldBe "newName"

    // get the asset again to make sure it has been updated
    updatedAsset = testApp.service.library.getById(asset.persistedId)
    updatedAsset.fileName shouldBe "newName"

    // attempt to rename a recycled asset
    asset = testApp.service.library.recycleAsset(asset.persistedId)

    intercept[IllegalOperationException] {
      testApp.service.library.renameAsset(asset.persistedId, "newName2")
    }
  }

  test("Move recycled asset to folder") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.library.recycleAsset(asset.persistedId)
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    val folder1: Folder = testApp.service.library.addFolder("folder1")

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
    testApp.service.asset.query(new Query()).records.length shouldBe 1

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 1

    val all = testApp.service.folder.repositoryFolders()

    (testApp.service.folder.getByIdWithChildAssetCounts(folder1.persistedId, all): Folder).numOfAssets shouldBe 1
  }

  test("Search by folder hierarchy should return assets in sub-folders") {
    /*
  folder1
    folder1_1
    folder1_2
  */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    folder1_1.parentId should not be None

    val folder1_2: Folder = testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    val mediaType = new AssetType(mediaType = "mediaType", mediaSubtype = "mediaSubtype", mime = "mime")

    testContext.persistAsset(folder = Some(folder1_1))
    testContext.persistAsset(folder = Some(folder1_2))
    testContext.persistAsset(folder = Some(folder1))

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1_2.persistedId))
    ).records.length shouldBe 1

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1_1.persistedId))
    ).records.length shouldBe 1

    testApp.service.library.query(
      new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
    ).records.length shouldBe 3
  }

  test("Folder filtering") {
    /*
    folder1
    folder2
      folder2_1
    */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    val folder2_1: Folder = testApp.service.library.addFolder(
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
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder2: Folder = testApp.service.library.addFolder("folder2")

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
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)

    // same but recycled
    testApp.service.library.recycleAsset(asset.persistedId)
    testApp.service.library.moveAssetToFolder(asset.persistedId, folder1.persistedId)
  }

  test("Recycle asset") {
    testContext.persistAsset()

    // SECOND USER
    val user2 = testContext.persistUser()
    testApp.service.user.switchContextToUser(user2)

    testContext.persistAsset(user = Some(user2))

    // FIRST USER
    switchContextUser(testContext.users.head)
    testApp.service.asset.query(new Query()).records.length shouldBe 2

    val asset: Asset = testApp.service.asset.query(new Query()).records.head
    testApp.service.library.recycleAsset(asset.persistedId)

    testApp.service.asset.query(new Query()).records.length shouldBe 1
    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 1

    // SECOND REPO
    val repo2 = testContext.persistRepository(user=Some(user2))
    switchContextRepo(repo2)

    testApp.service.asset.queryRecycled(new Query()).records.length shouldBe 0
  }

  test("Get recycled asset") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAsset(asset.persistedId)
  }

  test("Restore recycled asset") {
    val asset: Asset = testContext.persistAsset()
    val trashed: Asset = testApp.service.library.recycleAsset(asset.persistedId)
    testApp.service.library.restoreRecycledAsset(trashed.persistedId)
    testApp.service.asset.query(new Query()).isEmpty shouldBe false
  }

  test("Restore recycled asset to non-existing folder") {
    val asset: Asset = testContext.persistAsset()
    testApp.service.library.recycleAsset(asset.persistedId)

    intercept[NotFoundException] {
      testApp.service.library.moveAssetToFolder(asset.persistedId, "bad")
    }
  }

  test("Recycle folder assets") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    val folder2_1: Folder = testApp.service.library.addFolder(
      name = "folder2_1", parentId = folder2.id)

    var asset1: Asset = testContext.persistAsset(folder=Some(folder1))
    var asset2: Asset = testContext.persistAsset(folder=Some(folder2))
    var asset3: Asset = testContext.persistAsset(folder=Some(folder2_1))

    testApp.service.library.deleteFolderById(folder1.persistedId)
    testApp.service.library.deleteFolderById(folder2.persistedId)

    asset1 = testApp.service.library.getById(asset1.persistedId)
    asset2 = testApp.service.library.getById(asset2.persistedId)
    asset3 = testApp.service.library.getById(asset3.persistedId)

    asset1.isRecycled shouldBe true
    asset2.isRecycled shouldBe true
    asset3.isRecycled shouldBe true
  }

  test("Recycle non-existent folder") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")
    testApp.service.library.deleteFolderById(folder1.persistedId)

    intercept[NotFoundException] {
      testApp.service.library.deleteFolderById(folder1.persistedId)
    }
  }

  test("Restore an asset that was imported again") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val dataAsset = testContext.makeAssetWithData(folder=Some(folder1))
    val persistedAsset: Asset = testApp.service.library.add(dataAsset)

    // recycle the asset
    testApp.service.library.recycleAsset(persistedAsset.persistedId)

    // import a new copy of it (should be allowed)
    testApp.service.library.add(dataAsset)

    // now restore the previously deleted copy into itself
    intercept[DuplicateException] {
      testApp.service.library.restoreRecycledAsset(persistedAsset.persistedId)
    }
  }

  test("Deleting a folder recycles all assets and marks folder as recycled") {
    val folder1: Folder = testApp.service.library.addFolder(
      "folder")
    val folder1_1: Folder = testApp.service.library.addFolder(
      "folder1_1", parentId = folder1.id)
    val folder1_1_1: Folder = testApp.service.library.addFolder(
      "folder1_1_1", parentId = folder1_1.id)

    testContext.persistAsset(folder=Some(folder1))
    testContext.persistAsset(folder=Some(folder1_1))

    // delete the parent folder
    testApp.service.library.deleteFolderById(folder1.persistedId)

    // Folder 1 should stay as recycled, as it has an asset
    (testApp.service.folder.getById(folder1.persistedId): Folder).isRecycled shouldBe  true

    // Folder 1_1 should stay as recycled, as it has an asset
    (testApp.service.folder.getById(folder1_1.persistedId): Folder).isRecycled shouldBe  true

    // Folder 1_1_1 should be gone, as it had no assets in it, recycled or otherwise
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1_1.persistedId)
    }
  }

  test("Deleting a folder referenced by recycled assets marks folder as recycled") {
    val folder1: Folder = testApp.service.library.addFolder(
      "folder")
    val folder1_1: Folder = testApp.service.library.addFolder(
      "folder1_1", parentId = folder1.id)
    val folder1_1_1: Folder = testApp.service.library.addFolder(
      "folder1_1_1", parentId = folder1_1.id)

    val asset1: Asset = testContext.persistAsset(folder=Some(folder1))
    val asset2: Asset = testContext.persistAsset(folder=Some(folder1_1))

    testApp.service.library.recycleAsset(asset1.persistedId)
    testApp.service.library.recycleAsset(asset2.persistedId)

    // delete the parent folder
    testApp.service.library.deleteFolderById(folder1.persistedId)

    // Folder 1 should stay as recycled, as it has an asset
    (testApp.service.folder.getById(folder1.persistedId): Folder).isRecycled shouldBe  true

    // Folder 1_1 should stay as recycled, as it has an asset
    (testApp.service.folder.getById(folder1_1.persistedId): Folder).isRecycled shouldBe  true

    // Folder 1_1_1 should be gone, as it had no assets in it, recycled or otherwise
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1_1.persistedId)
    }
  }

  test("Restoring an asset into a recycled folder should recreate the folder") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))
    testApp.service.library.recycleAsset(asset.persistedId)

    testApp.service.library.deleteFolderById(folder1.persistedId)

    val deletedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    deletedFolder.isRecycled shouldBe true

    testApp.service.library.restoreRecycledAsset(asset.persistedId)

    val restoredFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    restoredFolder.isRecycled shouldBe false
  }
}
