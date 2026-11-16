package altitude.core.unit

import altitude.test.TestFocus
import com.typesafe.config.ConfigFactory
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Environment
import altitude.core.service.UserService

@DoNotDiscover class EnvironmentTests extends funsuite.AnyFunSuite with TestFocus {

  private val on = ConfigFactory.parseString("dev.switch = true")
  private val off = ConfigFactory.parseString("dev.switch = false")

  test("A development switch is on only when it is true and the environment is dev") {

    /**
     * Setup:
     *
     * A key set to true and the same key set to false, each read in the dev, test and prod environments.
     *
     * Assertions:
     *
     * The switch is on only for the true key in dev; everywhere else it is off.
     */
    Environment.devSwitch(on, "dev.switch", Environment.Name.DEV) shouldBe true

    Environment.devSwitch(on, "dev.switch", Environment.Name.TEST) shouldBe false
    Environment.devSwitch(on, "dev.switch", Environment.Name.PROD) shouldBe false

    Environment.devSwitch(off, "dev.switch", Environment.Name.DEV) shouldBe false
    Environment.devSwitch(off, "dev.switch", Environment.Name.TEST) shouldBe false
    Environment.devSwitch(off, "dev.switch", Environment.Name.PROD) shouldBe false
  }

  test("A development-only setting is read only in the dev environment, and the dev login with it") {

    /**
     * Setup:
     *
     * A config with `dev.user` and `dev.password` set, and one with them empty, read in the dev, test and prod environments.
     *
     * Assertions:
     *
     * The setting reads its value only in dev and is empty in test and prod. The dev login has credentials only in dev, and none
     * there either when the values are empty.
     */
    val set = ConfigFactory.parseString("""dev.user = "chief", dev.password = "password123"""")
    val empty = ConfigFactory.parseString("""dev.user = "", dev.password = """"")

    Environment.devSetting(set, "dev.user", Environment.Name.DEV) shouldBe "chief"
    Environment.devSetting(set, "dev.user", Environment.Name.TEST) shouldBe ""
    Environment.devSetting(set, "dev.user", Environment.Name.PROD) shouldBe ""

    UserService.devCredentials(set, Environment.Name.DEV) shouldBe Some(("chief", "password123"))
    UserService.devCredentials(set, Environment.Name.TEST) shouldBe None
    UserService.devCredentials(set, Environment.Name.PROD) shouldBe None
    UserService.devCredentials(empty, Environment.Name.DEV) shouldBe None
  }
}
