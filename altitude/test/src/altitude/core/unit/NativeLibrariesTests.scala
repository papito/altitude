package altitude.core.unit

import altitude.test.ChildJvm
import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.Properties
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

import altitude.core.NativeLibraries
import altitude.core.transactions.TransactionManager
import altitude.core.util.LockFile

/**
 * The native jar's libraries unpacked once into the per-user cache (`NativeLibraries`), from a jar written for each test: a
 * manifest with `Altitude-Build` and `Altitude-Platform`, and fake libraries at the paths ONNX Runtime, sqlite-jdbc and
 * sqlite-vector have in a platform jar. Nothing is loaded: the system properties are a `Properties` of the test's own.
 */
@DoNotDiscover class NativeLibrariesTests extends AnyFunSuite with TestFocus {

  private val build = "0123456789abcdef"
  private val platform = "linux-x86_64"

  /** The fake libraries by their entry in the jar */
  private val libraries = Map(
    "ai/onnxruntime/native/linux-x64/libonnxruntime.so" -> "ONNX Runtime".repeat(5000),
    "ai/onnxruntime/native/linux-x64/libonnxruntime4j_jni.so" -> "its JNI library",
    "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so" -> "sqlite-jdbc",
    "sqlite-vector/linux-x86/vector.so" -> "sqlite-vector"
  )

  private def fileName(entry: String): String = entry.split('/').last

  private val now = Instant.parse("2026-06-01T12:00:00Z")

  private def withScratch[T](f: Path => T): T = {
    val scratch = Files.createTempDirectory("native-libraries-test")
    try f(scratch)
    finally FileUtils.deleteDirectory(scratch.toFile)
  }

  /**
   * A jar in `folder` as a platform jar is to the helper: a manifest with `attributes`, a class file and the fake libraries. The
   * default attributes are a platform jar's.
   */
  private def platformJar(
      folder: Path,
      attributes: Map[String, String] = Map(NativeLibraries.BUILD -> build, NativeLibraries.PLATFORM -> platform)): Path = {
    val jar = folder.resolve("altitude-test.jar")
    val manifest = new java.util.jar.Manifest()
    manifest.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    attributes.foreach((name, value) => manifest.getMainAttributes.putValue(name, value))
    val out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)
    try {
      val entries = Seq("altitude/core/App.class" -> "a class") ++ libraries.toSeq
      // Each folder of an entry first, as a jar holds them
      val folders = entries.flatMap((name, _) => name.split('/').init.inits.toSeq.init.map(_.mkString("", "/", "/"))).distinct
      folders.sorted.foreach {
        name =>
          out.putNextEntry(new java.util.jar.JarEntry(name))
          out.closeEntry()
      }
      entries.foreach {
        (name, content) =>
          out.putNextEntry(new java.util.jar.JarEntry(name))
          out.write(content.getBytes("UTF-8"))
          out.closeEntry()
      }
    } finally out.close()
    jar
  }

  /** The natives folder of the per-user cache in a scratch folder: it does not exist until the helper makes it */
  private def nativesIn(scratch: Path): Path = scratch.resolve("cache").resolve("altitude").resolve("natives")

  private def entriesOf(dir: Path): List[String] =
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toList.sorted)

  private def touched(path: Path): Instant = Files.getLastModifiedTime(path).toInstant

  /** The four properties the helper sets, as `properties` has them */
  private def propertiesOf(properties: Properties): Map[String, Option[String]] =
    Seq(
      NativeLibraries.ONNX_RUNTIME_DIR,
      NativeLibraries.SQLITE_JDBC_DIR,
      NativeLibraries.SQLITE_JDBC_NAME,
      TransactionManager.NATIVE_DIR_PROPERTY).map(name => name -> Option(properties.getProperty(name))).toMap

  test("A platform jar's libraries are unpacked once into a folder of its build, and a second start rewrites nothing") {

    /**
     * Setup:
     *
     * A platform jar of a build, an empty scratch user cache, and two starts, each with system properties that name no folder.
     *
     * Assertions:
     *
     * The first start unpacks the four libraries, flat, into `natives/<build>-<platform>/` with the jar's bytes, and sets the
     * properties that make ONNX Runtime, sqlite-jdbc (its folder and its file's name) and sqlite-vector load from there. The
     * second start finds the same folder and sets the same properties, and writes no file again: each keeps the time it was given
     * after the first. Each start touches the folder at its own time.
     *
     * Edge cases:
     *
     * No temporary name is left in the folder, and the jar's class file is not unpacked.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val natives = nativesIn(scratch)
        val first = new Properties()

        val folder = NativeLibraries.prepare(Some(jar), natives, first, now).value

        folder shouldBe natives.resolve(s"$build-$platform")
        entriesOf(folder) shouldBe libraries.keys.map(fileName).toList.sorted
        libraries.foreach((entry, content) => Files.readString(folder.resolve(fileName(entry))) shouldBe content)
        propertiesOf(first) shouldBe Map(
          NativeLibraries.ONNX_RUNTIME_DIR -> Some(folder.toString),
          NativeLibraries.SQLITE_JDBC_DIR -> Some(folder.toString),
          NativeLibraries.SQLITE_JDBC_NAME -> Some("libsqlitejdbc.so"),
          TransactionManager.NATIVE_DIR_PROPERTY -> Some(folder.toString)
        )
        touched(folder) shouldBe now

        // A time no write leaves, so a rewritten file shows
        val written = Instant.parse("2001-01-01T00:00:00Z")
        val files = entriesOf(folder).map(folder.resolve)
        files.foreach(file => Files.setLastModifiedTime(file, FileTime.from(written)))

        val later = now.plus(Duration.ofDays(3))
        val second = new Properties()
        NativeLibraries.prepare(Some(jar), natives, second, later) shouldBe Some(folder)

        propertiesOf(second) shouldBe propertiesOf(first)
        files.map(touched).distinct shouldBe List(written)
        touched(folder) shouldBe later
    }
  }

  test("A library cut short and a temporary file a killed unpack left are replaced, the whole libraries kept") {

    /**
     * Setup:
     *
     * The folder of a build unpacked once, then one library truncated to half its length and a temporary file beside the
     * libraries, as an unpack killed between the write and the move leaves.
     *
     * Assertions:
     *
     * The next start writes the truncated library again with the jar's bytes and removes the temporary file; the other libraries
     * are the files the first start wrote.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val natives = nativesIn(scratch)
        val folder = NativeLibraries.prepare(Some(jar), natives, new Properties(), now).value

        val cut = folder.resolve("libonnxruntime.so")
        val whole = Files.readAllBytes(cut)
        Files.write(cut, whole.take(whole.length / 2))
        Files.writeString(folder.resolve("vector.so.1234.part"), "half a library")
        val kept = folder.resolve("vector.so")
        val written = Instant.parse("2001-01-01T00:00:00Z")
        Files.setLastModifiedTime(kept, FileTime.from(written))

        NativeLibraries.prepare(Some(jar), natives, new Properties(), now) shouldBe Some(folder)

        Files.readAllBytes(cut) shouldBe whole
        entriesOf(folder) shouldBe libraries.keys.map(fileName).toList.sorted
        touched(kept) shouldBe written
    }
  }

  test("A second process starting while the lock is held waits for it, then unpacks") {

    /**
     * Setup:
     *
     * A platform jar and an empty scratch user cache. This JVM holds the natives folder's lock file while a second JVM, logging
     * as a server does, starts the helper over the same cache.
     *
     * Assertions:
     *
     * The second JVM logs that another process holds the lock and waits, the build's folder holding no library meanwhile. Once
     * this JVM lets the lock go, it unpacks every library and exits cleanly.
     *
     * Edge cases:
     *
     * A second JVM still running at its timeout is killed and fails the test.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val natives = Files.createDirectories(nativesIn(scratch))
        val folder = natives.resolve(s"$build-$platform")

        val started = LockFile.locked(natives.resolve(NativeLibraries.LOCK_FILE), "holding the lock for the test") {
          val started = ChildJvm.start(
            NativeLibrariesProbe.getClass.getName.stripSuffix("$"),
            Seq(jar.toString, natives.toString),
            jvmOptions = Seq("-Dlogback.configurationFile=logback.xml"))
          try {
            val waiting = "Another process is unpacking the native libraries"
            val deadline = System.nanoTime() + 60.seconds.toNanos
            while (!started.output.contains(waiting) && started.process.isAlive && System.nanoTime() < deadline)
              Thread.sleep(100)
            withClue(started.output) {
              started.output should include(waiting)
              started.process.isAlive shouldBe true
            }
            (!Files.exists(folder) || entriesOf(folder).isEmpty) shouldBe true
            started
          } catch {
            case failure: Throwable =>
              started.close()
              throw failure
          }
        }

        // The lock is let go: the second JVM goes on
        try {
          val (exitCode, output) = started.await(120.seconds)
          withClue(output) {
            exitCode shouldBe 0
            output should include(s"${NativeLibrariesProbe.PREPARED}: $folder")
          }
          entriesOf(folder) shouldBe libraries.keys.map(fileName).toList.sorted
        } finally started.close()
    }
  }

  test("A folder of another build untouched for 30 days is pruned, and one touched since is kept") {

    /**
     * Setup:
     *
     * A user cache whose natives folder holds the folders of three other builds, last touched 31 days, 30 days less a minute and
     * 29 days before the start, each with a library, and the start's own build's folder, unpacked once and last touched 60 days
     * before.
     *
     * Assertions:
     *
     * The start removes the folder untouched for 31 days with what it holds, keeps the other two as they are, and keeps its own
     * folder, which it touches and finds whole.
     *
     * Edge cases:
     *
     * The lock file is kept.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val natives = nativesIn(scratch)
        val own = NativeLibraries.prepare(Some(jar), natives, new Properties(), now).value
        Files.setLastModifiedTime(own, FileTime.from(now.minus(Duration.ofDays(60))))

        def otherBuild(name: String, untouchedFor: Duration): Path = {
          val folder = Files.createDirectories(natives.resolve(name))
          Files.writeString(folder.resolve("libonnxruntime.so"), "an older build's")
          Files.setLastModifiedTime(folder, FileTime.from(now.minus(untouchedFor)))
          folder
        }
        val stale = otherBuild("1111111111111111-linux-x86_64", Duration.ofDays(31))
        val almost = otherBuild("2222222222222222-linux-x86_64", Duration.ofDays(30).minusMinutes(1))
        val fresh = otherBuild("3333333333333333-linux-x86_64", Duration.ofDays(29))

        NativeLibraries.prepare(Some(jar), natives, new Properties(), now) shouldBe Some(own)

        Files.exists(stale) shouldBe false
        entriesOf(almost) shouldBe List("libonnxruntime.so")
        entriesOf(fresh) shouldBe List("libonnxruntime.so")
        entriesOf(own) shouldBe libraries.keys.map(fileName).toList.sorted
        touched(own) shouldBe now
        entriesOf(natives) shouldBe (NativeLibraries.LOCK_FILE :: List(own, almost, fresh).map(_.getFileName.toString)).sorted
    }
  }

  test("With the properties set, as in the image, nothing is changed and nothing is written") {

    /**
     * Setup:
     *
     * A platform jar, and system properties naming a folder for each library, as the Docker image's JVM options do. The user
     * cache is one that cannot be known: asking for it fails the test.
     *
     * Assertions:
     *
     * The helper answers no folder, leaves every property as it was, and never asks for the user cache, so nothing is made or
     * touched there.
     *
     * Edge cases:
     *
     * Where one library's folder alone is named, that property is kept and the others are set to the unpacked folder.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val image = new Properties()
        image.setProperty(NativeLibraries.ONNX_RUNTIME_DIR, "/app/native")
        image.setProperty(NativeLibraries.SQLITE_JDBC_DIR, "/app/native")
        image.setProperty(NativeLibraries.SQLITE_JDBC_NAME, "libsqlitejdbc.so")
        image.setProperty(TransactionManager.NATIVE_DIR_PROPERTY, "/app/native")
        val before = propertiesOf(image)

        NativeLibraries.prepare(Some(jar), fail("The user cache was asked for"), image, now) shouldBe None
        propertiesOf(image) shouldBe before

        val natives = nativesIn(scratch)
        val partly = new Properties()
        partly.setProperty(NativeLibraries.ONNX_RUNTIME_DIR, "/opt/onnxruntime")
        val folder = NativeLibraries.prepare(Some(jar), natives, partly, now).value
        propertiesOf(partly) shouldBe Map(
          NativeLibraries.ONNX_RUNTIME_DIR -> Some("/opt/onnxruntime"),
          NativeLibraries.SQLITE_JDBC_DIR -> Some(folder.toString),
          NativeLibraries.SQLITE_JDBC_NAME -> Some("libsqlitejdbc.so"),
          TransactionManager.NATIVE_DIR_PROPERTY -> Some(folder.toString)
        )
    }
  }

  test("Outside a platform jar nothing is changed and nothing is written") {

    /**
     * Setup:
     *
     * No jar, as a start from a classes folder has (Mill's `run`, the tests), and a jar with the same libraries whose manifest
     * names no build or no platform, as every jar but a platform jar has.
     *
     * Assertions:
     *
     * Each answers no folder and sets no property, and the user cache is never asked for.
     */
    withScratch {
      scratch =>
        val properties = new Properties()
        NativeLibraries.prepare(None, fail("The user cache was asked for"), properties, now) shouldBe None

        for (
          attributes <- Seq(
            Map.empty[String, String],
            Map(NativeLibraries.BUILD -> build),
            Map(NativeLibraries.PLATFORM -> platform))
        ) {
          val jar = platformJar(Files.createTempDirectory(scratch, "jar"), attributes)
          NativeLibraries.prepare(Some(jar), fail("The user cache was asked for"), properties, now) shouldBe None
        }
        properties.isEmpty shouldBe true
    }
  }

  test("The folder the start unpacked into is touched while the instance runs, more often than another start prunes") {

    /**
     * Setup:
     *
     * A folder standing for the one this process's start unpacked its libraries into, last touched 40 days ago, held as the
     * folder in use; then no folder in use.
     *
     * Assertions:
     *
     * The touch an instance schedules leaves the folder touched now, and it is scheduled more often than a folder untouched for
     * long is pruned, so a start of another build never prunes the folder of an instance that is running, whatever its engine.
     *
     * Edge cases:
     *
     * With no folder in use, as outside a platform jar and in the image, the touch does nothing and throws nothing.
     */
    withScratch {
      scratch =>
        val folder = Files.createDirectories(scratch.resolve(s"$build-$platform"))
        Files.setLastModifiedTime(folder, FileTime.from(Instant.now().minus(Duration.ofDays(40))))
        val before = NativeLibraries.inUse
        val touchedFrom = Instant.now().minusSeconds(1)
        try {
          NativeLibraries.inUse = Some(folder)
          NativeLibraries.touchInUse()
          touched(folder).isBefore(touchedFrom) shouldBe false

          NativeLibraries.inUse = None
          NativeLibraries.touchInUse()
        } finally NativeLibraries.inUse = before

        NativeLibraries.TOUCH_EVERY.toMillis should be < NativeLibraries.UNUSED_FOR.toMillis
    }
  }

  test("A user cache that cannot be written fails, naming the folder") {

    /**
     * Setup:
     *
     * A platform jar, and a user cache where a file stands in place of the `altitude` folder, so no folder can be made in it on
     * any system, whoever runs the test.
     *
     * Assertions:
     *
     * The helper throws, naming the build's folder it could not unpack into and the error, which is its cause, and sets no
     * property.
     */
    withScratch {
      scratch =>
        val jar = platformJar(scratch)
        val natives = nativesIn(scratch)
        Files.createDirectories(natives.getParent.getParent)
        Files.writeString(natives.getParent, "a file where the folder would be")
        val properties = new Properties()

        val refusal = intercept[IllegalStateException](NativeLibraries.prepare(Some(jar), natives, properties, now))

        refusal.getMessage should include(natives.resolve(s"$build-$platform").toString)
        refusal.getCause shouldBe a[java.io.IOException]
        refusal.getMessage should include(refusal.getCause.getClass.getSimpleName)
        properties.isEmpty shouldBe true
    }
  }
}

/** What the second JVM of the lock test runs: the helper over the jar and the natives folder named, then the folder it answered */
object NativeLibrariesProbe {
  val PREPARED = "Native libraries prepared"

  def main(args: Array[String]): Unit = {
    val folder = NativeLibraries.prepare(Some(Path.of(args(0))), Path.of(args(1)), new Properties(), Instant.now())
    println(s"$PREPARED: ${folder.getOrElse("none")}")
  }
}
