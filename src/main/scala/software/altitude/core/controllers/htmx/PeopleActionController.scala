package software.altitude.core.controllers.htmx

import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.DataScrubber
import software.altitude.core.DuplicateException
import software.altitude.core.RequestContext
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.{ Const => C }

/** @ /htmx/people/ */
class PeopleActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val showPeopleTab: Route = get("/r/:repoId/tab") {
    val typeFilter: String = params.getOrElse(Api.Field.People.TYPE_FILTER, Const.PeopleTypeFilter.COMPLETE)
    val people: List[Person] = typeFilter match {
      case Const.PeopleTypeFilter.ALL => app.service.person.getAllNotDiscarded
      case Const.PeopleTypeFilter.HIDDEN => app.service.person.getAllHidden
      case Const.PeopleTypeFilter.COMPLETE => app.service.person.getAllAboveThreshold
      case Const.PeopleTypeFilter.INCOMPLETE => app.service.person.getAllBelowThreshold
    }

    ssp("htmx/people", Api.Field.Person.PEOPLE -> people, Api.Field.People.TYPE_FILTER -> typeFilter)
  }

  val showChoosePersonCoverFaceModal: Route = get("/r/:repoId/modals/choose-person-cover-face") {
    val personId: String = params.get(Api.Field.PERSON_ID).get
    val person: Person = app.service.person.getById(personId)
    val topFaces = app.service.person.getPersonFaces(person.persistedId, limit = 24)

    ssp(
      "htmx/choose_person_cover_face_modal",
      Api.Modal.MIN_WIDTH -> C.UI.CHANGE_PERSON_COVER_IMAGE_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.CHANGE_PERSON_COVER_IMAGE_MODAL_TITLE,
      Api.Field.Person.PERSON -> person,
      Api.Field.Person.FACES -> topFaces
    )
  }

  val setCoverImage: Route = put("/r/:repoId/p/:personId/cover-image") {
    val personId: String = params.get(Api.Field.PERSON_ID).get
    val person: Person = app.service.person.getById(personId)

    val faceId: String = params.get(Api.Field.FACE_ID).get
    val face: Face = app.service.person.getFaceById(faceId)

    logger.info(s"Setting cover image for person $personId to face $faceId")
    val updatedPerson = app.service.person.setFaceAsCover(person, face)

    ssp(
      "htmx/person_inner",
      Api.Field.Search.PERSON -> updatedPerson
    )
  }

  val showEditPersonName: Route = get("/r/:repoId/p/:personId/name/edit") {
    val personId: String = params.get("personId").get
    val person: Person = app.service.person.getById(personId)

    ssp("htmx/edit_person_name", Api.Field.Person.PERSON -> person)
  }

  val editPersonName: Route = put("/r/:repoId/p/:personId/name/edit") {
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

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)
    val personId = (jsonIn \ Api.Field.ID).as[String]
    val person: Person = app.service.person.getById(personId)

    def haltWithValidationErrors(errors: Map[String, String]): Unit = {
      halt(
        200,
        ssp(
          "htmx/edit_person_name",
          Api.Modal.FIELD_ERRORS -> errors,
          Api.Modal.FORM_JSON -> jsonIn,
          Api.Field.Person.PERSON -> person,
          Api.Field.Person.NEW_NAME -> (jsonIn \ Api.Field.Person.NAME).asOpt[String]
        )
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(validationException.errors.toMap)
    }

    val newName = (jsonIn \ Api.Field.Person.NAME).as[String]

    if (newName.toLowerCase == person.name.get.toLowerCase) {
      logger.info("Name has not changed")
      halt(200, ssp("htmx/view_person_name", Api.Field.Person.PERSON -> person))
    }

    try {
      app.service.person.updateName(person, newName = newName)
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Person by that name already exists")
        haltWithValidationErrors(Map(Api.Field.Person.NAME -> message))
    }

    val updatedPerson: Person = app.service.person.getById(personId)
    ssp("htmx/view_person_name", Api.Field.Person.PERSON -> updatedPerson)
  }

  val viewPersonName: Route = get("/r/:repoId/p/:personId/name") {
    val personId = params.get(Api.Field.PERSON_ID).get
    val person: Person = app.service.person.getById(personId)
    ssp("htmx/view_person_name", Api.Field.Person.PERSON -> person)
  }

  val showMergePeopleModal: Route = get("/r/:repoId/modals/merge") {
    val srcPersonId = request.getParameter(Api.Field.Person.MERGE_SOURCE_ID)
    val destPersonId = request.getParameter(Api.Field.Person.MERGE_DEST_ID)

    val requestedSourcePerson: Person = app.service.person.getById(srcPersonId)
    val requestedDestPerson: Person = app.service.person.getById(destPersonId)

    // if the merge is requested into a person with fewer faces, swap the source and dest
    val (sourcePerson, destPerson) = if (requestedSourcePerson.numOfFaces < requestedDestPerson.numOfFaces) {
      (requestedSourcePerson, requestedDestPerson)
    } else {
      (requestedDestPerson, requestedSourcePerson)
    }

    ssp(
      "htmx/merge_people_modal",
      Api.Modal.MIN_WIDTH -> C.UI.MERGE_PEOPLE_MODAL_MIN_WIDTH,
      Api.Modal.TITLE -> C.UI.MERGE_PEOPLE_MODAL_TITLE,
      Api.Field.Person.MERGE_SOURCE_PERSON -> sourcePerson,
      Api.Field.Person.MERGE_DEST_PERSON -> destPerson
    )
  }

  val mergePeople: Route = put("/r/:repoId/src/:srcPersonId/dest/:destPersonId") {
    val srcPersonId: String = params.get("srcPersonId").get
    val destPersonId: String = params.get("destPersonId").get

    val srcPerson: Person = app.service.person.getById(srcPersonId)
    val destPerson: Person = app.service.person.getById(destPersonId)
    logger.info(s"MERGING: {${srcPerson.name} into ${destPerson.name}")

    app.service.person.merge(dest = destPerson, source = srcPerson)
    redirect(s"/htmx/search/r/${RequestContext.getRepository.persistedId}?${Api.Field.Search.PERSON_ID}=$destPersonId")
  }

  val hidePerson: Route = put("/r/:repoId/p/:personId/hide") {
    val personId: String = params.get("personId").get
    val person: Person = app.service.person.getById(personId)
    logger.info(s"Hiding person: $personId")

    val updatedPerson = app.service.person.setVisibility(person, isHidden = true)

    ssp(
      "htmx/person_inner",
      Api.Field.Search.PERSON -> updatedPerson
    )
  }

  val discardPersonAsBadMatch: Route = delete("/r/:repoId/p/:personId") {
    val personId: String = params.get("personId").get
    val person: Person = app.service.person.getById(personId)
    logger.info(s"Discarding person: $personId")

    val updatedPerson = app.service.person.markAsBadMatch(person)

    ssp(
      "htmx/person_inner",
      Api.Field.Search.PERSON -> updatedPerson
    )
  }

  val showPerson: Route = put("/r/:repoId/p/:personId/show") {
    val personId: String = params.get("personId").get
    logger.info(s"Showing person: $personId")
    val person: Person = app.service.person.getById(personId)

    val updatedPerson = app.service.person.setVisibility(person, isHidden = false)

    ssp(
      "htmx/person_inner",
      Api.Field.Search.PERSON -> updatedPerson
    )
  }
}
