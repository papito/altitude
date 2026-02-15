package altitude.core.controller

import altitude.core.App
import org.scalatest.matchers.must.Matchers.endWith
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import org.scalatest.DoNotDiscover

@DoNotDiscover class IndexControllerTests extends ControllerTestCore {

  test("New installation goes to setup page") {
    withServer(App) { host =>
      testApp.service.system.readMetadata.isInitialized shouldBe false
      val response = requests.get(s"$host/", check = false)
      response.url should endWith("/setup")
    }
  }
}