package altitude.core.routes.web.partial

import altitude.core.{Api, App, Const, DataScrubber, DuplicateException, RequestContext, ValidationException, Const => C}
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.{Face, Person}
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger
import play.api.libs.json.JsObject

class PeopleActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/people"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/tab")
  def showPeopleTab(repoId: String, typeFilter: String = Const.PeopleTypeFilter.COMPLETE)(using request: Request): Response[String] =
    val people: List[Person] = typeFilter match
      case Const.PeopleTypeFilter.ALL => App.altitude.service.person.getAllNotDiscarded
      case Const.PeopleTypeFilter.HIDDEN => App.altitude.service.person.getAllHidden
      case Const.PeopleTypeFilter.COMPLETE => App.altitude.service.person.getAllAboveThreshold
      case Const.PeopleTypeFilter.INCOMPLETE => App.altitude.service.person.getAllBelowThreshold

    val payload = "<!doctype html>" + htmx.html.people(people = people, typeFilter = typeFilter)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/choose-person-cover-face")
  def showChoosePersonCoverFaceModal(repoId: String, personId: String, minWidth: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val topFaces = App.altitude.service.person.getPersonFaces(person.persistedId, limit = 24)
    val payload = "<!doctype html>" + htmx.html.choose_person_cover_face_modal(
      minWidth = C.UI.CHANGE_PERSON_COVER_IMAGE_MODAL_MIN_WIDTH,
      title = C.UI.CHANGE_PERSON_COVER_IMAGE_MODAL_TITLE,
      person = person,
      faces = topFaces
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/cover-image")
  def setCoverImage(repoId: String, personId: String, faceId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val face: Face = App.altitude.service.person.getFaceById(faceId)

    logger.info(s"Setting cover image for person $personId to face $faceId")
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
    val dataScrubber = DataScrubber(
      trim = List(Api.Field.Person.NAME)
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Person.NAME, Api.Field.ID),
      maxLengths = Map(
        Api.Field.Person.NAME -> Api.Constraints.MAX_NAME_LENGTH
      ),
      minLengths = Map(
        Api.Field.Person.NAME -> Api.Constraints.MIN_NAME_LENGTH
      ),
      uuid = List(Api.Field.ID)
    )

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedJson.get)
    val personIdFromJson = (jsonIn \ Api.Field.ID).as[String]
    val person: Person = App.altitude.service.person.getById(personIdFromJson)

    def responseWithValidationErrors(errors: Map[String, String]): Response[String] =
      val payload = "<!doctype html>" + htmx.html.edit_person_name(
        fieldErrors = errors,
        formJson = jsonIn,
        person = person
      )
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

    try
      apiRequestValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        return responseWithValidationErrors(validationException.errors.toMap)

    val newName = (jsonIn \ Api.Field.Person.NAME).as[String]

    if newName.toLowerCase == person.name.get.toLowerCase then
      logger.info("Name has not changed")
      val payload = "<!doctype html>" + htmx.html.view_person_name(person = person)
      return cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

    try
      App.altitude.service.person.updateName(person, newName = newName)
    catch
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Person by that name already exists")
        return responseWithValidationErrors(Map(Api.Field.Person.NAME -> message))

    val updatedPerson: Person = App.altitude.service.person.getById(personIdFromJson)
    val payload = "<!doctype html>" + htmx.html.view_person_name(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/p/:personId/name")
  def viewPersonName(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    val payload = "<!doctype html>" + htmx.html.view_person_name(person = person)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/merge")
  def showMergePeopleModal(repoId: String, srcPersonId: String, destPersonId: String)(using request: Request): Response[String] =
    val requestedSourcePerson: Person = App.altitude.service.person.getById(srcPersonId)
    val requestedDestPerson: Person = App.altitude.service.person.getById(destPersonId)

    // if the merge is requested into a person with fewer faces, swap the source and dest
    val (sourcePerson, destPerson) = if requestedSourcePerson.numOfFaces < requestedDestPerson.numOfFaces then
      (requestedSourcePerson, requestedDestPerson)
    else
      (requestedDestPerson, requestedSourcePerson)

    val payload = "<!doctype html>" + htmx.html.merge_people_modal(
      minWidth = C.UI.MERGE_PEOPLE_MODAL_MIN_WIDTH,
      title = C.UI.MERGE_PEOPLE_MODAL_TITLE,
      mergeSourcePerson = sourcePerson,
      mergeDestPerson = destPerson
    )
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/src/:srcPersonId/dest/:destPersonId")
  def mergePeople(repoId: String, srcPersonId: String, destPersonId: String)(using request: Request): Response[String] =
    val srcPerson: Person = App.altitude.service.person.getById(srcPersonId)
    val destPerson: Person = App.altitude.service.person.getById(destPersonId)
    logger.info(s"MERGING: {${srcPerson.name} into ${destPerson.name}")

    App.altitude.service.person.merge(dest = destPerson, source = srcPerson)
    cask.Response(
      data = "",
      statusCode = 200,
      headers = Seq(
        ("Content-Type", "text/html"),
        ("HX-Redirect", s"/htmx/search/r/${RequestContext.getRepository.persistedId}?${Api.Field.Search.PERSON_ID}=$destPersonId")
      )
    )

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/hide")
  def hidePerson(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    logger.info(s"Hiding person: $personId")

    val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = true)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/p/:personId")
  def discardPersonAsBadMatch(repoId: String, personId: String)(using request: Request): Response[String] =
    val person: Person = App.altitude.service.person.getById(personId)
    logger.info(s"Discarding person: $personId")

    val updatedPerson = App.altitude.service.person.markAsBadMatch(person)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/p/:personId/show")
  def showPerson(repoId: String, personId: String)(using request: Request): Response[String] =
    logger.info(s"Showing person: $personId")
    val person: Person = App.altitude.service.person.getById(personId)

    val updatedPerson = App.altitude.service.person.setVisibility(person, isHidden = false)

    val payload = "<!doctype html>" + htmx.html.person_inner(person = updatedPerson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()


