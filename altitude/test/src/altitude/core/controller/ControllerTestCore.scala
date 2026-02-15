package altitude.core.controller

import altitude.test.TestFocus
import io.undertow.Undertow
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, funsuite}
import org.slf4j.{Logger, LoggerFactory}

class ControllerTestCore
  extends funsuite.AnyFunSuite
  with  BeforeAndAfterAll
  with TestFocus {

  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(example.defaultHandler)
      .build

    server.start()

    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

}

