package altitude.core.controller

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import java.net.HttpCookie
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.AccountType
import altitude.core.models.Folder
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.WatchFolder
import altitude.core.models.WatchFolderSpec

/**
 * The Watch folders of the Import page: the cards an Import holder reads and an Admin acts on, the Watch folder dialog and its
 * refusals, the notice of what is deleted, the cards' actions, and the folder delete confirmation's warning. Every directory a
 * test watches is a fresh one under the suite's own temporary directory.
 */
@DoNotDiscover class WatchFolderControllerTests extends ControllerTestCore {

  private val F = Api.Field.WatchFolder
  private def watchFolders = testApp.service.watchFolder

  // The suite's own temporary directory, which holds every directory a test watches and goes with the suite
  private var tempRoot: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit = {
    super.beforeAll()
    tempRoot = Files.createTempDirectory("altitude-watch-folder-routes")
  }

  override def afterAll(): Unit =
    try FileUtils.deleteDirectory(tempRoot.toFile)
    finally super.afterAll()

  private def directory(): Path = Files.createTempDirectory(tempRoot, "watched").toRealPath()

  private def image(dir: Path, name: String): Path =
    Files.write(dir.resolve(name), IntegrationTestUtil.generateRandomImagBytesBgr())

  private def route(host: String, repoId: String, path: String): String = s"$host/htmx/watch-folders/r/$repoId/$path"

  private def send(
      method: String,
      url: String,
      body: ujson.Obj = ujson.Obj(),
      cookies: Map[String, HttpCookie] = testContext.cookies): requests.Response =
    requests.send(method)(
      url,
      // Identity, because the client cannot read a compressed empty body (a 204)
      headers = Map("Content-Type" -> "application/json", "Accept-Encoding" -> "identity"),
      data = ujson.write(body),
      cookies = cookies,
      check = false,
      maxRedirects = 0
    )

  private def get(url: String, cookies: Map[String, HttpCookie] = testContext.cookies): requests.Response =
    requests.get(url, cookies = cookies, check = false, maxRedirects = 0)

  private def encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** The body of an action that names a Watch folder */
  private def named(id: String): ujson.Obj = ujson.Obj(F.WATCH_FOLDER_ID -> id)

  /** The Watch folder dialog's body, as the form posts it: a switch is the text of its hidden input */
  private def form(
      name: String,
      path: String,
      keepsStructure: Boolean = false,
      destFolderId: String = "",
      deletesImported: Boolean = false): ujson.Obj =
    ujson.Obj(
      F.NAME -> name,
      F.PATH -> path,
      F.KEEPS_STRUCTURE -> keepsStructure.toString,
      F.DEST_FOLDER_ID -> destFolderId,
      F.DELETES_IMPORTED -> deletesImported.toString
    )

  /** A Watch folder of the Library on a fresh directory, added as the test context's account, which must be an Admin */
  private def watch(name: String, destination: Option[Folder] = None, deletesImported: Boolean = false): WatchFolder =
    watchFolders.add(WatchFolderSpec(name, directory().toString, destination.flatMap(_.id), deletesImported))

  /** The card of the Watch folder in a rendering of the cards */
  private def cardOf(html: String, watchFolder: WatchFolder): String =
    html.split(s"""data-watch-folder-id="${watchFolder.persistedId}"""")(1).split("</article>")(0)

  /** A User of the Library holding `permissions` there, logged in to the server at `host`: their cookies */
  private def userCookies(host: String, repository: Repository, permissions: Permissions): Map[String, HttpCookie] = {
    val user = testContext.persistUser()
    testContext.grant(user, repository, permissions)
    cookiesOf(host, user)
  }

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  test("The Import page shows the Library's Watch folders: an Admin's cards with their actions, an Import holder's without") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library that has the folder "Scans" and two Watch folders of the Admin's: "Phone sync", to Triage,
     * and "Scanner", which sorts into "Scans", deletes imported files and is paused. A User who holds Import alone in the
     * Library, and one who holds every Permission but Import.
     *
     * Assertions:
     *
     * The Admin's page has the Watch folders section with "Add Watch folder", and a card per Watch folder with its name, its
     * path, where its files go ("Triage", or "Scans"), its status chip (Watching, Paused), no file imported, and its actions:
     * "Scan now", which is disabled on the paused card, and a menu of Pause (Resume on the paused card), Retry failed, Edit and
     * Remove. "Scanner" says it deletes imported files and, being paused, that it was not scanned yet. The cards' route answers
     * the same cards.
     *
     * Edge cases:
     *
     * The Import holder's page and cards route show both cards, with their statuses, and no action: no "Add Watch folder", no
     * "Scan now", no menu. The account without Import is refused the cards route with a 403.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val repository = testContext.repository
    val scans = testApp.service.folder.add("Scans")
    val phoneSync = watch("Phone sync")
    val scanner = watch("Scanner", destination = Some(scans), deletesImported = true)
    watchFolders.pause(scanner.persistedId)

    withServer(App) {
      host =>
        val page = get(s"$host/pipeline/r/$repoId")
        page.statusCode shouldBe 200
        val html = page.text()
        html should include("""<h2 id="watchFoldersTitle">Watch folders</h2>""")
        html should include("""id="addWatchFolderBtn"""")
        html should include(s"""data-app-view-url="/htmx/watch-folders/r/$repoId/list"""")

        val listed = get(route(host, repoId, "list"))
        listed.statusCode shouldBe 200

        for (cards <- List(html, listed.text())) {
          val phone = cardOf(cards, phoneSync)
          phone should include("<h3>Phone sync</h3>")
          phone should include(phoneSync.path)
          phone should include("into Triage")
          phone should include("""<span class="status-chip watching">Watching</span>""")
          phone should include("""<span class="imported">0</span> imported""")
          (phone should not).include("deletes imported files")
          phone should include(">Scan now</button>")
          (phone should not).include("disabled")
          phone should include(s"""id="${Const.WatchFolder.menuButtonId(phoneSync.persistedId)}"""")
          for (action <- List("Pause", "Retry failed", "Edit…", "Remove…")) phone should include(s">$action</button>")
          (phone should not).include(">Resume</button>")

          val scan = cardOf(cards, scanner)
          scan should include("<h3>Scanner</h3>")
          (scan should include).regex("""into <i class="fas fa-folder" aria-hidden="true"></i> Scans""")
          scan should include("""<span class="status-chip paused">Paused</span>""")
          // The server scans on its own every interval, but never a paused Watch folder
          scan should include("not scanned yet")
          scan should include("deletes imported files")
          (scan should include).regex("""class="action-button small" disabled>Scan now</button>""")
          scan should include(">Resume</button>")
          (scan should not).include(">Pause</button>")
        }

        val importer = userCookies(host, repository, Permissions.of(Permission.Import))
        for (response <- List(get(s"$host/pipeline/r/$repoId", importer), get(route(host, repoId, "list"), importer))) {
          response.statusCode shouldBe 200
          val readOnly = response.text()
          cardOf(readOnly, phoneSync) should include("""<span class="status-chip watching">Watching</span>""")
          cardOf(readOnly, scanner) should include("""<span class="status-chip paused">Paused</span>""")
          (readOnly should not).include("addWatchFolderBtn")
          (readOnly should not).include("Scan now")
          (readOnly should not).include("watchFolderMenuBtn")
          (readOnly should not).include("Remove…")
        }

        val others = Permissions(Permissions.all.mask & ~Permissions.of(Permission.Import).mask)
        get(route(host, repoId, "list"), userCookies(host, repository, others)).statusCode shouldBe 403
    }
  }

  test("Every write of a Watch folder, and each of its dialogs, is an Admin's") {

    /**
     * Setup:
     *
     * A Library with a Watch folder of an Admin's, and a User who holds every Permission in the Library, logged in.
     *
     * Assertions:
     *
     * The User is refused, with a 403, the Add and Edit dialogs, the add, the edit, the notice of what is deleted, Pause, Resume,
     * Scan now, Retry failed, the Remove dialog and the removal. The Watch folder is as it was, and the Library has no other.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val repository = testContext.repository
    val watchFolder = watch("Phone sync")
    val id = watchFolder.persistedId
    val another = directory().toString

    withServer(App) {
      host =>
        val user = userCookies(host, repository, Permissions.all)
        def at(path: String) = route(host, repoId, path)

        val refused = List(
          get(at("dialogs/add"), user),
          get(at(s"dialogs/edit?${F.WATCH_FOLDER_ID}=$id"), user),
          send("POST", at("add"), form("Another", another), user),
          send("PUT", at("edit"), ujson.Obj.from(form("Renamed", watchFolder.path).value ++ named(id).value), user),
          get(at(s"deletion-notice?path=${encoded(watchFolder.path)}"), user),
          send("PUT", at("pause"), named(id), user),
          send("PUT", at("resume"), named(id), user),
          send("PUT", at("scan"), named(id), user),
          send("PUT", at("retry"), named(id), user),
          get(at(s"dialogs/remove?${F.WATCH_FOLDER_ID}=$id"), user),
          send("DELETE", at(s"watch-folder?${F.WATCH_FOLDER_ID}=$id"), cookies = user)
        )

        refused.map(_.statusCode) shouldBe List.fill(refused.size)(403)
    }

    val List(stored) = watchFolders.cards().map(_.watchFolder)
    (stored.id, stored.name, stored.path, stored.isPaused) shouldBe (watchFolder.id, "Phone sync", watchFolder.path, false)
  }

  test("An Admin adds and edits a Watch folder through the dialog, and a refused field comes back under its field") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library with the folder "Scans", and an empty directory on the server.
     *
     * Assertions:
     *
     * The Add dialog is a form titled "Add Watch folder" with the three fieldsets, both switches off and "Save" as its submit. An
     * add with a relative path comes back as the form, retargeted at itself, with the name and the path as submitted and the
     * path's error under its field, and adds nothing; so does an add that keeps the folder structure with no folder chosen, with
     * that error. An add with the directory, the structure kept under "Scans" and the deletion on answers that the Watch folder
     * was added, and it is stored so, owned by the Admin. Its Edit dialog is titled by its name and opens over what is stored:
     * the switches on, "Scans" as the chosen destination, the notice of what is deleted and "Save and delete imported files". An
     * edit that renames it and turns both switches off is stored, with no destination.
     *
     * Edge cases:
     *
     * An add whose destination is no folder of the Library is a 404.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val scans = testApp.service.folder.add("Scans")
    val dir = directory()

    withServer(App) {
      host =>
        def at(path: String) = route(host, repoId, path)

        val dialog = get(at("dialogs/add")).text()
        dialog should include("""data-app-modal-title="Add Watch folder"""")
        for (legend <- List("Source", "Destination", "After import")) dialog should include(s"<legend>$legend</legend>")
        dialog should include("""data-app-keeps-structure="false"""")
        dialog should include("""data-app-deletes-imported="false"""")
        (dialog should include).regex("""x-text="saveLabel">Save</button>""")
        (dialog should not).include("notice deletion")

        val relative = send("POST", at("add"), form("Phone sync", "relative/path"))
        relative.statusCode shouldBe 200
        relative.headers("hx-retarget") shouldBe List("this")
        relative.text() should include("""value="Phone sync"""")
        relative.text() should include("""value="relative/path"""")
        relative.text() should include(Const.Msg.Err.WATCH_PATH_RELATIVE)

        val noFolder = send("POST", at("add"), form("Phone sync", dir.toString, keepsStructure = true))
        noFolder.headers("hx-retarget") shouldBe List("this")
        noFolder.text() should include(Const.Msg.Err.DESTINATION_REQUIRED)
        noFolder.text() should include("""data-app-keeps-structure="true"""")
        watchFolders.cards() shouldBe empty

        send(
          "POST",
          at("add"),
          form(
            "Phone sync",
            dir.toString,
            keepsStructure = true,
            destFolderId = java.util.UUID.randomUUID.toString)).statusCode shouldBe 404

        val added =
          send(
            "POST",
            at("add"),
            form(" Phone sync ", dir.toString, keepsStructure = true, scans.persistedId, deletesImported = true))
        added.statusCode shouldBe 200
        added.headers.get("hx-retarget") shouldBe None
        added.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head should include("Watch folder Phone sync added")
        val List(stored) = watchFolders.cards().map(_.watchFolder)
        (stored.name, stored.path, stored.destFolderId, stored.deletesImported) shouldBe (
          "Phone sync",
          dir.toString,
          scans.id,
          true)
        stored.accountId shouldBe testContext.user.persistedId

        val edit = get(at(s"dialogs/edit?${F.WATCH_FOLDER_ID}=${stored.persistedId}")).text()
        edit should include("""data-app-modal-title="Edit Phone sync"""")
        edit should include(s"""name="${F.WATCH_FOLDER_ID}" value="${stored.persistedId}"""")
        edit should include("""data-app-keeps-structure="true"""")
        edit should include("""data-app-deletes-imported="true"""")
        edit should include(s"""name="${F.DEST_FOLDER_ID}" value="${scans.persistedId}"""")
        edit should include(s"""data-option-id="${scans.persistedId}"""")
        edit should include("<strong>No files are in this folder now.</strong>")
        (edit should include).regex("""x-text="saveLabel">Save and delete imported files</button>""")

        val body = ujson.Obj.from(form("Renamed", dir.toString).value ++ named(stored.persistedId).value)
        val edited = send("PUT", at("edit"), body)
        edited.statusCode shouldBe 200
        edited.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head should include("Watch folder Renamed saved")
        val now = watchFolders.getById(stored.persistedId)
        (now.name, now.destFolderId, now.deletesImported) shouldBe ("Renamed", None, false)
    }
  }

  test("The notice of what is deleted names the number of files a scan would find in the folder now") {

    /**
     * Setup:
     *
     * An Admin logged in, and a directory on the server holding two images, one of them in a subdirectory, a hidden file and an
     * empty file. A Watch folder of the Library is on another directory, which holds one image.
     *
     * Assertions:
     *
     * The notice for the first directory says that 2 files are in the folder now, and what is deleted. The notice for the Watch
     * folder's own directory, asked for its edit, says that 1 file is.
     *
     * Edge cases:
     *
     * For a path that cannot be a Watch folder's as it stands (one that does not exist, and the Watch folder's directory asked
     * for another one), the notice names no number.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val dir = directory()
    image(dir, "a.png")
    image(Files.createDirectory(dir.resolve("2026")), "b.png")
    image(dir, ".hidden.png")
    Files.createFile(dir.resolve("empty.png"))
    val watchFolder = watch("Phone sync")
    image(Path.of(watchFolder.path), "c.png")

    withServer(App) {
      host =>
        def notice(path: String, self: String = ""): String = {
          val response =
            get(route(host, repoId, s"deletion-notice?path=${encoded(path)}&${F.WATCH_FOLDER_ID}=$self"))
          response.statusCode shouldBe 200
          response.text()
        }

        val counted = notice(dir.toString)
        counted should include("<strong>2 files are in this folder now.</strong>")
        counted should include("Unsupported and failed files stay.")
        notice(watchFolder.path, watchFolder.persistedId) should include("<strong>1 file is in this folder now.</strong>")

        for (unusable <- List(notice(dir.resolve("missing").toString), notice(watchFolder.path))) {
          unusable should include("<strong>The files in this folder are deleted.</strong>")
          (unusable should not).include("in this folder now")
        }
    }
  }

  test("An Admin pauses, resumes, scans, retries and removes a Watch folder from its card") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library with a Watch folder on a directory that holds one image.
     *
     * Assertions:
     *
     * Pause answers 204 and the Watch folder is paused; "Scan now" is then a 409 that says it is paused. Resume answers 204 and
     * it is watching again. "Scan now" answers 204 at once, and the scan it started remembers the file; a second one reads the
     * file, which becomes an asset of the Library. Retry failed answers 204. The Remove dialog is titled by the Watch folder's
     * name and says that its files and what it imported stay; the removal answers that it was removed, the cards say the Library
     * has no Watch folder, and the image is still in the directory.
     *
     * Edge cases:
     *
     * An action with no Watch folder named, and one named by something that is no ID, is a 400.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val watchFolder = watch("Phone sync")
    val id = watchFolder.persistedId
    val file = image(Path.of(watchFolder.path), "a.png")

    withServer(App) {
      host =>
        def at(path: String) = route(host, repoId, path)
        def scanned(): Unit = {
          send("PUT", at("scan"), named(id)).statusCode shouldBe 204
          eventually(App.altitude.service.watchFolder.isWorking(id) shouldBe false)
        }

        send("PUT", at("pause"), named(id)).statusCode shouldBe 204
        watchFolders.getById(id).isPaused shouldBe true
        val paused = send("PUT", at("scan"), named(id))
        (paused.statusCode, paused.text()) shouldBe (409, Const.Msg.Err.WATCH_FOLDER_PAUSED)

        send("PUT", at("resume"), named(id)).statusCode shouldBe 204
        watchFolders.getById(id).isPaused shouldBe false

        scanned()
        eventually(watchFolders.memory(id).map(_.relPath) shouldBe List("a.png"))
        scanned()
        eventually(testApp.service.asset.getByChecksum(altitude.core.util.Sha256.hex(file)) should not be empty)
        eventually(App.altitude.service.watchFolder.isWorking(id) shouldBe false)

        send("PUT", at("retry"), named(id)).statusCode shouldBe 204
        send("PUT", at("pause")).statusCode shouldBe 400
        send("PUT", at("pause"), named("not-an-id")).statusCode shouldBe 400

        val dialog = get(at(s"dialogs/remove?${F.WATCH_FOLDER_ID}=$id")).text()
        dialog should include("""data-app-modal-title="Remove Phone sync"""")
        dialog should include(watchFolder.path)
        dialog should include("Its files stay on the server")

        val removed = send("DELETE", at(s"watch-folder?${F.WATCH_FOLDER_ID}=$id"))
        removed.statusCode shouldBe 200
        removed.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head should include("Watch folder Phone sync removed")
        get(at("list")).text() should include("No Watch folders.")
        Files.exists(file) shouldBe true
    }
  }

  test("An Admin naming another Library's Watch folder is answered as for one that does not exist") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library, and a second Library with a folder and a Watch folder, paused.
     *
     * Assertions:
     *
     * Through the first Library, the Edit dialog, the edit, Pause, Resume, Scan now, Retry failed, the Remove dialog and the
     * removal of the second Library's Watch folder are each a 404, as they are for an ID that names nothing, and no answer names
     * it. An add whose destination is the second Library's folder is a 404 too. The second Library's rows are as they were, and
     * the first has no Watch folder.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val mine = testContext.repository
    val theirs = addLibrary(TestContext.nextRepositoryName(), testContext.user)
    testApp.service.repository.switchContextToRepository(theirs)
    val theirFolder = testApp.service.folder.add("Quxtheirs folder")
    val theirWatch = watch("Quxtheirs watch")
    watchFolders.pause(theirWatch.persistedId)
    testApp.service.repository.switchContextToRepository(mine)
    val before = testContext.rowsOf(theirs)
    val dir = directory().toString

    withServer(App) {
      host =>
        def at(path: String) = route(host, repoId, path)

        for (id <- List(theirWatch.persistedId, java.util.UUID.randomUUID.toString)) {
          val answers = List(
            get(at(s"dialogs/edit?${F.WATCH_FOLDER_ID}=$id")),
            send("PUT", at("edit"), ujson.Obj.from(form("Mine", dir).value ++ named(id).value)),
            send("PUT", at("pause"), named(id)),
            send("PUT", at("resume"), named(id)),
            send("PUT", at("scan"), named(id)),
            send("PUT", at("retry"), named(id)),
            get(at(s"dialogs/remove?${F.WATCH_FOLDER_ID}=$id")),
            send("DELETE", at(s"watch-folder?${F.WATCH_FOLDER_ID}=$id"))
          )

          answers.map(_.statusCode) shouldBe List.fill(answers.size)(404)
          answers.foreach(answer => (answer.text() should not).include("Quxtheirs"))
        }

        send(
          "POST",
          at("add"),
          form("Mine", dir, keepsStructure = true, destFolderId = theirFolder.persistedId)).statusCode shouldBe 404
    }

    testContext.rowsOf(theirs) shouldBe before
    watchFolders.cards() shouldBe empty
  }

  test("The folder delete confirmation warns of the Watch folders that sort their files into the folder") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library with the folders "Archive", "Archive/2026" and "Other". One Watch folder sorts into
     * "Archive", another into "Archive/2026", and a third goes to Triage.
     *
     * Assertions:
     *
     * The Delete confirmation of "Archive" warns that the two Watch folders sort their files into it, naming both, and that they
     * pause until the folder is restored or they are given another destination. The one of "Archive/2026" names the one that
     * sorts into it alone.
     *
     * Edge cases:
     *
     * The confirmation of "Other", which no Watch folder sorts into, has no warning.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val archive = testApp.service.folder.add("Archive")
    val year = testApp.service.folder.add("2026", archive.id)
    val other = testApp.service.folder.add("Other")
    watch("Phone sync", destination = Some(archive))
    watch("Scanner", destination = Some(year))
    watch("To Triage")

    withServer(App) {
      host =>
        def confirmation(folder: Folder): String = {
          val response = get(s"$host/htmx/folder/r/$repoId/dialogs/delete-folder?id=${folder.persistedId}")
          response.statusCode shouldBe 200
          response.text()
        }

        val ofArchive = confirmation(archive)
        ofArchive should include("""<p class="notice warning" role="alert">""")
        (ofArchive should include).regex(
          """The Watch folders\s+<strong>Phone sync</strong>,\s+<strong>Scanner</strong>\s+sort their files""")
        (ofArchive should include).regex(
          """They pause until the folder is restored, or until an Admin gives them another\s+destination""")
        (ofArchive should not).include("To Triage")
        (ofArchive should not).include("It pauses")

        val ofYear = confirmation(year)
        (ofYear should include).regex("""The Watch folder <strong>Scanner</strong> sorts its files into this folder. It pauses""")
        (ofYear should not).include("Phone sync")
        // One wording or the other, and nothing of the template's own between them
        for (text <- List(ofArchive, ofYear)) (text should not).include("else")
        (ofYear should not).include("They pause")

        (confirmation(other) should not).include("notice warning")
    }
  }
}
