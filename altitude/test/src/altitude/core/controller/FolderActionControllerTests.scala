package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Folder

@DoNotDiscover class FolderActionControllerTests extends ControllerTestCore {

  test("Add folder dialog renders") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/htmx/folder/r/$repoId/dialogs/add-folder",
          params = Map("parentId" -> testContext.repository.rootFolderId),
          cookies = testContext.cookies,
          check = false
        )

        response.statusCode shouldBe 200
        response.text() should include("""id="addFolder"""")
    }
  }

  test("Rename folder dialog renders") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val folder: Folder = testApp.service.folder.add("to-rename")

        val response = requests.get(
          s"$host/htmx/folder/r/$repoId/dialogs/rename-folder",
          params = Map("id" -> folder.persistedId),
          cookies = testContext.cookies,
          check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="renameFolder"""")
        response.text() should include(folder.name)
    }
  }

  test("Delete folder dialog renders") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val folder: Folder = testApp.service.folder.add("to-delete")

        val response = requests.get(
          s"$host/htmx/folder/r/$repoId/dialogs/delete-folder",
          params = Map("id" -> folder.persistedId),
          cookies = testContext.cookies,
          check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="deleteFolder"""")
        response.text() should include(folder.name)
    }
  }

  test("Add folder validation error replaces the dialog form in place") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val payload = ujson.Obj(
          Api.Field.Folder.NAME -> "",
          Api.Field.Folder.PARENT_ID -> testContext.repository.rootFolderId
        )

        val response = requests.post(
          s"$host/htmx/folder/r/$repoId/add",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(payload),
          cookies = testContext.cookies,
          check = false
        )

        response.statusCode shouldBe 200
        response.headers("hx-retarget") shouldBe Seq("this")
        response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
        response.text() should include("""id="addFolder"""")
        response.text() should include("""class="error"""")
    }
  }

  test("Rename folder validation error replaces the dialog form in place") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val folder: Folder = testApp.service.folder.add("to-rename")
        val payload = ujson.Obj(
          Api.Field.Folder.NAME -> "",
          Api.Field.ID -> folder.persistedId
        )

        val response = requests.put(
          s"$host/htmx/folder/r/$repoId/rename",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(payload),
          cookies = testContext.cookies,
          check = false
        )

        response.statusCode shouldBe 200
        response.headers("hx-retarget") shouldBe Seq("this")
        response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
        response.text() should include("""id="renameFolder"""")
        response.text() should include("""class="error"""")
    }
  }
}
