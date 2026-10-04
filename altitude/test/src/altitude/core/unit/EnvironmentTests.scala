package altitude.core.unit

import altitude.test.TestFocus
import com.typesafe.config.ConfigFactory
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Environment

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
     * The switch is on only for the true key in dev. In test and prod it is off whatever the key says, and a false key is off
     * everywhere.
     */
    Environment.devSwitch(on, "dev.switch", Environment.Name.DEV) shouldBe true

    Environment.devSwitch(on, "dev.switch", Environment.Name.TEST) shouldBe false
    Environment.devSwitch(on, "dev.switch", Environment.Name.PROD) shouldBe false

    Environment.devSwitch(off, "dev.switch", Environment.Name.DEV) shouldBe false
    Environment.devSwitch(off, "dev.switch", Environment.Name.TEST) shouldBe false
    Environment.devSwitch(off, "dev.switch", Environment.Name.PROD) shouldBe false
  }
}
