package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.DataScrubber
import altitude.core.DuplicateException
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.service.PersonService

class PeopleActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/people"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showPeopleTab(repoId: String)(using request: Request): Response[String] =
    // The shell only: the browser's `peopleTab` component asks `listPeople` for each pane's list
    val payload = "<!doctype html>" + htmx.html.people()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The list of the people in the People buckets named, comma-separated, by `buckets`, each with the number the tab shows; a name
   * that is no bucket is a 400. Only `triaged=true` shows triaged items, the repository view's rule (`SearchRequestParser`), and
   * a value that is no boolean is a 400. `pane` names the pane of the People tab the list is for (`Const.PeoplePane`, the Main
   * pane when left out), which each rename pencil's ID carries, since a Person can be listed in both panes; a name that is no
   * pane is a 400.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/list")
  def listPeople(
      repoId: String,
      buckets: String = Const.PeopleBucket.MATCHED,
      triaged: Option[Boolean] = None,
      pane: String = Const.PeoplePane.MAIN)(using request: Request): Response[String] =
    val requested: Set[String] = App.altitude.service.person.parseBuckets(buckets)
    val unknown = requested -- Const.PeopleBucket.ALL
    if unknown.nonEmpty then return badRequest(s"Unknown People bucket: ${unknown.mkString(", ")}")
    if !Const.PeoplePane.ALL.contains(pane) then return unknownPane(pane)

    val listed = App.altitude.service.person.getListedInBuckets(requested, showTriaged = triaged.contains(true))
    val payload = "<!doctype html>" + htmx.html.people_list(listed = listed, pane = pane)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /** A plain-text 400; the snackbar reports the status */
  private def badRequest(message: String): Response[String] =
    cask.Response(message, 400, Seq(("Content-Type", "text/plain")))

  private def unknownPane(pane: String): Response[String] = badRequest(s"Unknown People pane: $pane")

  /**
   * The options of the Change combobox of Asset detail's People section: the people a Face can be given to
   * (`PersonService.assignablePeople`). Given the Face (`faceId`), the Suggested group of the people nearest it comes first, and
   * those people and the Face's own Person are left out of the list after it, so each is one option and the Face's Person none;
   * an unknown Face, or another repository's, is a 404
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/assignable")
  def listAssignablePeople(repoId: String, faceId: Option[String] = None)(using request: Request): Response[String] =
    answering {
      val assignable = App.altitude.service.person.assignablePeople(faceId)
      val payload = "<!doctype html>" + htmx.html.assignable_people(people = assignable.others, suggested = assignable.suggested)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /**
   * A person's results title on its own, read again after a Face correction because the title's Hide and Discard links depend on
   * the count; an unknown person, or another repository's, is a 404
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/p/:personId/title")
  def showPersonTitle(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getInRepository(personId)
      val payload = "<!doctype html>" + htmx.html.person_inner(person = person)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/choose-person-cover-face")
  def showChoosePersonCoverFaceModal(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val topFaces = App.altitude.service.person.getPersonFaces(person.persistedId, limit = 24)
    val payload = "<!doctype html>" + htmx.html.choose_person_cover_face_modal(
      title = C.UI.CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE,
      person = person,
      faces = topFaces
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/cover-image")
  def setCoverImage(repoId: String, personId: String, faceId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val face: Face = App.altitude.service.person.getFaceById(faceId)

    logger.trace(s"Setting cover image for person $personId to face $faceId")
    val updatedPerson = App.altitude.service.person.setFaceAsCover(person, face)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/p/:personId/name/edit")
  def showEditPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val payload = "<!doctype html>" + htmx.html.edit_person_name(person = person)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/name/edit")
  def editPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    val payload = renamePerson(json => App.altitude.service.person.getById(json(Api.Field.ID).str)) match
      case RenameOutcome.Saved(person) => htmx.html.view_person_name(person = person)
      case RenameOutcome.Refused(person, form, errors, holder) =>
        htmx.html.edit_person_name(fieldErrors = errors, formJson = form, person = person, conflictingPerson = holder)
    cask.Response("<!doctype html>" + payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The rename dialog the People tab's pencil opens for a Person, its field holding the current name. `pane` is the pane of the
   * pencil (`Const.PeoplePane`, the Main pane when left out), the control focus goes back to when the dialog closes; a name that
   * is no pane is a 400. An unknown Person, or another repository's, is a 404.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/rename-person")
  def showRenamePersonDialog(repoId: String, personId: String, pane: String = Const.PeoplePane.MAIN)(using
      request: Request): Response[String] =
    if !Const.PeoplePane.ALL.contains(pane) then return unknownPane(pane)

    answering {
      val person: Person = App.altitude.service.person.getInRepository(personId)
      val payload = "<!doctype html>" + htmx.html.rename_person_dialog(person, C.UI.RENAME_PERSON_DIALOG_TITLE, pane)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /**
   * The rename dialog's save. A saved name, the name the Person already has included, is an empty success, which completes the
   * dialog; a name that cannot be saved answers the dialog's form again, in place of the submitted one
   * (`dialogFormValidationResponse`), with its error or, for a name another live Person holds, the offer to merge into them. An
   * unknown Person, or another repository's, is a 404.
   */
  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/rename")
  def renamePersonInDialog(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      renamePerson(_ => App.altitude.service.person.getInRepository(personId)) match
        case RenameOutcome.Saved(_) => cask.Response("", 200, Seq(("Content-Type", "text/html")))
        case RenameOutcome.Refused(person, form, errors, holder) =>
          logger.debug(s"Rename dialog refused a name for person [${person.persistedId}]")
          // The form sends its pane back so that the form replacing it names the same pencil; a missing or unknown one is the
          // Main pane's, as for the dialog itself
          val pane = form.obj
            .get(Api.Field.Person.PANE)
            .flatMap(_.strOpt)
            .filter(Const.PeoplePane.ALL.contains)
            .getOrElse(Const.PeoplePane.MAIN)
          dialogFormValidationResponse(
            "<!doctype html>" + htmx.html.rename_person_dialog(
              person,
              C.UI.RENAME_PERSON_DIALOG_TITLE,
              pane,
              fieldErrors = errors,
              formJson = form,
              conflictingPerson = holder))
    }

  /**
   * How a rename ended: `Saved` with the Person as stored, which is also the answer for the name they already had, or `Refused`
   * with the submitted form, its field errors and, when another live Person holds the name, that Person (`holder`), whom the
   * form offers to merge into.
   */
  private enum RenameOutcome:
    case Saved(person: Person)
    case Refused(person: Person, form: ujson.Obj, errors: Map[String, String], holder: Option[Person])

  private val nameScrubber = DataScrubber(trim = List(Api.Field.Person.NAME))

  private val nameValidator = ApiRequestValidator(
    required = List(Api.Field.Person.NAME),
    maxLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MAX_NAME_LENGTH),
    minLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MIN_NAME_LENGTH)
  )

  /**
   * Renames the Person that `personOf` finds for the request's scrubbed JSON to the name in it: the one rename of the inline name
   * editor and of the rename dialog, each of which renders the outcome its own way.
   */
  private def renamePerson(personOf: ujson.Obj => Person)(using request: Request): RenameOutcome =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)
    val person: Person = personOf(jsonIn)

    def refused(errors: Map[String, String], holder: Option[Person] = None): RenameOutcome =
      RenameOutcome.Refused(person, jsonIn, errors, holder)

    try nameValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return refused(validationException.errors.toMap)

    val newName = jsonIn(Api.Field.Person.NAME).str

    // Only the exact stored name is no change: a name that differs in case alone is a rename, and saves
    if PersonService.normalizedName(newName) == person.name.get then
      logger.debug("Name has not changed")
      return RenameOutcome.Saved(person)

    try App.altitude.service.person.updateName(person, newName = newName)
    catch
      case _: DuplicateException =>
        // The form offers to merge into the person holding the name. No one holds it when the holder was merged away meanwhile,
        // or when the constraint was not the name's: the plain error then, with nothing to merge into
        App.altitude.service.person.getLiveByNameKey(newName) match
          case Some(holder) =>
            logger.debug(s"Name [$newName] is held by person [${holder.persistedId}], offering to merge into them")
            return refused(Map.empty, holder = Some(holder))
          case None =>
            return refused(Map(Api.Field.Person.NAME -> C.Msg.Err.PERSON_NAME_ALREADY_EXISTS))
      case ex: ValidationException =>
        return refused(Map(Api.Field.Person.NAME -> ex.message))

    RenameOutcome.Saved(App.altitude.service.person.getById(person.persistedId))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/p/:personId/name")
  def viewPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val payload = "<!doctype html>" + htmx.html.view_person_name(person = person)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The Merge people modal for the two people asked for. `pane` is sent by the rename dialog's offer to merge alone
   * (`includes/person_name_taken`): the pane of the People tab (`Const.PeoplePane`) whose pencil opened that dialog. The merge
   * modal takes the rename dialog's place, so the modal names that pencil, the requested source's in that pane, as the control
   * focus goes back to when it closes; a name that is no pane is a 400. Without `pane` focus goes back to the control that
   * asked for the modal.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/merge")
  def showMergePeopleModal(
      repoId: String,
      mergeSourceId: String,
      mergeDestId: String,
      keepDirection: Boolean = false,
      pane: Option[String] = None)(using request: Request): Response[String] =
    if pane.exists(!Const.PeoplePane.ALL.contains(_)) then return unknownPane(pane.get)

    val requestedSourcePerson: Person = App.altitude.service.person.getById(mergeSourceId)
    val requestedDestPerson: Person = App.altitude.service.person.getById(mergeDestId)

    // A drop merges the person with fewer faces into the other, whichever was dropped on which; the name editor's offer keeps
    // its direction (`keepDirection`), so the person already holding the name stays, and keeps its ID
    val (sourcePerson, destPerson) =
      if keepDirection || requestedSourcePerson.numOfFaces < requestedDestPerson.numOfFaces then
        (requestedSourcePerson, requestedDestPerson)
      else (requestedDestPerson, requestedSourcePerson)

    val payload = "<!doctype html>" + htmx.html.merge_people_modal(
      title = C.UI.MERGE_PEOPLE_DIALOG_TITLE,
      mergeSourcePerson = sourcePerson,
      mergeDestPerson = destPerson,
      returnFocusPencil = pane.map(_ -> requestedSourcePerson.persistedId)
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/src/:srcPersonId/dest/:destPersonId")
  def mergePeople(repoId: String, srcPersonId: String, destPersonId: String)(using request: Request): Response[String] =
    val srcPerson: Person = App.altitude.service.person.getById(srcPersonId)
    val destPerson: Person = App.altitude.service.person.getById(destPersonId)
    logger.debug(s"MERGING: {${srcPerson.name} into ${destPerson.name}")

    App.altitude.service.person.merge(dest = destPerson, source = srcPerson)

    cask.Response("", 204, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/hide")
  def hidePerson(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    logger.trace(s"Hiding person: $personId")

    val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = true)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/p/:personId")
  def discardPersonAsBadMatch(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    logger.trace(s"Discarding person: $personId")

    val updatedPerson = App.altitude.service.person.markAsBadMatch(person)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/show")
  def showPerson(repoId: String, personId: String)(using request: Request): Response[String] =
    logger.trace(s"Showing person: $personId")
    val person: Person = App.altitude.service.person.getById(personId)

    val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = false)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
