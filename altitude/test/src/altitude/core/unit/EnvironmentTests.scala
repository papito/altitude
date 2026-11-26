package altitude.core.unit

import altitude.test.TestFocus
import com.typesafe.config.ConfigFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Environment
import altitude.core.service.UserService

@DoNotDiscover class EnvironmentTests extends funsuite.AnyFunSuite with TestFocus {

  private val on = ConfigFactory.parseString("dev.switch = true")
  private val off = ConfigFactory.parseString("dev.switch = false")

  test("The environment is the altitude.env property, then ALTITUDE_ENV, then prod") {

    /**
     * Setup:
     *
     * The property and the variable each set or not, naming different environments.
     *
     * Assertions:
     *
     * The property wins over the variable, the variable is read when there is no property, and with neither the environment is
     * prod.
     *
     * Edge cases:
     *
     * An empty property or variable counts as unset, so the next source is read, and an empty variable beside a property is no
     * misspelled one.
     */
    val asTest = Map(Environment.Variable.ENV -> "test")

    Environment.resolve(Some("prod"), asTest) shouldBe Environment.Name.PROD
    Environment.resolve(Some("dev"), asTest) shouldBe Environment.Name.DEV
    Environment.resolve(None, asTest) shouldBe Environment.Name.TEST
    Environment.resolve(None, Map.empty) shouldBe Environment.Name.PROD

    Environment.resolve(Some(""), asTest) shouldBe Environment.Name.TEST
    Environment.resolve(Some(""), Map(Environment.Variable.ENV -> "")) shouldBe Environment.Name.PROD
    Environment.resolve(None, Map(Environment.Variable.ENV -> "")) shouldBe Environment.Name.PROD
    Environment.resolve(Some("dev"), Map(Environment.Variable.ENV -> "")) shouldBe Environment.Name.DEV
  }

  test("ALTITUDE_ENV and the property name an environment in each of its accepted spellings") {

    /**
     * Setup:
     *
     * Every accepted spelling of dev, test and prod, given as the variable and as the property.
     *
     * Assertions:
     *
     * Each spelling resolves to its environment from either source, and the accepted spellings are exactly these.
     */
    val spellings = Map(
      "dev" -> Environment.Name.DEV,
      "development" -> Environment.Name.DEV,
      "DEV" -> Environment.Name.DEV,
      "DEVELOPMENT" -> Environment.Name.DEV,
      "test" -> Environment.Name.TEST,
      "TEST" -> Environment.Name.TEST,
      "prod" -> Environment.Name.PROD,
      "production" -> Environment.Name.PROD,
      "PROD" -> Environment.Name.PROD,
      "PRODUCTION" -> Environment.Name.PROD
    )

    spellings.foreach {
      (spelling, environment) =>
        withClue(s"[$spelling]: ") {
          Environment.resolve(None, Map(Environment.Variable.ENV -> spelling)) shouldBe environment
          Environment.resolve(Some(spelling), Map.empty) shouldBe environment
        }
    }

    Environment.ACCEPTED.toSet shouldBe spellings.keySet
  }

  test("A value that names no environment fails, naming the accepted ones") {

    /**
     * Setup:
     *
     * A misspelled environment, a spelling in mixed case and a blank one, given as the variable and as the property.
     *
     * Assertions:
     *
     * Each one throws instead of running as some environment, and the message names the source, the value and every accepted
     * spelling.
     *
     * Edge cases:
     *
     * A misspelled property fails even when the variable names an environment: the next source is not a fallback for a bad value.
     * A misspelled variable fails even when the property names an environment and so wins: the image passes the property, and a
     * typo in its `ALTITUDE_ENV` must not start a server its owner believes is in another environment.
     */
    Seq("dve", "Dev", "staging", " ", "prod ").foreach {
      value =>
        withClue(s"[$value]: ") {
          val ofVariable = the[IllegalArgumentException] thrownBy
            Environment.resolve(None, Map(Environment.Variable.ENV -> value))
          ofVariable.getMessage should include(Environment.Variable.ENV)
          ofVariable.getMessage should include(s"\"$value\"")
          Environment.ACCEPTED.foreach(spelling => ofVariable.getMessage should include(spelling))

          val ofProperty = the[IllegalArgumentException] thrownBy
            Environment.resolve(Some(value), Map(Environment.Variable.ENV -> "test"))
          ofProperty.getMessage should include(Environment.PROPERTY)
          Environment.ACCEPTED.foreach(spelling => ofProperty.getMessage should include(spelling))

          val besideProperty = the[IllegalArgumentException] thrownBy
            Environment.resolve(Some("prod"), Map(Environment.Variable.ENV -> value))
          besideProperty.getMessage shouldBe ofVariable.getMessage
        }
    }
  }

  test("ENV is not read") {

    /**
     * Setup:
     *
     * `ENV`, a POSIX shell's own variable, set to dev, to test and to a value that names no environment, with neither the
     * property nor `ALTITUDE_ENV` set; and `ENV` set to dev beside `ALTITUDE_ENV` set to test.
     *
     * Assertions:
     *
     * The environment is prod whatever `ENV` says, an unrecognised `ENV` fails nothing, and `ALTITUDE_ENV` decides when both are
     * set.
     */
    Environment.resolve(None, Map("ENV" -> "dev")) shouldBe Environment.Name.PROD
    Environment.resolve(None, Map("ENV" -> "test")) shouldBe Environment.Name.PROD
    Environment.resolve(None, Map("ENV" -> "/home/user/.shrc")) shouldBe Environment.Name.PROD
    Environment.resolve(None, Map("ENV" -> "dev", Environment.Variable.ENV -> "test")) shouldBe Environment.Name.TEST
  }

  test("The process's own variables are those named ALTITUDE_ alone") {

    /**
     * Setup:
     *
     * The variables of the test run's process, which the Makefile starts with `ALTITUDE_ENV`.
     *
     * Assertions:
     *
     * Every variable the application reads has the `ALTITUDE_` prefix, and the running environment is the one they resolve to
     * with the `altitude.env` property.
     */
    Environment.variables.keys.foreach(name => name should startWith(Environment.Variable.PREFIX))
    Environment.CURRENT shouldBe Environment.resolve(Option(System.getProperty(Environment.PROPERTY)), Environment.variables)
  }

  test("The application's own location is read through its URI, a jar on a Windows share's included") {

    /**
     * Setup:
     *
     * The location the test run's classes are loaded from, a classes folder, and `file:` URLs as a class loader gives them: of a
     * jar in a folder whose name has a space, and, on Windows, of a jar on a share (`file://server/share/...`).
     *
     * Assertions:
     *
     * The classes' location is the folder they are in. The jar's URL is the jar's path, its space a space and not `%20`. On
     * Windows the share's URL is the UNC path `\\server\share\...`, where `java.io.File(URI)` would refuse it for its authority.
     */
    val classes = Environment.codeLocation.value
    Files.isDirectory(classes) shouldBe true
    Files.exists(classes.resolve(Environment.getClass.getName.replace('.', '/') + ".class")) shouldBe true

    val jar = FileSystems.getDefault.getRootDirectories.iterator.next.resolve("a folder").resolve("altitude.jar")
    val url = jar.toUri.toURL
    url.toString should include("a%20folder")
    Environment.pathOf(url) shouldBe jar

    // On another system a URI's authority names no file: there is no share to name
    if sys.props("os.name").toLowerCase.startsWith("windows") then
      Environment.pathOf(URI("file://server/share/folder/altitude.jar").toURL) shouldBe
        Path.of("\\\\server\\share\\folder\\altitude.jar")
  }

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
