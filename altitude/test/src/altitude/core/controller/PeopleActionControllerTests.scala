package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, include, not, should, shouldBe }

import altitude.core.App
import altitude.core.models.Person

@DoNotDiscover class PeopleActionControllerTests extends ControllerTestCore {

  test("Choose person cover face modal renders without a width parameter") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val person: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(person)

        val response = requests.get(
          s"$host/htmx/people/r/$repoId/modals/choose-person-cover-face",
          params = Map("personId" -> person.persistedId),
          cookies = testContext.cookies,
          check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="faceSelector"""")
    }
  }

  /**
   * The client owns the search parameters, so a merge reports only that it happened. Showing the destination person is a normal
   * search the browser runs off the dialog's success event.
   */
  test("Merging people returns no content and does not redirect to search results") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val sourcePerson: Person = testApp.service.person.addPerson(Person())
        val destPerson: Person = testApp.service.person.addPerson(Person())
        testContext.addTestFacesAndAssets(sourcePerson)
        testContext.addTestFacesAndAssets(destPerson)

        val response = requests.put(
          s"$host/htmx/people/r/$repoId/src/${sourcePerson.persistedId}/dest/${destPerson.persistedId}",
          cookies = testContext.cookies,
          maxRedirects = 0,
          // The global compress decorator still labels the empty body as gzipped, which the client cannot unwrap
          autoDecompress = false,
          check = false
        )

        response.statusCode shouldBe 204
        response.headers.keys should not contain "location"
    }
  }
}
