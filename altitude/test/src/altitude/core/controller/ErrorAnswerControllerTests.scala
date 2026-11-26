package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.Environment

/** A route that throws, and one whose argument can be refused, as any route of `App` may */
class FailingRoutes extends cask.Routes:

  @cask.get("/fails")
  def fails(): String = throw IllegalStateException(FailingRoutes.DETAIL)

  @cask.get("/takes-a-number")
  def takesANumber(number: Int): String = number.toString

  initialize()

object FailingRoutes:
  /** What an exception's message may hold, and no client should read */
  val DETAIL = "SELECT password_hash FROM account"

/** Keeps the failing routes' stack traces, which cask prints for every error whatever the debug mode, out of the test output */
private object SilentLogger extends cask.util.Logger:
  def exception(t: Throwable): Unit = ()
  def debug(t: sourcecode.Text[Any])(implicit f: sourcecode.File, line: sourcecode.Line): Unit = ()

/** The failing routes answered as `App` answers an error: with its debug mode */
object FailingApp extends cask.Main:
  override def debugMode: Boolean = App.debugMode
  override def allRoutes: Seq[cask.Routes] = Seq(new FailingRoutes)
  implicit override def log: cask.util.Logger = SilentLogger

/** The failing routes answered in debug mode, as `App` answers in dev */
object FailingDebugApp extends cask.Main:
  override def debugMode: Boolean = true
  override def allRoutes: Seq[cask.Routes] = Seq(new FailingRoutes)
  implicit override def log: cask.util.Logger = SilentLogger

@DoNotDiscover class ErrorAnswerControllerTests extends ControllerTestCore {

  test("Outside dev, an error is answered without its stack trace or its message") {

    /**
     * Setup:
     *
     * The test environment, which is not dev, and two routes served with `App`'s debug mode: one that throws an exception whose
     * message quotes SQL, and one called with an argument it cannot read. The same routes are also served in debug mode, as dev
     * answers.
     *
     * Assertions:
     *
     * `App`'s debug mode is off. The exception is a 500 and the bad argument a 400, each with the status line alone as its body:
     * no exception class, no message, no stack frame, no argument name.
     *
     * Edge cases:
     *
     * In debug mode the same two requests do show the exception with its message and the argument's name, so the debug mode is
     * what keeps them from a client. A request `App` itself cannot bind, a login with no fields, is answered the same bare way.
     */
    Environment.isDev shouldBe false
    App.debugMode shouldBe false

    withServer(FailingApp) {
      host =>
        val failed = requests.get(s"$host/fails", check = false)
        failed.statusCode shouldBe 500
        failed.text() shouldBe "Error 500: Internal Server Error"

        val refused = requests.get(s"$host/takes-a-number?number=seven", check = false)
        refused.statusCode shouldBe 400
        refused.text() shouldBe "Error 400: Bad Request"
    }

    withServer(FailingDebugApp) {
      host =>
        val failed = requests.get(s"$host/fails", check = false)
        failed.statusCode shouldBe 500
        failed.text() should include(FailingRoutes.DETAIL)
        failed.text() should include("IllegalStateException")
        failed.text() should include("FailingRoutes")

        val refused = requests.get(s"$host/takes-a-number?number=seven", check = false)
        refused.statusCode shouldBe 400
        refused.text() should include("number")
    }

    withServer(App) {
      host =>
        val unbound = requests.post(s"$host/login", check = false, maxRedirects = 0, data = Map("unknown" -> "field"))
        unbound.statusCode shouldBe 400
        unbound.text() shouldBe "Error 400: Bad Request"
    }
  }
}
