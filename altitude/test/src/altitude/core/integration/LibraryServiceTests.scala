package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.models.*
import altitude.core.util.Query

@DoNotDiscover class LibraryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Rename asset and attempt to rename a recycled asset") {

    /**
     * Setup:
     *
     * One asset in the root folder, renamed, then recycled.
     *
     * Assertions:
     *
     * The new name shows on the returned asset and on a fresh read, and renaming the asset once it is recycled is refused.
     *
     * Edge cases:
     *
     * A recycled asset cannot be renamed.
     */
    val asset: Asset = testContext.persistAsset()
    var updatedAsset: Asset = testApp.service.asset.rename(asset.persistedId, "newName")
    updatedAsset.fileName shouldBe "newName"

    // get the asset again to make sure it has been updated
    updatedAsset = testApp.service.asset.getById(asset.persistedId)
    updatedAsset.fileName shouldBe "newName"

    // attempt to rename a recycled asset
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[IllegalOperationException] {
      testApp.service.asset.rename(asset.persistedId, "newName2")
    }
  }

  test("Folder filtering") {

    /**
     * Setup:
     *
     * Two top-level folders and a child of the second, with two assets in each:
     *
     * folder1
     * folder2
     *   folder2_1
     *
     * Assertions:
     *
     * A folder filter matches the assets of the folder and of all of its descendants: two for each leaf folder, four for the
     * parent.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")

    val folder2_1: Folder = testApp.service.folder.add(name = "folder2_1", parentId = folder2.id)

    // fill up the hierarchy with assets x times over
    (1 to 2).foreach {
      n =>
        testContext.persistAsset(folder = Some(folder1))
        testContext.persistAsset(folder = Some(folder2))
        testContext.persistAsset(folder = Some(folder2_1))
    }

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
      )
      .records
      .length shouldBe 2

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2_1.persistedId))
      )
      .records
      .length shouldBe 2

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2.persistedId))
      )
      .records
      .length shouldBe 4
  }

  test("Move assets between folders") {

    /**
     * Setup:
     *
     * Three folders with three assets in each; ALL nine assets are moved into the last folder.
     *
     * Assertions:
     *
     * The move completes without an exception; nothing else is checked.
     *
     * Edge cases:
     *
     * The selection includes the three assets already in the destination folder.
     */
    val folders = (1 to 3).map(n => testApp.service.folder.add(s"folder$n"))

    val assets = folders.flatMap(folder => (1 to 3).map(_ => testContext.persistAsset(folder = Some(folder)))).toList

    testApp.service.library.moveAssetsToFolder(assets.map(_.persistedId).toSet, folders.last.persistedId)
  }

  test("Move asset to a different folder") {

    /**
     * Setup:
     *
     * Two folders with one asset in the first, which is moved into the second; then a second, empty repository is switched to.
     *
     * Assertions:
     *
     * The asset leaves the first folder's results and shows in the second's, and the second repository sees none of the first
     * repository's assets, even filtered by the first repository's folder.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")

    val asset: Asset = testContext.persistAsset(folder = Some(folder1))

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
      )
      .records
      .length shouldBe 1

    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folder2.persistedId)

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
      )
      .records
      .length shouldBe 0

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder2.persistedId))
      )
      .records
      .length shouldBe 1

    // SECOND REPO
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)

    testApp.service.library.query(new Query()).isEmpty shouldBe true

    testApp.service.library
      .query(
        new Query(Map(FieldConst.Asset.FOLDER_ID -> folder1.persistedId))
      )
      .isEmpty shouldBe true
  }

}
