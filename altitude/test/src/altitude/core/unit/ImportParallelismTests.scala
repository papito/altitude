package altitude.core.unit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Const
import altitude.core.util.ImportParallelism
import altitude.core.util.ImportParallelism.Resolution

/** The rule that sizes the import dispatcher, as a function of the Setting, the machine and the server's cap */
@DoNotDiscover class ImportParallelismTests extends AnyFunSuite {

  private val GB: Long = 1024L * 1024 * 1024
  private val NO_CAP = 0
  private val AUTOMATIC = 0
  private val CAP_KEY = Const.Conf.IMPORT_PARALLELISM_MAX
  private val CAPPED = s"capped by $CAP_KEY"

  /** The WARN messages `ImportParallelism` logs while the block runs */
  private def warnings(block: => Unit): Seq[String] = {
    val logger = LoggerFactory.getLogger(ImportParallelism.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)
    logger.setAdditive(false)
    try block
    finally {
      logger.setAdditive(true)
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
    appender.list.asScala.toSeq.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
  }

  test("An explicit Setting is the value, whatever the machine") {
    ImportParallelism.resolve(3, 24, 31 * GB, NO_CAP) shouldBe Resolution(3, "the Setting")
    ImportParallelism.resolve(64, 2, 1 * GB, NO_CAP) shouldBe Resolution(64, "the Setting")
  }

  test("An explicit Setting of 1 stays 1: the floor belongs to the automatic rule") {
    ImportParallelism.resolve(1, 24, 31 * GB, NO_CAP) shouldBe Resolution(1, "the Setting")
    ImportParallelism.resolve(1, 24, 31 * GB, 2) shouldBe Resolution(1, "the Setting")
  }

  test("Automatic takes half the cores when the memory affords more") {
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, NO_CAP) shouldBe
      Resolution(12, "automatic, half of 24 cores or 15 GB of 31 GB")
  }

  test("Automatic truncates half an odd number of cores") {
    ImportParallelism.resolve(AUTOMATIC, 7, 31 * GB, NO_CAP).value shouldBe 3
  }

  test("Automatic takes what half the memory affords on a small box with many cores") {
    // (4096 MB - 384 MB) / 512 MB
    ImportParallelism.resolve(AUTOMATIC, 24, 8 * GB, NO_CAP) shouldBe
      Resolution(7, "automatic, half of 24 cores or 4 GB of 8 GB")
  }

  test("Automatic computes in bytes, past what an Int holds") {
    // 4 TB: an Int would overflow on the total long before the core half had its say
    ImportParallelism.resolve(AUTOMATIC, 256, 4096 * GB, NO_CAP) shouldBe
      Resolution(128, "automatic, half of 256 cores or 2048 GB of 4096 GB")
    // Just enough memory for the seventh thread, and a byte short of it
    val forSeven = 2 * (ImportParallelism.POOL_COST + 7 * ImportParallelism.THREAD_COST)
    ImportParallelism.resolve(AUTOMATIC, 24, forSeven, NO_CAP).value shouldBe 7
    ImportParallelism.resolve(AUTOMATIC, 24, forSeven - 2, NO_CAP).value shouldBe 6
  }

  test("Automatic is never fewer than the floor, and says so when the floor raised it") {
    ImportParallelism.resolve(AUTOMATIC, 2, 3 * GB, NO_CAP) shouldBe
      Resolution(2, "automatic, half of 2 cores or 1 GB of 3 GB, never fewer than 2")
    // One core
    ImportParallelism.resolve(AUTOMATIC, 1, 31 * GB, NO_CAP) shouldBe
      Resolution(2, "automatic, half of 1 core or 15 GB of 31 GB, never fewer than 2")
    // Half the memory holds the shared net and not one thread
    ImportParallelism.resolve(AUTOMATIC, 24, 1 * GB, NO_CAP) shouldBe
      Resolution(2, "automatic, half of 24 cores or 0 GB of 1 GB, never fewer than 2")
    // Half the memory does not even hold the shared net
    ImportParallelism.resolve(AUTOMATIC, 24, GB / 2, NO_CAP) shouldBe
      Resolution(2, "automatic, half of 24 cores or 0 GB of 0 GB, never fewer than 2")
  }

  test("Automatic landing on the floor by itself does not claim the floor raised it") {
    ImportParallelism.resolve(AUTOMATIC, 4, 31 * GB, NO_CAP) shouldBe
      Resolution(2, "automatic, half of 4 cores or 15 GB of 31 GB")
  }

  test("A cap lowers the automatic value and becomes the reason") {
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, 4) shouldBe Resolution(4, CAPPED)
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, ImportParallelism.MIN_THREADS) shouldBe
      Resolution(ImportParallelism.MIN_THREADS, CAPPED)
  }

  test("A cap lowers an explicit Setting too") {
    ImportParallelism.resolve(20, 24, 31 * GB, 4) shouldBe Resolution(4, CAPPED)
  }

  test("A cap at or above the value leaves the original reason") {
    val automatic = Resolution(12, "automatic, half of 24 cores or 15 GB of 31 GB")
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, 12) shouldBe automatic
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, 100) shouldBe automatic

    ImportParallelism.resolve(4, 24, 31 * GB, 4) shouldBe Resolution(4, "the Setting")
    ImportParallelism.resolve(4, 24, 31 * GB, 100) shouldBe Resolution(4, "the Setting")

    // The floor is what the value came from, not the cap at the same number
    ImportParallelism.resolve(AUTOMATIC, 2, 3 * GB, 2) shouldBe
      Resolution(2, "automatic, half of 2 cores or 1 GB of 3 GB, never fewer than 2")
  }

  test("A nonzero cap under the floor is an error naming the key and the value, whatever the Setting") {
    Seq(1, -1, -8).foreach {
      cap =>
        Seq(AUTOMATIC, 1, 5).foreach {
          setting =>
            val error = intercept[IllegalStateException](ImportParallelism.resolve(setting, 24, 31 * GB, cap))
            error.getMessage should include(CAP_KEY)
            error.getMessage should include(cap.toString)
        }
    }
  }

  test("A resolution reads as the Settings screen and the log show it") {
    ImportParallelism.resolve(AUTOMATIC, 24, 31 * GB, NO_CAP).summary shouldBe
      "12 threads: automatic, half of 24 cores or 15 GB of 31 GB"
    ImportParallelism.resolve(3, 24, 31 * GB, NO_CAP).summary shouldBe "3 threads: the Setting"
    ImportParallelism.resolve(8, 24, 31 * GB, 4).summary shouldBe s"4 threads: $CAPPED"
    ImportParallelism.resolve(1, 24, 31 * GB, NO_CAP).summary shouldBe "1 thread: the Setting"
  }

  test("The configured cap is the key's value, and no cap when the key is absent") {
    ImportParallelism.configuredCap(ConfigFactory.parseString(s"$CAP_KEY=6")) shouldBe 6
    ImportParallelism.configuredCap(ConfigFactory.parseString("")) shouldBe NO_CAP
  }

  test("The shipped default is no cap") {
    ImportParallelism.configuredCap(ConfigFactory.defaultReference()) shouldBe NO_CAP
  }

  test("A cap in the application's config overrides the shipped default") {
    val config = ConfigFactory.parseString(s"$CAP_KEY=6").withFallback(ConfigFactory.defaultReference())
    ImportParallelism.configuredCap(config) shouldBe 6
  }

  test("A leftover number for the Setting counts as no cap, and is warned about") {
    // As a number, `import.parallelism` replaces the whole object the cap lives in, and reference.conf promises it is ignored
    val config = ConfigFactory.parseString("import.parallelism=4").withFallback(ConfigFactory.defaultReference())
    config.hasPath(CAP_KEY) shouldBe false
    config.getInt("import.parallelism") shouldBe 4

    var cap = -1
    val warned = warnings { cap = ImportParallelism.configuredCap(config) }

    cap shouldBe NO_CAP
    warned should have size 1
    warned.head should include("import.parallelism")
    warned.head should include(CAP_KEY)
  }

  test("A leftover number in the same file as the cap hides it when it comes after, and not when it comes before") {
    // HOCON keeps the later of the two, so this order loses the cap, which is what the warning is for
    val hidden = ConfigFactory.parseString(s"$CAP_KEY=6\nimport.parallelism=4").withFallback(ConfigFactory.defaultReference())
    ImportParallelism.configuredCap(hidden) shouldBe NO_CAP

    val kept = ConfigFactory.parseString(s"import.parallelism=4\n$CAP_KEY=6").withFallback(ConfigFactory.defaultReference())
    ImportParallelism.configuredCap(kept) shouldBe 6
  }

  test("A cap set, absent or null is not taken for a leftover") {
    Seq(s"$CAP_KEY=6", "", s"$CAP_KEY=null").foreach {
      text => warnings(ImportParallelism.configuredCap(ConfigFactory.parseString(text))) shouldBe empty
    }
  }
}
