package software.altitude.core.controllers.htmx

import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.{DataScrubber, DuplicateException, ValidationException}
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.Person
import software.altitude.core.{Const => C}

/**
  * @ /htmx/people/
 */
class PeopleActionController extends BaseHtmxController{

  before() {
    requireLogin()
  }

  val showPeopleTab: Route = get("/r/:repoId/tab") {
    val people: List[Person] = app.service.person.getAll

    ssp("htmx/people", "people" -> people)
  }

  val searchPerson: Route = get("/r/:repoId/p/:personId/search") {
    val personId: String = params.get("personId").get
    searchPerson(personId = personId)
  }

  val showEditPersonName: Route = get("/r/:repoId/p/:personId/name/edit") {
    val personId: String = params.get("personId").get
    val person: Person = app.service.person.getById(personId)

    ssp("htmx/edit_person_name", "person" -> person)
  }

  val editPersonName: Route = put("/r/:repoId/p/:personId/name/edit") {
    val dataScrubber = DataScrubber(
      trim = List(C.Api.Person.NAME),
    )

    val apiRequestValidator = ApiRequestValidator(
      required = List(
        C.Api.Person.NAME, C.Api.ID),
      maxLengths = Map(
        C.Api.Person.NAME -> C.Api.Constraints.MAX_NAME_LENGTH,
      ),
      minLengths = Map(
        C.Api.Person.NAME -> C.Api.Constraints.MIN_NAME_LENGTH,
      ),
    )

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)
    val personId =  (jsonIn \ C.Api.ID).as[String]
    val person: Person = app.service.person.getById(personId)

    def haltWithValidationErrors(errors: Map[String, String]): Unit = {
      halt(200,
        ssp(
          "htmx/edit_person_name",
          "fieldErrors" -> errors,
          "formJson" -> jsonIn,
          "person" -> person,
          "newName" -> (jsonIn \ C.Api.Person.NAME).asOpt[String],
        ),
      )
    }

    try {
      apiRequestValidator.validate(jsonIn)
    } catch {
      case validationException: ValidationException =>
        haltWithValidationErrors(
          validationException.errors.toMap)
    }

    val newName = (jsonIn \ C.Api.Person.NAME).as[String]

    try {
      app.service.person.updateName(person, newName=newName)
    } catch {
      case ex: DuplicateException =>
        val message = ex.message.getOrElse("Person by that name already exists")
        haltWithValidationErrors(Map(C.Api.Person.NAME -> message))
    }

    val updatedPerson: Person = app.service.person.getById(personId)
    ssp("htmx/view_person_name", "person" -> updatedPerson)
  }

  val showMergePeopleModal: Route = get("/r/:repoId/modals/merge") {
    val srcPersonId = request.getParameter(C.Api.Person.MERGE_SOURCE_ID)
    val destPersonId = request.getParameter(C.Api.Person.MERGE_DEST_ID)

    val requestedSourcePerson: Person = app.service.person.getById(srcPersonId)
    val requestedDestPerson: Person = app.service.person.getById(destPersonId)

    // if the merge is requested into a person with fewer faces, swap the source and dest
    val (sourcePerson, destPerson) = if (requestedSourcePerson.numOfFaces < requestedDestPerson.numOfFaces) {
      (requestedSourcePerson, requestedDestPerson)
    } else {
      (requestedDestPerson, requestedSourcePerson)
    }

    ssp("htmx/merge_people_modal",
      "minWidth" -> C.UI.MERGE_PEOPLE_MODAL_MIN_WIDTH,
      "title" -> C.UI.MERGE_PEOPLE_MODAL_TITLE,
      C.Api.Person.MERGE_SOURCE_PERSON -> sourcePerson,
      C.Api.Person.MERGE_DEST_PERSON -> destPerson)
  }

  val mergePeople: Route = put("/r/:repoId/src/:srcPersonId/dest/:destPersonId") {
    val srcPersonId: String = params.get("srcPersonId").get
    val destPersonId: String = params.get("destPersonId").get

    val srcPerson: Person = app.service.person.getById(srcPersonId)
    val destPerson: Person = app.service.person.getById(destPersonId)
    logger.info(s"MERGING: {${srcPerson.name} into ${destPerson.name}")

    app.service.person.merge(dest = destPerson, source = srcPerson)
    searchPerson(personId = destPersonId)
  }

  private def searchPerson(personId: String): String = {
    val person: Person = app.service.person.getById(personId)

    ssp("htmx/person", "person" -> person)
  }
}
