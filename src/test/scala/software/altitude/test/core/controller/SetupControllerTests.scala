package software.altitude.test.core.controller

import org.scalatest.DoNotDiscover
import play.api.libs.json.Json
import software.altitude.core.Altitude
import software.altitude.core.Api
import software.altitude.core.models.Field
import software.altitude.core.models.User
import software.altitude.core.util.Query
import software.altitude.core.util.Util
import software.altitude.test.core.ControllerTestCore

@DoNotDiscover class SetupControllerTests(override val testApp: Altitude) extends ControllerTestCore {

  test("Should return validation errors") {

    post("/htmx/admin/setup", body = "{}") {
      val requiredCount = "required".r.findAllIn(response.body).toList.size
      requiredCount should be (5)
    }
  }

  test("Should not allow mismatching passwords") {
    val payload = Json.obj(
      Api.Field.Setup.ADMIN_EMAIL -> "me@me.com",
      Api.Field.Setup.ADMIN_NAME -> "First Last",
      Api.Field.Setup.REPOSITORY_NAME -> "My Repository",
      Api.Field.Setup.PASSWORD -> "password",
      Api.Field.Setup.PASSWORD2 -> "oops"
    )
    post("/htmx/admin/setup", body = payload.toString()) {
      response.body should include ("Passwords do not match")
    }
  }

  test("Should successfully initialize when the form is valid") {
    val email = Util.randomStr(5).toLowerCase() + "@me.com"
    val payload = Json.obj(
      Api.Field.Setup.ADMIN_EMAIL -> email,
      Api.Field.Setup.ADMIN_NAME -> "First Last",
      Api.Field.Setup.REPOSITORY_NAME -> "My Repository",
      Api.Field.Setup.PASSWORD -> "password3000",
      Api.Field.Setup.PASSWORD2 -> "password3000"
    )

    post("/htmx/admin/setup", body = payload.toString()) {
      status should equal(200)

      val query = new Query(params = Map(Field.User.EMAIL -> email))
      val user: User = testApp.service.user.getOneByQuery(query)
      user.lastActiveRepoId should not be None

      // Sends HTMX redirect to the root path
      response.getHeader("HX-Redirect") should be("/")
    }
  }
}
