package altitude.core.controller

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Asset
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

@DoNotDiscover class AssetControllerTests extends ControllerTestCore {

  test("Move assets to folder via PUT endpoint") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) { host =>
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
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) { host =>
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
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) { host =>
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
}
