package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireAccountCreator
import altitude.core.routes.decorators.requireAdmin
import altitude.core.routes.decorators.requireLogin
import altitude.core.service.RepositoryService
import altitude.core.service.UserService
import altitude.core.util.NaturalOrder
import altitude.core.util.Util

object UserActionController:

  /** A Permission matrix row: whether the creator may give a User or a Guest access to the Library, and what Permissions */
  final case class LibraryCeiling(library: Repository, users: Boolean, guests: Boolean, permissions: Permissions)

  /**
   * What a creator may give an account: everything for an Admin; for anyone else, a User where it holds Create Users and a Guest
   * where it holds Create Guests, each with the Permissions it holds there, and never an Admin. The form renders nothing above
   * it.
   */
  final case class Ceiling(isAdmin: Boolean, libraries: List[LibraryCeiling]):

    /** In the order of the form's cards */
    def types: List[AccountType] =
      if isAdmin then List(AccountType.Admin, AccountType.User, AccountType.Guest)
      else
        List(AccountType.User -> libraries.exists(_.users), AccountType.Guest -> libraries.exists(_.guests))
          .collect { case (accountType, true) => accountType }

    /** The matrix's columns: the Permissions the creator may give a User in some Library */
    def columns: List[Permission] =
      Permission.values.toList.filter(permission => libraries.exists(row => row.users && row.permissions.has(permission)))

  private def names(permissions: Permissions): ujson.Arr = ujson.Arr.from(permissions.toList.map(_.toString))

  /** A request's account or Library ID, refused unless a UUID so no other text reaches a statement or a log line */
  def uuid(id: String): String =
    if !Validators.isUuid(id) then throw ValidationException(C.Msg.Err.VALUE_NOT_A_UUID)
    id

  /**
   * An account's values as stored or as submitted. `storedName` is what an edit's title names, whatever name is typed;
   * `openedState` is the state an edit was opened over (`UserService.editState`).
   */
  final case class AccountForm(
      accountId: Option[String] = None,
      name: String = "",
      username: String = "",
      email: String = "",
      password: String = "",
      passwordRepeat: String = "",
      accountType: Option[AccountType] = None,
      grants: Map[String, Permissions] = Map.empty,
      canCreateLibraries: Boolean = false,
      grantsNewLibraries: Boolean = false,
      newLibraryPermissions: Permissions = AccountForm.PRESET,
      openedState: Option[String] = None,
      storedName: Option[String] = None):

    /** A type is chosen by now */
    def toUser: User =
      User(
        id = accountId,
        email = email,
        username = username,
        name = name,
        accountType = accountType.getOrElse(AccountType.User),
        canCreateLibraries = canCreateLibraries,
        grantsNewLibraries = grantsNewLibraries,
        newLibraryPermissions = newLibraryPermissions
      )

    /**
     * The form component's starting state (`js/alpine/components/account-form.js`). With the new-Library switch off the form
     * submits no Permissions for it, so its chips show the preset for the switch to turn on with.
     */
    def stateJson(ceiling: Ceiling): String =
      ujson.write(
        ujson.Obj(
          "type" -> accountType.fold("")(_.toString),
          "access" -> ujson.Arr.from(grants.keys.toSeq.sorted),
          "permissions" -> ujson.Obj.from(grants.toSeq.map((id, held) => id -> names(held))),
          "canCreateLibraries" -> canCreateLibraries,
          "grantsNewLibraries" -> grantsNewLibraries,
          "newLibraryPermissions" -> names(if grantsNewLibraries then newLibraryPermissions else AccountForm.PRESET),
          "preset" -> names(AccountForm.PRESET),
          "ceiling" -> ujson.Obj.from(ceiling.libraries.map {
            row =>
              row.library.persistedId -> ujson.Obj(
                "users" -> row.users,
                "guests" -> row.guests,
                "permissions" -> names(row.permissions))
          }),
          "maxLibraries" -> RepositoryService.MAX_GRANTS_PER_ACCOUNT
        ))

  object AccountForm:

    /** What a User given a Library holds there to start with, and a new-Library grant's start */
    val PRESET: Permissions = Permissions.of(Permission.Import, Permission.CurateOwn)

    def ofAccount(opened: UserService.EditForm): AccountForm =
      val (account, grants) = (opened.account, opened.grants)
      AccountForm(
        openedState = Some(opened.state),
        storedName = Some(account.name),
        accountId = account.id,
        name = account.name,
        username = account.username,
        email = account.email,
        accountType = Some(account.accountType),
        grants = grants,
        canCreateLibraries = account.canCreateLibraries,
        grantsNewLibraries = account.grantsNewLibraries,
        newLibraryPermissions = account.newLibraryPermissions
      )

    /**
     * The form as `json-enc` writes it: a field checked once is a string, several times an array. A Permission given in a Library
     * gives access to it too, as the matrix does. An unchosen type is left for `submitted` to refuse.
     */
    def submittedIn(json: ujson.Obj): AccountForm =
      def text(field: String): String = json.obj.get(field).flatMap(_.strOpt).getOrElse("")
      def texts(field: String): List[String] = json.obj.get(field) match
        case Some(ujson.Str(value)) => List(value)
        case Some(ujson.Arr(values)) => values.toList.flatMap(_.strOpt)
        case _ => Nil
      def permission(name: String): Permission =
        Permission.values.find(_.toString == name).getOrElse(throw ValidationException(C.Msg.Err.INCORRECT_VALUE_TYPE))

      val accountType = Option(text(Api.Field.User.ACCOUNT_TYPE)).filter(_.nonEmpty).map {
        name => AccountType.values.find(_.toString == name).getOrElse(throw ValidationException(C.Msg.Err.INCORRECT_VALUE_TYPE))
      }
      val granted = texts(Api.Field.User.PERMISSIONS).map {
        value =>
          value.split(':') match
            case Array(libraryId, name) => uuid(libraryId) -> permission(name)
            case _ => throw ValidationException(C.Msg.Err.INCORRECT_VALUE_TYPE)
      }
      val libraryIds = texts(Api.Field.User.LIBRARY_IDS).map(uuid) ++ granted.map(_._1)

      AccountForm(
        accountId = Option(text(Api.Field.User.ACCOUNT_ID)).filter(_.nonEmpty).map(uuid),
        name = text(Api.Field.User.NAME),
        username = text(Api.Field.User.USERNAME).trim,
        email = text(Api.Field.User.EMAIL).trim,
        password = text(Api.Field.User.PASSWORD),
        passwordRepeat = text(Api.Field.User.PASSWORD2),
        accountType = accountType,
        grants = libraryIds.distinct.map {
          libraryId => libraryId -> Permissions.of(granted.collect { case (`libraryId`, held) => held }*)
        }.toMap,
        canCreateLibraries = text(Api.Field.User.CAN_CREATE_LIBRARIES) == "true",
        grantsNewLibraries = text(Api.Field.User.GRANTS_NEW_LIBRARIES) == "true",
        newLibraryPermissions = Permissions.of(texts(Api.Field.User.NEW_LIBRARY_PERMISSIONS).map(permission)*),
        openedState = Option(text(Api.Field.User.OPENED_STATE)).filter(_.nonEmpty)
      )

  /** The form field of each key the account rules name an error by */
  val FIELD_OF_ERROR: Map[String, String] = Map(
    FieldConst.User.NAME -> Api.Field.User.NAME,
    FieldConst.User.USERNAME -> Api.Field.User.USERNAME,
    FieldConst.User.EMAIL -> Api.Field.User.EMAIL,
    FieldConst.User.PASSWORD -> Api.Field.User.PASSWORD,
    FieldConst.User.ACCOUNT_TYPE -> Api.Field.User.ACCOUNT_TYPE,
    FieldConst.User.GRANTS -> Api.Field.User.LIBRARY_IDS,
    FieldConst.User.OPENED_STATE -> Api.Field.User.OPENED_STATE
  )

  /** The account and its grants in the Libraries the viewer sees, by name */
  final case class AccountCard(account: User, libraries: List[(Repository, Permissions)])

  /** The first tab that holds a card; with none, Users, or Guests for a viewer who may create Guests alone */
  def startTab(cards: List[AccountCard]): AccountType =
    List(AccountType.Admin, AccountType.User, AccountType.Guest)
      .find(accountType => cards.exists(_.account.accountType == accountType))
      .getOrElse(if RequestContext.holdsAnywhere(Permission.CreateUsers) then AccountType.User else AccountType.Guest)

  /** The account cards `viewer` sees: every account for an Admin, the ones it created for anyone else */
  def accounts(viewer: User): Html =
    val libraries = App.altitude.service.repository.getAccessibleTo(viewer).map(library => library.persistedId -> library).toMap
    val cards = App.altitude.service.user.accountsVisibleTo(viewer).map {
      (account, grants) =>
        val held = grants.flatMap(grant => libraries.get(grant.repositoryId).map(_ -> grant.permissions))
        AccountCard(account, held.sortBy(_._1.name)(using NaturalOrder))
    }
    htmx.html.user_accounts(cards, viewer, startTab(cards))

  def ceilingOf(creator: User): Ceiling =
    val libraries = App.altitude.service.repository.getAccessibleTo(creator)
    if creator.accountType == AccountType.Admin then
      Ceiling(isAdmin = true, libraries.map(LibraryCeiling(_, users = true, guests = true, Permissions.all)))
    else
      val grants = App.altitude.service.repository.getGrants(creator)
      Ceiling(
        isAdmin = false,
        libraries.flatMap {
          library =>
            grants
              .get(library.persistedId)
              .filter(held => held.has(Permission.CreateUsers) || held.has(Permission.CreateGuests))
              .map(held => LibraryCeiling(library, held.has(Permission.CreateUsers), held.has(Permission.CreateGuests), held))
        }
      )

/**
 * The Users page's dialogs and writes. The account rules and the ceiling are `UserService`'s; a refused field comes back as the
 * form with the error under it, a refusal above the ceiling as a 403.
 */
class UserActionController(using logger: Logger) extends BaseController:
  import UserActionController.*

  private val prefix = "htmx/users"

  private def users = App.altitude.service.user

  @requireLogin()
  @requireAccountCreator()
  @cask.get(f"/$prefix/r/:repoId/accounts")
  def htmxAccounts(repoId: String)(using request: Request): Response[String] =
    htmlResponse(UserActionController.accounts(RequestContext.getAccount))

  @requireLogin()
  @requireAccountCreator()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add")
  def showAddDialog(repoId: String)(using request: Request): Response[String] =
    val ceiling = ceilingOf(RequestContext.getAccount)
    // A new account starts as the first type the creator may create, with access to this Library where it may give it and the
    // preset Permissions for a User, as far as the creator holds them
    val accountType = ceiling.types.find(_ != AccountType.Admin)
    val here = ceiling.libraries.find(_.library.persistedId == repoId).filter {
      row => accountType.contains(AccountType.User) && row.users || accountType.contains(AccountType.Guest) && row.guests
    }
    val grants = here.map {
      row =>
        val preset =
          if accountType.contains(AccountType.User) then AccountForm.PRESET
          else Permissions.none
        row.library.persistedId -> Permissions(preset.mask & row.permissions.mask)
    }.toMap
    htmlResponse(htmx.html.account_form(AccountForm(accountType = accountType, grants = grants), ceiling))

  /** `UserService.create` holds the account to the creator's ceiling */
  @requireLogin()
  @requireAccountCreator()
  @cask.post(f"/$prefix/r/:repoId/add")
  def htmxAdd(repoId: String)(using request: Request): Response[String] =
    answering {
      val creator = RequestContext.getAccount
      val form = AccountForm.submittedIn(unscrubbedJson.get)
      submitted(form, ceilingOf(creator)) {
        val created =
          users.create(creator.persistedId, form.toUser, form.password, form.grants, BaseController.clientIpOf(request))
        logger.info(s"Account [${creator.persistedId}] created account [${created.persistedId}]")
        dialogSuccessResponse(ujson.Obj("message" -> s"${created.name} added"))
      }
    }

  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/edit")
  def showEditDialog(repoId: String, accountId: String)(using request: Request): Response[String] =
    answering {
      val opened = AccountForm.ofAccount(users.openForEdit(uuid(accountId)))
      htmlResponse(htmx.html.account_form(opened, ceilingOf(RequestContext.getAccount)))
    }

  /** The same form as Add User, its password aside */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/edit")
  def htmxEdit(repoId: String)(using request: Request): Response[String] =
    answering {
      val admin = RequestContext.getAccount
      val form = AccountForm.submittedIn(unscrubbedJson.get)
      val accountId = form.accountId.getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED))
      submitted(form.copy(storedName = Some(users.getById(accountId).name)), ceilingOf(admin)) {
        val account = form.toUser.copy(id = Some(accountId))
        // A form with no opened state matches no account's state, so its save is refused as stale
        val updated =
          users.update(
            admin.persistedId,
            account,
            form.grants,
            form.openedState.getOrElse(""),
            BaseController.clientIpOf(request))
        dialogSuccessResponse(ujson.Obj("message" -> s"${updated.name} saved"))
      }
    }

  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/set-password")
  def showSetPasswordDialog(repoId: String, accountId: String)(using request: Request): Response[String] =
    answering {
      htmlResponse(htmx.html.set_password_dialog(users.getById(uuid(accountId))))
    }

  /** Refuses every session of the account; when it is the Admin's own, this session is given a new token */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/password")
  def htmxSetPassword(repoId: String)(using request: Request): Response[String] =
    answering {
      val admin = RequestContext.getAccount
      val json = unscrubbedJson.get
      val accountId = accountIdOf(json)
      val password = json.obj.get(Api.Field.User.PASSWORD).flatMap(_.strOpt).getOrElse("")
      val account = users.getById(accountId)
      try
        users.setPassword(admin.persistedId, accountId, password, BaseController.clientIpOf(request))
        val answer = dialogSuccessResponse(ujson.Obj("message" -> s"The password of ${account.name} is set"))
        if accountId == admin.persistedId then answer.copy(cookies = OwnPasswordController.renewedSession(accountId))
        else answer
      catch
        case ex: ValidationException =>
          dialogFormValidationResponse(
            "<!doctype html>" + htmx.html.set_password_dialog(account, ex.errors.getOrElse(FieldConst.User.PASSWORD, ex.message)))
    }

  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/deactivate")
  def htmxDeactivate(repoId: String)(using request: Request): Response[String] =
    answering {
      val accountId = accountIdOf(unscrubbedJson.get)
      users.deactivate(RequestContext.getAccount.persistedId, accountId, BaseController.clientIpOf(request))
      noContentResponse
    }

  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/reactivate")
  def htmxReactivate(repoId: String)(using request: Request): Response[String] =
    answering {
      val accountId = accountIdOf(unscrubbedJson.get)
      users.reactivate(RequestContext.getAccount.persistedId, accountId, BaseController.clientIpOf(request))
      noContentResponse
    }

  /** Names how many assets of the account, in every Library, move to the Admin */
  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/delete")
  def showDeleteDialog(repoId: String, accountId: String)(using request: Request): Response[String] =
    answering {
      val account = users.getById(uuid(accountId))
      htmlResponse(htmx.html.delete_account_dialog(account, users.countOwnedAssets(accountId)))
    }

  @requireLogin()
  @requireAdmin()
  @cask.delete(f"/$prefix/r/:repoId/account")
  def htmxDelete(repoId: String, accountId: String)(using request: Request): Response[String] =
    answering {
      val admin = RequestContext.getAccount
      val account = users.getById(uuid(accountId))
      val moved = users.delete(admin.persistedId, accountId, BaseController.clientIpOf(request))
      logger.info(
        s"Account [${admin.persistedId}] deleted account [${Util.printable(accountId, 36)}]; [$moved] assets moved to it")
      dialogSuccessResponse(ujson.Obj("message" -> s"${account.name} deleted"))
    }

  private def accountIdOf(json: ujson.Obj): String =
    uuid(json.obj.get(Api.Field.User.ACCOUNT_ID).flatMap(_.strOpt).getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED)))

  /**
   * Runs the form's write, or answers the form again with every field's error. An unchosen type and an add's mismatched password
   * are refused here before the service, and come back beside every other error the rules give (`UserService.fieldErrors`).
   */
  private def submitted(form: AccountForm, ceiling: Ceiling)(write: => Response[String]): Response[String] =
    def again(errors: Map[String, String]): Response[String] =
      val fieldErrors = errors.map((key, error) => FIELD_OF_ERROR.getOrElse(key, key) -> error)
      logger.debug(s"Account form refused: ${fieldErrors.keys.mkString(", ")}")
      dialogFormValidationResponse("<!doctype html>" + htmx.html.account_form(form, ceiling, fieldErrors))

    val adding = form.accountId.isEmpty
    val formErrors =
      Option.when(form.accountType.isEmpty)(Api.Field.User.ACCOUNT_TYPE -> C.Msg.Err.ACCOUNT_TYPE_REQUIRED).toMap ++
        Option.when(adding && form.password != form.passwordRepeat)(Api.Field.User.PASSWORD2 -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH)

    if formErrors.nonEmpty then
      again(users.fieldErrors(form.toUser, form.grants, Option.when(adding)(form.password)) ++ formErrors)
    else
      try write
      catch case ex: ValidationException if ex.errors.nonEmpty => again(ex.errors.toMap)

  initialize()
