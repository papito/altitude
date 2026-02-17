package altitude.core.controller

import altitude.core.App
import org.scalatest.matchers.must.Matchers.endWith
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import org.scalatest.DoNotDiscover

@DoNotDiscover class IndexControllerTests extends ControllerTestCore {

  test("New installation goes to setup page") {
    withServer(App) { host =>
      testApp.service.system.readMetadata.isInitialized shouldBe false
      val response = requests.get(s"$host/")
      response.url should endWith("/setup")
    }
  }

  test("Unauthenticated initialized install is not allowed to access protected route") {
    val repo = testContext.persistRepository() // also creates a user
    testApp.app.isInitialized = true

    withServer(App) { host =>
      val response = requests.get(s"$host/r/${repo.persistedId}", maxRedirects = 0, check = false)
      response.statusCode shouldBe 302
    }
  }
}