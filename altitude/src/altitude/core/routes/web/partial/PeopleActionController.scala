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
import altitude.core.models.Permission
import altitude.core.models.Person
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.refusedView
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.service.PersonService

class PeopleActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/people"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showPeopleTab(repoId: String)(using request: Request): Response[String] =
    // The shell only; each pane's list comes from `listPeople`
    val payload = "<!doctype html>" + htmx.html.people()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The people in the comma-separated People `buckets`. Only `triaged=true` shows triaged items, as in the repository view.
   * `pane` goes into each rename pencil's ID, since a Person can be listed in both panes.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/list")
  def listPeople(
      repoId: String,
      buckets: String = Const.PeopleBucket.MATCHED,
      triaged: Option[Boolean] = None,
      pane: String = Const.PeoplePane.MAIN)(using request: Request): Response[String] =
    refusedView(Const.Search.View.DEFAULT, triaged) match
      case Some(refusal) => return refusal
      case None =>
    val requested: Set[String] = App.altitude.service.person.parseBuckets(buckets)
    val unknown = requested -- Const.PeopleBucket.ALL
    if unknown.nonEmpty then return textError(s"Unknown People bucket: ${unknown.mkString(", ")}", 400)
    if !Const.PeoplePane.ALL.contains(pane) then return unknownPane(pane)

    val listed = App.altitude.service.person.getListedInBuckets(requested, showTriaged = triaged.contains(true))
    val payload = "<!doctype html>" + htmx.html.people_list(listed = listed, pane = pane)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  private def unknownPane(pane: String): Response[String] = textError(s"Unknown People pane: $pane", 400)

  /**
   * The people a Face can be given to, for the Change combobox. Given `faceId`, the people nearest the Face come first as
   * Suggested, and the list after it leaves out them and the Face's own Person.
   */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.get(f"/$prefix/r/:repoId/assignable")
  def listAssignablePeople(repoId: String, faceId: Option[String] = None)(using request: Request): Response[String] =
    answering {
      val assignable = App.altitude.service.person.assignablePeople(faceId)
      val payload = "<!doctype html>" + htmx.html.assignable_people(people = assignable.others, suggested = assignable.suggested)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /** A person's results title, read again after a Face correction because its Hide and Discard links depend on the count */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/p/:personId/title")
  def showPersonTitle(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      val payload = "<!doctype html>" + htmx.html.person_inner(person = person)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /** The Faces a cover can be chosen from, best first, leaving out those on an asset marked for purging */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/modals/choose-person-cover-face")
  def showChoosePersonCoverFaceModal(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      val topFaces = App.altitude.service.person.getPersonFaces(person.persistedId, limit = 24)
      htmlResponse(
        htmx.html.choose_person_cover_face_modal(
          title = C.UI.CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE,
          person = person,
          faces = topFaces
        ))
    }

  /** A Face of another Person, or on an asset marked for purging, is a plain-text 400 */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/p/:personId/cover-image")
  def setCoverImage(repoId: String, personId: String, faceId: String)(using request: Request): Response[String] =
    answering {
      logger.trace(s"Setting cover image for person $personId to face $faceId")
      val updatedPerson = App.altitude.service.person.setFaceAsCover(personId, faceId)

      htmlResponse(htmx.html.person_inner(person = updatedPerson))
    }

  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.get(f"/$prefix/r/:repoId/p/:personId/name/edit")
  def showEditPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      htmlResponse(htmx.html.edit_person_name(person = person))
    }

  /** The inline name editor's save, for the Person in the path (an ID in the body is ignored); a merged-away Person is a 404 */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.put(f"/$prefix/r/:repoId/p/:personId/name/edit")
  def editPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val payload = renamePerson(App.altitude.service.person.getLiveInRepository(personId)) match
        case RenameOutcome.Saved(person) => htmx.html.view_person_name(person = person)
        case RenameOutcome.Refused(person, form, errors, holder) =>
          htmx.html.edit_person_name(fieldErrors = errors, formJson = form, person = person, conflictingPerson = holder)
      cask.Response("<!doctype html>" + payload, 200, Seq(("Content-Type", "text/html")))
    }

  /**
   * The People tab's rename dialog. `pane` names the pencil that focus returns to on close. A Person merged away or discarded as
   * a bad match is a 404.
   */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.get(f"/$prefix/r/:repoId/dialogs/rename-person")
  def showRenamePersonDialog(repoId: String, personId: String, pane: String = Const.PeoplePane.MAIN)(using
      request: Request): Response[String] =
    if !Const.PeoplePane.ALL.contains(pane) then return unknownPane(pane)

    answering {
      val person: Person = App.altitude.service.person.getLiveInRepository(personId)
      val payload = "<!doctype html>" + htmx.html.rename_person_dialog(person, C.UI.RENAME_PERSON_DIALOG_TITLE, pane)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
    }

  /** A refused name answers the form again, with its error or the offer to merge into the Person holding the name */
  @requireLogin()
  @requirePermission(Permission.EditMetadata)
  @cask.put(f"/$prefix/r/:repoId/p/:personId/rename")
  def renamePersonInDialog(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      renamePerson(App.altitude.service.person.getLiveInRepository(personId)) match
        case RenameOutcome.Saved(_) => cask.Response("", 200, Seq(("Content-Type", "text/html")))
        case RenameOutcome.Refused(person, form, errors, holder) =>
          logger.debug(s"Rename dialog refused a name for person [${person.persistedId}]")
          // The form sends its pane back so the replacement form names the same pencil
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

  /** `Saved` covers an unchanged name too; `holder` is the live Person who holds the name, whom the form offers to merge into */
  private enum RenameOutcome:
    case Saved(person: Person)
    case Refused(person: Person, form: ujson.Obj, errors: Map[String, String], holder: Option[Person])

  private val nameScrubber = DataScrubber(trim = List(Api.Field.Person.NAME))

  private val nameValidator = ApiRequestValidator(
    required = List(Api.Field.Person.NAME),
    maxLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MAX_NAME_LENGTH),
    minLengths = Map(Api.Field.Person.NAME -> Api.Constraints.MIN_NAME_LENGTH)
  )

  /** The one rename behind the inline name editor and the rename dialog, which each render the outcome their own way */
  private def renamePerson(person: Person)(using request: Request): RenameOutcome =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)

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
        // No live holder when it was merged away meanwhile, or the constraint was not the name's: the plain error then
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
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      htmlResponse(htmx.html.view_person_name(person = person))
    }

  /**
   * `pane` comes only from the rename dialog's offer to merge: the modal takes that dialog's place, so focus returns to the
   * source's pencil in that pane. Without it, focus returns to the control that opened the modal.
   */
  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/modals/merge")
  def showMergePeopleModal(
      repoId: String,
      mergeSourceId: String,
      mergeDestId: String,
      keepDirection: Boolean = false,
      pane: Option[String] = None)(using request: Request): Response[String] =
    if pane.exists(!Const.PeoplePane.ALL.contains(_)) then return unknownPane(pane.get)

    answering {
      val requestedSourcePerson: Person = App.altitude.service.person.getById(mergeSourceId)
      val requestedDestPerson: Person = App.altitude.service.person.getById(mergeDestId)

      // A drop merges the person with fewer faces into the other, whichever was dropped on which; the name editor's offer keeps
      // its direction (`keepDirection`), so the person already holding the name stays, and keeps its ID
      val (sourcePerson, destPerson) =
        if keepDirection || requestedSourcePerson.numOfFaces < requestedDestPerson.numOfFaces then
          (requestedSourcePerson, requestedDestPerson)
        else (requestedDestPerson, requestedSourcePerson)

      htmlResponse(
        htmx.html.merge_people_modal(
          title = C.UI.MERGE_PEOPLE_DIALOG_TITLE,
          mergeSourcePerson = sourcePerson,
          mergeDestPerson = destPerson,
          returnFocusPencil = pane.map(_ -> requestedSourcePerson.persistedId)
        ))
    }

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/src/:srcPersonId/dest/:destPersonId")
  def mergePeople(repoId: String, srcPersonId: String, destPersonId: String)(using request: Request): Response[String] =
    answering {
      val srcPerson: Person = App.altitude.service.person.getById(srcPersonId)
      val destPerson: Person = App.altitude.service.person.getById(destPersonId)
      logger.debug(s"MERGING: {${srcPerson.name} into ${destPerson.name}")

      App.altitude.service.person.merge(dest = destPerson, source = srcPerson)

      noContentResponse
    }

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/p/:personId/hide")
  def hidePerson(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      logger.trace(s"Hiding person: $personId")

      val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = true)

      htmlResponse(htmx.html.person_inner(person = updatedPerson))
    }

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.delete(f"/$prefix/r/:repoId/p/:personId")
  def discardPersonAsBadMatch(repoId: String, personId: String)(using request: Request): Response[String] =
    answering {
      val person: Person = App.altitude.service.person.getById(personId)
      logger.trace(s"Discarding person: $personId")

      val updatedPerson = App.altitude.service.person.markAsBadMatch(person)

      htmlResponse(htmx.html.person_inner(person = updatedPerson))
    }

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.put(f"/$prefix/r/:repoId/p/:personId/show")
  def showPerson(repoId: String, personId: String)(using request: Request): Response[String] =
    logger.trace(s"Showing person: $personId")
    answering {
      val person: Person = App.altitude.service.person.getById(personId)

      val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = false)

      htmlResponse(htmx.html.person_inner(person = updatedPerson))
    }

  initialize()
