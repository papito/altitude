package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Asset

@DoNotDiscover class AssetControllerTests extends ControllerTestCore {

  test("Move assets to folder via PUT endpoint") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder "target-folder" and two imported assets in the root folder.
     *
     * Assertions:
     *
     * The move endpoint puts both assets in the folder, and neither ends up recycled.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        // Create a folder to move assets to
        val targetFolder = testApp.service.folder.add("target-folder")

        // Create test assets
        val asset1 = testContext.persistAsset()
        val asset2 = testContext.persistAsset()

        val assetIds = Seq(asset1.persistedId, asset2.persistedId)

        val payload = ujson.Obj(
          Api.Field.FOLDER_ID -> targetFolder.persistedId,
          Api.Field.ASSET_IDS -> assetIds
        )

        val response = requests.put(
          s"$host/api/asset/r/$repoId/move",
          cookies = testContext.cookies,
          data = ujson.write(payload)
        )

        response.statusCode.shouldBe(200)

        // Verify assets were moved to the target folder
        val movedAsset1: Asset = testApp.service.asset.getById(asset1.persistedId)
        val movedAsset2: Asset = testApp.service.asset.getById(asset2.persistedId)

        movedAsset1.folderId.shouldBe(targetFolder.persistedId)
        movedAsset2.folderId.shouldBe(targetFolder.persistedId)
        movedAsset1.isRecycled.shouldBe(false)
        movedAsset2.isRecycled.shouldBe(false)
    }
  }

  test("Move assets to trash via DELETE endpoint") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two imported assets.
     *
     * Assertions:
     *
     * A DELETE on the move endpoint recycles both assets.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        // Create test assets
        val asset1 = testContext.persistAsset()
        val asset2 = testContext.persistAsset()

        val assetIds = Seq(asset1.persistedId, asset2.persistedId)

        val payload = ujson.Obj(
          Api.Field.ASSET_IDS -> assetIds
        )

        val response = requests.delete(
          s"$host/api/asset/r/$repoId/move",
          cookies = testContext.cookies,
          data = ujson.write(payload)
        )

        response.statusCode.shouldBe(200)

        // Verify assets were recycled
        val recycledAsset1: Asset = testApp.service.asset.getById(asset1.persistedId)
        val recycledAsset2: Asset = testApp.service.asset.getById(asset2.persistedId)

        recycledAsset1.isRecycled.shouldBe(true)
        recycledAsset2.isRecycled.shouldBe(true)
    }
  }

  test("Moving recycled asset to folder should restore it") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one imported asset, recycled before the request, and the folder "restore-folder".
     *
     * Assertions:
     *
     * Moving the recycled asset into the folder restores it there.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        // Create and recycle an asset
        val asset = testContext.persistAsset()
        testApp.service.library.recycleAssets(Set(asset.persistedId))

        val recycledAsset: Asset = testApp.service.asset.getById(asset.persistedId)
        recycledAsset.isRecycled.shouldBe(true)

        // Now move it to a folder (which should restore it)
        val targetFolder = testApp.service.folder.add("restore-folder")

        val payload = ujson.Obj(
          Api.Field.FOLDER_ID -> targetFolder.persistedId,
          Api.Field.ASSET_IDS -> Seq(asset.persistedId)
        )

        val response = requests.put(
          s"$host/api/asset/r/$repoId/move",
          cookies = testContext.cookies,
          data = ujson.write(payload)
        )

        response.statusCode.shouldBe(200)

        // Verify asset was restored and moved
        val restoredAsset: Asset = testApp.service.asset.getById(asset.persistedId)
        restoredAsset.folderId.shouldBe(targetFolder.persistedId)
        restoredAsset.isRecycled.shouldBe(false)
    }
  }

  test("Restoring assets reports the restored ones and the ones whose content is live again") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two recycled assets: one whose content exists nowhere else, and one whose same file is
     * imported again after it was recycled.
     *
     * Assertions:
     *
     * The restore endpoint answers with the IDs it restored and, apart from them, the IDs it left in the trash because their
     * content is live again.
     *
     * Edge cases:
     *
     * The second copy of a file, imported after the first was recycled, keeps the first from being restored.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val restorable = testContext.persistAsset()

        val dataAsset = testContext.makeAssetWithData()
        val secondCopy = dataAsset.copy(path = testApp.service.staging.stageCopy(dataAsset.path))
        val duplicate: Asset = testApp.service.library.addAsset(dataAsset)

        testApp.service.library.recycleAssets(Set(restorable.persistedId, duplicate.persistedId))
        testApp.service.library.addAsset(secondCopy)

        val payload = ujson.Obj(Api.Field.ASSET_IDS -> Seq(restorable.persistedId, duplicate.persistedId))

        val response = requests.put(
          s"$host/api/asset/r/$repoId/restore",
          cookies = testContext.cookies,
          data = ujson.write(payload)
        )

        response.statusCode.shouldBe(200)
        val json = ujson.read(response.text())
        json(Api.Field.Asset.RESTORED).arr.map(_.str).toList.shouldBe(List(restorable.persistedId))
        json(Api.Field.Asset.DUPLICATES).arr.map(_.str).toList.shouldBe(List(duplicate.persistedId))
    }
  }
}
