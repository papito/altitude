package altitude.core.controller

import java.net.HttpCookie
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const as C
import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.routes.web.SessionController
import altitude.core.util.Util

/**
 * The Users page and its dialogs: who opens the page and sees which accounts, the nav's Users entry, the Add User form rendered
 * to the creator's ceiling and its round trip, the refusal of anything above the ceiling, an Admin's edit, password, deactivation
 * and delete, and every account's change of its own password.
 */
@DoNotDiscover class UsersControllerTests extends ControllerTestCore {

  private def users = testApp.service.user

  private def send(
      method: String,
      url: String,
      body: ujson.Obj,
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

  private def merged(body: ujson.Obj, more: ujson.Obj): ujson.Obj = ujson.Obj.from(body.value ++ more.value)

  /** A Library of the test instance, which nobody holds a grant in */
  private def library(): Repository = testContext.persistRepository()

  /** An account of `accountType` created by `creator` through the service, with `grants` */
  private def created(creator: User, accountType: AccountType, grants: Map[String, Permissions]): User =
    users.create(creator.persistedId, testContext.makeUser().copy(accountType = accountType), "password 1", grants)

  /** The Add User form's body, a User given `permissions` in each of `libraries` */
  private def addBody(username: String, libraries: Map[Repository, List[Permission]], accountType: String = "User"): ujson.Obj =
    ujson.Obj(
      Api.Field.User.NAME -> "Jane Doe",
      Api.Field.User.USERNAME -> username,
      Api.Field.User.EMAIL -> s"$username@example.com",
      Api.Field.User.PASSWORD -> "password 1",
      Api.Field.User.ACCOUNT_TYPE -> accountType,
      Api.Field.User.LIBRARY_IDS -> ujson.Arr.from(libraries.keys.map(_.persistedId)),
      Api.Field.User.PERMISSIONS -> ujson.Arr.from(
        libraries.toSeq.flatMap((library, held) => held.map(p => s"${library.persistedId}:$p")))
    )

  test("An Admin sees every account on the Users page, each card with its menu, under the three tabs with their counts") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library, and in a second Library a User holding Import and Organize and a Guest, both created by
     * the Admin, the Guest deactivated.
     *
     * Assertions:
     *
     * The page answers 200 with the nav's Users entry marked as the page shown, the three tabs with their counts (one Admin, one
     * User, one Guest), each account's name and `@username`, the User's Libraries summary ("1 Library · 2 Permissions") and their
     * Permissions, "All Libraries" for the Admin, the Guest's status "Deactivated", and a ⋯ menu on each card: Edit, Set password
     * and Delete, with Reactivate for the Guest.
     *
     * Edge cases:
     *
     * The Admin's own card offers neither Deactivate nor Delete. The page opens on the Admins tab, the first with cards.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val admin = testContext.user
    val other = library()
    val user = created(admin, AccountType.User, Map(other.persistedId -> Permissions.of(Permission.Import, Permission.Organize)))
    val guest = created(admin, AccountType.Guest, Map(other.persistedId -> Permissions.none))
    users.deactivate(admin.persistedId, guest.persistedId)

    withServer(App) {
      host =>
        val response = get(s"$host/users/r/$repoId")
        response.statusCode shouldBe 200
        val page = response.text()

        (page should include).regex("""class="menu users" aria-current="page"""")
        (page should include).regex("""Admins <span class="tab-count">1</span>""")
        (page should include).regex("""Users <span class="tab-count">1</span>""")
        (page should include).regex("""Guests <span class="tab-count">1</span>""")
        for (account <- List(admin, user, guest)) {
          page should include(account.name)
          page should include(s"@${account.username}")
          page should include(s"""id="accountMenuBtn-${account.persistedId}"""")
        }
        page should include("1 Library · 2 Permissions")
        page should include("Import, Organize")
        page should include("All Libraries")
        page should include("Deactivated")
        page should include(">Reactivate</button>")
        val ownMenu = page.split(s"""id="accountMenu-${admin.persistedId}"""")(1).split("</article>")(0)
        (ownMenu should not).include(">Delete…</button>")
        (ownMenu should not).include(">Deactivate</button>")
        page should include("""data-app-start-tab="Admin"""")
    }
  }

  test("A holder of Create Users sees the accounts it created alone, read-only, listing only the Libraries it sees") {

    /**
     * Setup:
     *
     * A User holding Create Users in a Library, logged in; an account it created with access to that Library, which an Admin also
     * gave access to a second Library; and an account the Admin created.
     *
     * Assertions:
     *
     * The page answers 200 with the account the User created and its first Library, and neither the second Library's name, nor
     * the Admin's account, nor any card's menu.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.CreateUsers))
    val creator = testContext.user
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val mine = testApp.service.repository.getById(repoId)
    val hidden = library()
    val account = created(creator, AccountType.User, Map(repoId -> Permissions.none))
    users.update(admin.persistedId, account, Map(repoId -> Permissions.none, hidden.persistedId -> Permissions.none))
    val adminsAccount = created(admin, AccountType.User, Map(repoId -> Permissions.none))

    withServer(App) {
      host =>
        val response = get(s"$host/users/r/$repoId")
        response.statusCode shouldBe 200
        val page = response.text()

        val card = page.split("<article").find(_.contains(s"@${account.username}")).get.split("</article>")(0)
        card should include(mine.name)
        (page should not).include(hidden.name)
        (page should not).include(adminsAccount.username)
        (page should not).include("accountMenuBtn-")
        page should include("1 Library · 0 Permissions")
    }
  }

  test("The Users page opens on the first tab with cards, or on Guests for a creator who may create Guests alone") {

    /**
     * Setup:
     *
     * A User holding Create Guests alone in a Library, logged in, who created no account yet; then a Guest it creates.
     *
     * Assertions:
     *
     * The page opens on the Guests tab with no account, and again once the Guest is there; that tab's section is the one shown
     * before the page's script runs.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.CreateGuests))

    withServer(App) {
      host =>
        get(s"$host/users/r/$repoId").text() should include("""data-app-start-tab="Guest"""")
        created(testContext.user, AccountType.Guest, Map(repoId -> Permissions.none))
        val page = get(s"$host/users/r/$repoId").text()
        page should include("""data-app-start-tab="Guest"""")
        (page should include).regex("""aria-label="Guests" x-show="isTab\('Guest'\)">""")
    }
  }

  test("The nav shows Users to an Admin and to a holder of Create Users or Create Guests, and to no one else") {

    /**
     * Setup:
     *
     * An Admin, a User holding Create Guests alone, a User holding every Permission but Create Users and Create Guests, and a
     * Guest, each logged in to a Library.
     *
     * Assertions:
     *
     * The main page's nav carries the Users entry, linking to the Users page of its Library, for the Admin and the holder of
     * Create Guests, and not for the other two. Every nav carries the account menu, named after the username, with Change
     * password.
     */
    val callers = List(
      (AccountType.Admin, Permissions.all, true),
      (AccountType.User, Permissions.of(Permission.CreateGuests), true),
      (
        AccountType.User,
        Permissions(Permissions.all.mask & ~Permissions.of(Permission.CreateUsers, Permission.CreateGuests).mask),
        false),
      (AccountType.Guest, Permissions.none, false)
    )
    for ((accountType, permissions, shown) <- callers) withClue(s"[$accountType $permissions]: ") {
      testContext = new altitude.test.TestContext(testApp)
      val repoId = loginAs(accountType, permissions)

      withServer(App) {
        host =>
          val page = get(s"$host/r/$repoId").text()
          page.contains(s"""href="/users/r/$repoId"""") shouldBe shown
          page should include(s"""aria-label="Account: ${testContext.user.username}"""")
          page should include("Change password…")
      }
    }
  }

  test("The Add User form offers an Admin every type, Library and Permission, and the Create Libraries and new-Library grant") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library, beside a second Library.
     *
     * Assertions:
     *
     * The form answers 200 in the general modal titled "Add User", with Name, Username, Email and Password, the three type cards,
     * a row for each Library, a column for each of the nine Permissions, the Create Libraries switch and the new-Library grant's
     * switch and chips.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val other = library()

    withServer(App) {
      host =>
        val response = get(s"$host/htmx/users/r/$repoId/dialogs/add")
        response.statusCode shouldBe 200
        val form = response.text()

        form should include("""data-app-modal-title="Add User"""")
        for (field <- List(Api.Field.User.NAME, Api.Field.User.USERNAME, Api.Field.User.EMAIL, Api.Field.User.PASSWORD))
          form should include(s"""name="$field"""")
        for (accountType <- List("Admin", "User", "Guest")) form should include(s"""value="$accountType"""")
        for (library <- List(repoId, other.persistedId))
          form should include(s"""name="${Api.Field.User.LIBRARY_IDS}" value="$library"""")
        for (permission <- Permission.values) form should include(s"""toggleColumn('$permission')""")
        form should include("""id="switch-canCreateLibraries"""")
        form should include("""id="switch-grantsNewLibraries"""")
    }
  }

  test(
    "A creator who is no Admin is offered nothing above its ceiling: not a type, a Library, a Permission or the Admin section") {

    /**
     * Setup:
     *
     * A User logged in to a Library where it holds Import, Curate own and Create Users, holding Create Guests alone in a second
     * Library and nothing in a third.
     *
     * Assertions:
     *
     * The form offers the User and Guest cards and no Admin card; a row for the first two Libraries and none for the third; the
     * columns of Import, Curate own and Create Users alone; no Create Libraries or new-Library grant; and in the second Library's
     * row no Permission cell. The Library it was opened from starts checked, with Import and Curate own.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import, Permission.CurateOwn, Permission.CreateUsers))
    val creator = testContext.user
    val (guests, none) = (library(), library())
    testContext.grant(creator, guests, Permissions.of(Permission.CreateGuests))
    login()

    withServer(App) {
      host =>
        val form = get(s"$host/htmx/users/r/$repoId/dialogs/add").text()

        form should include("""value="User"""")
        form should include("""value="Guest"""")
        (form should not).include("""value="Admin"""")
        form should include(s"""value="$repoId"""")
        form should include(s"""value="${guests.persistedId}"""")
        (form should not).include(none.persistedId)
        val columns = Permission.values.filter(p => form.contains(s"toggleColumn('$p')")).toList
        columns shouldBe List(Permission.Import, Permission.CurateOwn, Permission.CreateUsers)
        (form should not).include(s"""value="${guests.persistedId}:""")
        (form should not).include("switch-canCreateLibraries")
        (form should not).include("switch-grantsNewLibraries")
        form should include("&quot;access&quot;:[&quot;" + repoId)
    }
  }

  test("Add User answers a refused field with the form, its error and the values submitted, and adds a valid account") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library; a submission with a username the rule refuses and no account type, then one with that
     * username alone refused, then a valid one giving Import and Organize in the Library.
     *
     * Assertions:
     *
     * The first two answer 200 as a validation replacement (`HX-Retarget: this`): the form with the type's error beside the
     * username rule's, then the username rule's error alone, under their fields, the typed values kept. The third answers 200
     * with the success detail naming the account, which is stored, created by the Admin, holding Import and Organize in the
     * Library.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val mine = testApp.service.repository.getById(repoId)
    val username = s"jane.${Util.randomStr(6)}"

    withServer(App) {
      host =>
        val url = s"$host/htmx/users/r/$repoId/add"
        val untyped = send("POST", url, ujson.Obj.from(addBody("a b", Map(mine -> Nil)).value - Api.Field.User.ACCOUNT_TYPE))
        untyped.statusCode shouldBe 200
        untyped.headers("hx-retarget") shouldBe Seq("this")
        untyped.text() should include(s"""<div class="error">${C.Msg.Err.ACCOUNT_TYPE_REQUIRED}</div>""")
        untyped.text() should include(s"""<div class="error">${C.Msg.Err.USERNAME_RULE}</div>""")

        val refused = send("POST", url, addBody("a b", Map(mine -> Nil)))
        refused.headers("hx-retarget") shouldBe Seq("this")
        refused.text() should include(s"""<div class="error">${C.Msg.Err.USERNAME_RULE}</div>""")
        refused.text() should include("""value="Jane Doe"""")
        refused.text() should include("""value="a b"""")

        val added = send("POST", url, addBody(username, Map(mine -> List(Permission.Import, Permission.Organize))))
        added.statusCode shouldBe 200
        added.headers.get("hx-retarget") shouldBe None
        added.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head should include("Jane Doe added")

        val account = users.accountsVisibleTo(testContext.user).map(_._1).find(_.username == username).get
        account.createdByAccountId shouldBe Some(testContext.user.persistedId)
        testApp.service.repository.getGrant(account, repoId) shouldBe Some(Permissions.of(Permission.Import, Permission.Organize))
    }
  }

  test("Anything above a creator's ceiling is refused with 403, whatever the form sends, and adds no account") {

    /**
     * Setup:
     *
     * A User holding Import and Create Users in a Library, logged in, beside a Library it holds nothing in.
     *
     * Assertions:
     *
     * A User given Organize in its Library, a User given the other Library, an Admin, and a User with the Create Libraries flag
     * are each answered 403, and no account of their usernames exists.
     */
    val repoId = loginAs(AccountType.User, Permissions.of(Permission.Import, Permission.CreateUsers))
    val mine = testApp.service.repository.getById(repoId)
    val other = library()

    withServer(App) {
      host =>
        val url = s"$host/htmx/users/r/$repoId/add"
        val bodies = List(
          addBody(s"o.${Util.randomStr(6)}", Map(mine -> List(Permission.Organize))),
          addBody(s"o.${Util.randomStr(6)}", Map(other -> Nil)),
          addBody(s"o.${Util.randomStr(6)}", Map(mine -> Nil), accountType = "Admin"),
          merged(addBody(s"o.${Util.randomStr(6)}", Map(mine -> Nil)), ujson.Obj(Api.Field.User.CAN_CREATE_LIBRARIES -> "true"))
        )
        for (body <- bodies) withClue(s"${ujson.write(body)}: ") {
          send("POST", url, body).statusCode shouldBe 403
          val username = body(Api.Field.User.USERNAME).str
          testApp.txManager.asReadOnly {
            testApp.DAO.user.manyBySqlQuery("SELECT id FROM account WHERE username = ?", List(username))
          } shouldBe empty
        }
    }
  }

  test("An Admin edits an account through the same form, sets its password, deactivates, reactivates and deletes it") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library, and a User it created with Import there, who imported an asset and is logged in too.
     *
     * Assertions:
     *
     * The edit form is the Add User form titled "Edit <name>", holding the User's values and no password field; its save renames
     * the User. Set password answers 200, and the User's old password fails at login while the new one logs in. Deactivate
     * refuses the User's session; Reactivate lets it log in again. The Delete dialog names the one asset that moves to the Admin;
     * the delete answers 200, the account is gone and the asset is the Admin's.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val admin = testContext.user
    val user = created(admin, AccountType.User, Map(repoId -> Permissions.of(Permission.Import)))
    val asset = testContext.insertAsset(testContext.makeAsset(user = Some(user)))
    val accountId = Api.Field.User.ACCOUNT_ID

    withServer(App) {
      host =>
        val base = s"$host/htmx/users/r/$repoId"
        val form = get(s"$base/dialogs/edit?$accountId=${user.persistedId}").text()
        form should include(s"""data-app-modal-title="Edit ${user.name}"""")
        form should include(s"""value="${user.username}"""")
        (form should not).include(s"""name="${Api.Field.User.PASSWORD}"""")

        val edited = merged(
          addBody(user.username, Map(testApp.service.repository.getById(repoId) -> List(Permission.Import))),
          ujson.Obj(accountId -> user.persistedId, Api.Field.User.NAME -> "Renamed User", Api.Field.User.EMAIL -> user.email)
        )
        send("PUT", s"$base/edit", edited).statusCode shouldBe 200
        users.getById(user.persistedId).name shouldBe "Renamed User"

        send(
          "PUT",
          s"$base/password",
          ujson.Obj(accountId -> user.persistedId, Api.Field.User.PASSWORD -> "password 2")).statusCode shouldBe 200
        users.loginAndSetUser(user.username, "password 1") shouldBe None
        val userCookies = requests
          .post(s"$host/login", data = Map("login" -> user.username, "password" -> "password 2"), maxRedirects = 0, check = false)
          .cookies

        send("PUT", s"$base/deactivate", ujson.Obj(accountId -> user.persistedId)).statusCode shouldBe 204
        get(s"$host/r/$repoId", userCookies).statusCode shouldBe 302
        send("PUT", s"$base/reactivate", ujson.Obj(accountId -> user.persistedId)).statusCode shouldBe 204
        users.loginAndSetUser(user.username, "password 2").isDefined shouldBe true

        get(s"$base/dialogs/delete?$accountId=${user.persistedId}").text() should include("Their 1 asset moves to you")
        requests
          .delete(
            s"$base/account",
            params = Map(accountId -> user.persistedId),
            headers = Map("Accept-Encoding" -> "identity"),
            cookies = testContext.cookies,
            check = false)
          .statusCode shouldBe 200
        users.accountsVisibleTo(admin).map(_._1.persistedId) should not contain user.persistedId
        (testApp.service.asset.getById(asset.persistedId): altitude.core.models.Asset).ownerUserId shouldBe admin.persistedId
    }
  }

  test("A session whose token was stale when it changed a password carries on with the new token alone") {

    /**
     * Setup:
     *
     * An Admin logged in to a Library, whose account's version is bumped after the login (its token is stale and rebuilt at the
     * next request); it changes its own password, then, stale again, sets its own password from the Users page.
     *
     * Assertions:
     *
     * Each answer sets one session cookie, the new token's, which opens the Library; the rebuilt token, minted before the
     * password changed, is not set after it.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val admin = testContext.user

    withServer(App) {
      host =>
        def sessionCookies(response: requests.Response) =
          response.headers.getOrElse("set-cookie", Nil).filter(_.startsWith(s"${SessionController.AUTH_COOKIE_NAME}="))
        def opens(response: requests.Response) =
          val cookie = response.cookies(SessionController.AUTH_COOKIE_NAME)
          get(s"$host/r/$repoId", Map(SessionController.AUTH_COOKIE_NAME -> cookie)).statusCode shouldBe 200

        testApp.service.accountVersions.bump(admin.persistedId)
        val changed = send(
          "PUT",
          s"$host/htmx/account/password",
          ujson.Obj(
            Api.Field.User.CURRENT_PASSWORD -> altitude.test.TestContext.USER_PASSWORD,
            Api.Field.User.NEW_PASSWORD -> "password 2",
            Api.Field.User.NEW_PASSWORD2 -> "password 2"
          )
        )
        changed.statusCode shouldBe 200
        sessionCookies(changed).size shouldBe 1
        opens(changed)

        testApp.service.accountVersions.bump(admin.persistedId)
        val cookies = Map(SessionController.AUTH_COOKIE_NAME -> changed.cookies(SessionController.AUTH_COOKIE_NAME))
        val set = send(
          "PUT",
          s"$host/htmx/users/r/$repoId/password",
          ujson.Obj(Api.Field.User.ACCOUNT_ID -> admin.persistedId, Api.Field.User.PASSWORD -> "password 3"),
          cookies)
        set.statusCode shouldBe 200
        sessionCookies(set).size shouldBe 1
        opens(set)
    }
  }

  test("An account changes its own password with its current one, and its session carries on with a new token") {

    /**
     * Setup:
     *
     * A Guest logged in to a Library, and a second session of it; the Guest sends a wrong current password, then a repeat that
     * differs, then a valid change.
     *
     * Assertions:
     *
     * The first two answer the dialog again with the error under its field (`WRONG_PASSWORD`, `PASSWORDS_DO_NOT_MATCH`). The
     * valid change answers 200 and sets a new session cookie, which opens the Library, while the second session is refused, and
     * the new password logs in.
     */
    val repoId = loginAs(AccountType.Guest, Permissions.none)
    val guest = testContext.user

    withServer(App) {
      host =>
        val other = cookiesOf(host, guest)
        val url = s"$host/htmx/account/password"
        def change(current: String, chosen: String, repeated: String) = send(
          "PUT",
          url,
          ujson.Obj(
            Api.Field.User.CURRENT_PASSWORD -> current,
            Api.Field.User.NEW_PASSWORD -> chosen,
            Api.Field.User.NEW_PASSWORD2 -> repeated))

        get(s"$host/htmx/account/dialogs/password").text() should include(s"""name="${Api.Field.User.CURRENT_PASSWORD}"""")
        change("wrong one", "password 2", "password 2").text() should include(
          s"""<div class="error">${C.Msg.Err.WRONG_PASSWORD}</div>""")
        change(altitude.test.TestContext.USER_PASSWORD, "password 2", "password 3").text() should include(
          s"""<div class="error">${C.Msg.Err.PASSWORDS_DO_NOT_MATCH}</div>""")

        val changed = change(altitude.test.TestContext.USER_PASSWORD, "password 2", "password 2")
        changed.statusCode shouldBe 200
        val renewed = changed.cookies(SessionController.AUTH_COOKIE_NAME)
        get(s"$host/r/$repoId", Map(SessionController.AUTH_COOKIE_NAME -> renewed)).statusCode shouldBe 200
        get(s"$host/r/$repoId", other).statusCode shouldBe 302
        users.loginAndSetUser(guest.username, "password 2").isDefined shouldBe true
    }
  }
}
