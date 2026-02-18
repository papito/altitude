package altitude.core.controller

import altitude.core.App
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import org.scalatest.DoNotDiscover

@DoNotDiscover class IndexControllerTests extends ControllerTestCore {

/*
  FIXME: This does not pass within the context of other tests (but will pass on its own) - what state is being shared across tests that is causing this to fail?
  test("New installation goes to setup page", Focused) {
    uninitializeInstance()

    withServer(App) { host =>
      uninitializeInstance()
      testApp.service.system.readMetadata.isInitialized shouldBe false
      val response = requests.get(s"$host/")
      response.url should endWith("/setup")
    }
  }
*/

  test("Unauthenticated initialized install is not allowed to access protected route") {
    val repo = testContext.persistRepository()
    testApp.service.system.readMetadata.isInitialized shouldBe true

    withServer(App) { host =>
      val response = requests.get(s"$host/r/${repo.persistedId}", maxRedirects = 0, check = false)
      response.statusCode shouldBe 302
    }
  }
}