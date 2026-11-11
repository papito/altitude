package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Folder
import altitude.core.models.Repository

@DoNotDiscover class FolderActionControllerTests extends ControllerTestCore {

  test("Add folder dialog renders") {

    /**
     * Setup:
     *
     * A logged-in user's repository, with its root folder as the new folder's parent.
     *
     * Assertions:
     *
     * The add folder dialog renders its form.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository with one folder, "to-rename".
     *
     * Assertions:
     *
     * The rename folder dialog renders its form with the folder's name.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository with one folder, "to-delete".
     *
     * Assertions:
     *
     * The delete folder dialog renders and names the folder.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository, and a blank folder name posted to the add endpoint under the root folder.
     *
     * Assertions:
     *
     * The response is the add dialog's form carrying the error, retargeted to replace the submitted form in place.
     */
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

    /**
     * Setup:
     *
     * A logged-in user's repository with one folder, "to-rename", and a blank name put to the rename endpoint for it.
     *
     * Assertions:
     *
     * The response is the rename dialog's form carrying the error, retargeted to replace the submitted form in place.
     */
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

  /** A request of a folder route, through the first repository, with the folder route's JSON body when it has one */
  private def folderRoute(
      method: requests.Requester,
      host: String,
      repoId: String,
      path: String,
      params: Map[String, String] = Map.empty,
      body: Option[ujson.Obj] = None): requests.Response = {
    val url = s"$host/htmx/folder/r/$repoId/$path"
    body match {
      case Some(json) =>
        method(
          url,
          params = params,
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(json),
          cookies = testContext.cookies,
          check = false)
      case None => method(url, params = params, cookies = testContext.cookies, check = false)
    }
  }

  /** A second repository of the logged-in user's holding the folder "Eve's folder"; the context is `repo` again on return */
  private def othersFolder(repo: Repository): (Repository, Folder) = {
    val otherRepo = testContext.persistRepository()
    testApp.service.repository.switchContextToRepository(otherRepo)
    val folder: Folder = testApp.service.folder.add("Eve's folder")
    testApp.service.repository.switchContextToRepository(repo)
    (otherRepo, folder)
  }

  test("Every folder route that takes a folder ID is a 404 for an unknown ID and for another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder "Mine", and a second repository of theirs with the folder "Eve's folder".
     *
     * Assertions:
     *
     * Asked through the first repository, the rename and delete dialogs, the rename, the move of the folder into "Mine" and the
     * delete answer 404 for a folder ID that does not exist and for "Eve's folder", and so does the move of the second
     * repository's root folder. No answer carries the other repository's folder name, and no row of either repository changes.
     *
     * Edge cases:
     *
     * Moving "Mine" into "Eve's folder" is a 400, the answer to a destination that does not exist, which moving it into an
     * unknown folder also gets.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val mine: Folder = testApp.service.folder.add("Mine")
        val (otherRepo, eves) = othersFolder(repo)
        val unknownId = java.util.UUID.randomUUID.toString
        val ownBefore = testContext.rowsOf(repo)
        val othersBefore = testContext.rowsOf(otherRepo)

        def move(movedId: String, destId: String): requests.Response =
          folderRoute(
            requests.put,
            host,
            repoId,
            "move",
            Map(Api.Field.Folder.MOVED_FOLDER_ID -> movedId, Api.Field.Folder.NEW_PARENT_ID -> destId))

        for (id <- List(unknownId, eves.persistedId)) withClue(s"[$id]: ") {
          val answers = List(
            folderRoute(requests.get, host, repoId, "dialogs/rename-folder", Map("id" -> id)),
            folderRoute(requests.get, host, repoId, "dialogs/delete-folder", Map("id" -> id)),
            folderRoute(
              requests.put,
              host,
              repoId,
              "rename",
              body = Some(ujson.Obj(Api.Field.Folder.NAME -> "Renamed", Api.Field.ID -> id))),
            move(id, mine.persistedId),
            folderRoute(requests.delete, host, repoId, "", Map("id" -> id))
          )
          for (answer <- answers) {
            answer.statusCode shouldBe 404
            (answer.text() should not).include("Eve")
          }
        }

        val othersRoot = move(otherRepo.rootFolderId, mine.persistedId)
        othersRoot.statusCode shouldBe 404
        (othersRoot.text() should not).include("Eve")

        for (destId <- List(unknownId, eves.persistedId)) withClue(s"[$destId]: ") {
          val answer = move(mine.persistedId, destId)
          answer.statusCode shouldBe 400
          (answer.text() should not).include("Eve")
        }

        testContext.rowsOf(repo) shouldBe ownBefore
        testContext.rowsOf(otherRepo) shouldBe othersBefore
    }
  }

  test("A folder is not added under another repository's folder, nor under one that does not exist") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs with the folder "Eve's folder".
     *
     * Assertions:
     *
     * Adding the folder "New" through the first repository under "Eve's folder", or under a folder ID that does not exist,
     * answers the add dialog's form again in place of the submitted one, saying the parent folder does not exist, and naming
     * nothing of the other repository. No row of either repository changes.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val (otherRepo, eves) = othersFolder(repo)
        val ownBefore = testContext.rowsOf(repo)
        val othersBefore = testContext.rowsOf(otherRepo)

        for (parentId <- List(java.util.UUID.randomUUID.toString, eves.persistedId)) withClue(s"[$parentId]: ") {
          val response = folderRoute(
            requests.post,
            host,
            repoId,
            "add",
            body = Some(ujson.Obj(Api.Field.Folder.NAME -> "New", Api.Field.Folder.PARENT_ID -> parentId)))

          response.statusCode shouldBe 200
          response.headers("hx-retarget") shouldBe Seq("this")
          response.text() should include("""id="addFolder"""")
          response.text() should include(Const.Msg.Err.PARENT_FOLDER_NOT_FOUND)
          (response.text() should not).include("Eve")
        }

        testContext.rowsOf(repo) shouldBe ownBefore
        testContext.rowsOf(otherRepo) shouldBe othersBefore
    }
  }
}
