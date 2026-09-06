package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

import altitude.core.App
import altitude.core.models.Folder

@DoNotDiscover class FolderControllerTests extends ControllerTestCore {

  test("Folder tree JSON carries recursive asset counts") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val folder1: Folder = testApp.service.folder.add("folder1")
        val folder1_1: Folder = testApp.service.folder.add("folder1_1", parentId = folder1.id)

        testContext.persistAsset(folder = Some(folder1_1))
        testContext.persistAsset(folder = Some(folder1_1))
        testContext.persistAsset(folder = Some(folder1))
        testContext.persistAsset(isTriaged = true)

        val response = requests.get(s"$host/api/folder/r/$repoId/tree", cookies = testContext.cookies)

        response.statusCode shouldBe 200
        response.headers("content-type").head should include("application/json")

        val root = ujson.read(response.text())
        root("isRoot").bool shouldBe true
        root("numOfAssets").num shouldBe 3

        val folder1Json = root("children")(0)
        folder1Json("id").str shouldBe folder1.persistedId
        folder1Json("numOfAssets").num shouldBe 3
        folder1Json("numOfChildren").num shouldBe 1

        val folder1_1Json = folder1Json("children")(0)
        folder1_1Json("id").str shouldBe folder1_1.persistedId
        folder1_1Json("numOfAssets").num shouldBe 2
        folder1_1Json("numOfChildren").num shouldBe 0
    }
  }
}
