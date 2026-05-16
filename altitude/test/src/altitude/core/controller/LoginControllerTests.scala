package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }

import altitude.core.App

@DoNotDiscover class LoginControllerTests extends ControllerTestCore {

  test("Valid login") {
    val repo = testContext.persistRepository()
    testApp.app.isInitialized = true

    withServer(App) {
      host =>
        val loginResponse = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map(
            "login" -> testContext.user.email,
            "password" -> TestContext.USER_PASSWORD
          ))

        loginResponse.statusCode shouldBe 302

        val indexResponse = requests.get(
          s"$host/r/${testContext.repository.persistedId}",
          cookies = loginResponse.cookies
        )

        indexResponse.statusCode shouldBe 200
        indexResponse.text() should include("<title>ALTITUDE</title>")
    }
  }

  test("Invalid login") {
    val repo = testContext.persistRepository()
    testApp.app.isInitialized = true

    withServer(App) {
      host =>
        val loginResponse = requests.post(
          s"$host/login",
          maxRedirects = 0,
          check = false,
          data = Map(
            "login" -> "blah",
            "password" -> "blahblahblah"
          ))

        loginResponse.statusCode shouldBe 401
    }
  }
}
