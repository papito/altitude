package altitude.core.unit

import altitude.launcher.Launcher
import altitude.test.ChildJvm
import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.DurationInt

/**
 * The native jar's launcher (`launcher/src/altitude/launcher/Launcher.java`): its checks of the Java version and the platform,
 * and the second JVM it starts where ONNX Runtime's telemetry variable is missing. The launcher runs from the test classpath,
 * where it has no jar and so no platform, and calls [[LauncherStub]] in place of the application.
 */
@DoNotDiscover class LauncherTests extends AnyFunSuite with TestFocus {

  private val variable = Launcher.TELEMETRY_VARIABLE

  private val stubClass = LauncherStub.getClass.getName.stripSuffix("$")

  /** The JVM options that make the launcher call the stub, and the property the stub reports */
  private val stubOptions = Seq(s"-D${Launcher.MAIN_PROPERTY}=$stubClass", s"-D${LauncherStub.PROPERTY}=passed")

  private def withoutVariable(environment: java.util.Map[String, String]): Unit = environment.remove(variable): Unit

  /** The launcher started in a second JVM, calling the stub with `arguments`, with or without the telemetry variable */
  private def startLauncher(arguments: Seq[String], withVariable: Boolean): ChildJvm.Started =
    ChildJvm.start(
      classOf[Launcher].getName,
      arguments,
      environment => if (withVariable) environment.put(variable, Launcher.TELEMETRY_OFF): Unit else withoutVariable(environment),
      jvmOptions = stubOptions
    )

  /**
   * A jar in `folder` as a native jar is to the launcher: the launcher's class and the stub's, and a manifest naming the launcher
   * as its main class and `platform` as its own. The Scala libraries the stub needs are the manifest's `Class-Path`, from the
   * test classpath.
   */
  private def launcherJar(folder: Path, platform: String): Path = {
    val jar = folder.resolve(s"altitude-test-$platform.jar")
    val manifest = new java.util.jar.Manifest()
    val attributes = manifest.getMainAttributes
    attributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    attributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, classOf[Launcher].getName)
    attributes.putValue("Altitude-Platform", platform)
    val scalaLibraries = Seq(classOf[scala.Option[?]], classOf[scala.runtime.Scala3RunTime$]).map(
      library => Path.of(library.getProtectionDomain.getCodeSource.getLocation.toURI).toUri.toString)
    attributes.put(java.util.jar.Attributes.Name.CLASS_PATH, scalaLibraries.distinct.mkString(" "))

    val out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)
    try
      Seq(classOf[Launcher].getName, stubClass, s"$stubClass$$").foreach {
        className =>
          val entry = className.replace('.', '/') + ".class"
          out.putNextEntry(new java.util.jar.JarEntry(entry))
          val in = getClass.getClassLoader.getResourceAsStream(entry)
          try in.transferTo(out)
          finally in.close()
          out.closeEntry()
      }
    finally out.close()
    jar
  }

  /** What the stub reported, by name, from a line of the launcher's output */
  private def reportIn(output: String): Map[String, String] =
    output.linesIterator
      .collectFirst { case line if line.startsWith(LauncherStub.REPORT) => line.stripPrefix(LauncherStub.REPORT) }
      .value
      .trim
      .split(' ')
      .map(_.split('=') match {
        case Array(name, value) => name -> value
        case Array(name) => name -> ""
        case other => fail(s"Unreadable report: ${other.mkString("=")}")
      })
      .toMap

  test("Started without the telemetry variable, the launcher runs the application in a second JVM that has it") {

    /**
     * Setup:
     *
     * The launcher started in a JVM whose environment has no `ORT_DISABLE_TELEMETRY`, with a system property of its own and two
     * program arguments, calling a stub that reports what its process has.
     *
     * Assertions:
     *
     * The stub runs in another process than the launcher's, one the launcher started, and that process has the variable at the
     * value that switches ONNX Runtime's telemetry off, the launcher JVM's system property and the same program arguments.
     */
    val started = startLauncher(Seq("report", "one", "two"), withVariable = false)
    try {
      val (exitCode, output) = started.await(120.seconds)
      withClue(output) {
        exitCode shouldBe 0
        val report = reportIn(output)
        report("variable") shouldBe Launcher.TELEMETRY_OFF
        report("parent") shouldBe started.process.pid().toString
        report("pid") should not be started.process.pid().toString
        report("property") shouldBe "passed"
        report("args") shouldBe "one,two"
      }
    } finally started.close()
  }

  test("Started with the telemetry variable, the launcher runs the application in its own JVM") {

    /**
     * Setup:
     *
     * The launcher started in a JVM whose environment has `ORT_DISABLE_TELEMETRY=1`, calling the stub that reports what its
     * process has.
     *
     * Assertions:
     *
     * The stub runs in the launcher's own process, with the variable and the program arguments: no second JVM is started.
     */
    val started = startLauncher(Seq("report", "one"), withVariable = true)
    try {
      val (exitCode, output) = started.await(120.seconds)
      withClue(output) {
        exitCode shouldBe 0
        val report = reportIn(output)
        report("pid") shouldBe started.process.pid().toString
        report("variable") shouldBe Launcher.TELEMETRY_OFF
        report("args") shouldBe "one"
      }
    } finally started.close()
  }

  test("The launcher exits with the second JVM's status") {

    /**
     * Setup:
     *
     * The launcher started without the telemetry variable, calling a stub that exits with status 7, and once more with a stub
     * that exits with 0.
     *
     * Assertions:
     *
     * The launcher's own process exits with the status of the second JVM each time.
     */
    Seq(7, 0).foreach {
      status =>
        val started = startLauncher(Seq("exit", status.toString), withVariable = false)
        try {
          val (exitCode, output) = started.await(120.seconds)
          withClue(output) {
            exitCode shouldBe status
          }
        } finally started.close()
    }
  }

  test("A SIGTERM to the launcher is passed on, and the launcher ends only once the second JVM's stop has finished") {

    /**
     * Setup:
     *
     * The launcher started without the telemetry variable, calling a stub that stays up, and whose shutdown hook, as the server's
     * drain does, takes two seconds before it writes a marker file. Once the stub is up, the launcher alone is sent a SIGTERM.
     *
     * Assertions:
     *
     * The launcher's process ends, and when it has, the marker is written and the second JVM is gone: the stop reached the second
     * JVM, whose shutdown work ran to its end, and the launcher waited for it.
     *
     * Edge cases:
     *
     * Not on Windows, where `Process.destroy` kills a process with no shutdown hook run, and where the launcher leaves the stop
     * to the console's Ctrl-C, which reaches both processes by itself.
     */
    assume(!sys.props("os.name").toLowerCase.startsWith("windows"))

    val folder = Files.createTempDirectory("launcher-stop")
    val marker = folder.resolve("drained")
    val started = startLauncher(Seq("hold", marker.toString), withVariable = false)
    // The second JVM, once it has said it is up: killed at the end whatever happened, since a launcher that did not wait for it
    // would leave it running with no parent, out of `close`'s reach
    var stub: Option[Long] = None
    try {
      val deadline = System.nanoTime() + 60.seconds.toNanos
      while (!started.output.contains(LauncherStub.HOLDING) && started.process.isAlive && System.nanoTime() < deadline)
        Thread.sleep(50)
      stub = s"${LauncherStub.HOLDING}: (\\d+)".r.findFirstMatchIn(started.output).map(_.group(1).toLong)
      withClue(started.output) {
        stub.value should not be started.process.pid()
      }

      // A SIGTERM on Linux and macOS
      started.process.destroy()
      withClue(started.output) {
        started.process.waitFor(60, TimeUnit.SECONDS) shouldBe true
        Files.exists(marker) shouldBe true
        ProcessHandle.of(stub.value).filter(_.isAlive).isPresent shouldBe false
      }
    } finally {
      stub.foreach(pid => ProcessHandle.of(pid).ifPresent(process => process.destroyForcibly(): Unit))
      started.close()
      FileUtils.deleteDirectory(folder.toFile)
    }
  }

  test("Started from its jar, the launcher starts the second JVM from the jar too, and refuses another platform's jar") {

    /**
     * Setup:
     *
     * A jar in a scratch folder holding the launcher's class and the stub's, its manifest naming the launcher as its main class,
     * started with `java -jar` and no telemetry variable: once with this machine's platform in its manifest, calling the stub
     * that reports its process, and once with another platform's.
     *
     * Assertions:
     *
     * The jar of this machine's platform runs the stub in a second JVM, started with `-jar` and the jar, where the jar's manifest
     * applies, and with the variable. The jar of another platform exits with status 1, saying it is that platform's jar, and runs
     * no stub.
     */
    val here = Launcher.platformOf(sys.props("os.name"), sys.props("os.arch"))
    val other = Seq("linux-x86_64", "linux-arm64").find(_ != here).value
    val folder = Files.createTempDirectory("launcher-jar")
    try {
      val jar = launcherJar(folder, here)
      val started = ChildJvm.startJar(jar, Seq("report"), withoutVariable, jvmOptions = stubOptions)
      try {
        val (exitCode, output) = started.await(120.seconds)
        withClue(output) {
          exitCode shouldBe 0
          val report = reportIn(output)
          report("parent") shouldBe started.process.pid().toString
          // As real paths: the JVM names the jar it runs by its real path, and a temp folder may be under a link (macOS' /var)
          Path.of(report("command")).toRealPath() shouldBe jar.toRealPath()
          report("variable") shouldBe Launcher.TELEMETRY_OFF
        }
      } finally started.close()

      val refused = ChildJvm.startJar(launcherJar(folder, other), Seq("report"), withoutVariable, jvmOptions = stubOptions)
      try {
        val (exitCode, output) = refused.await(120.seconds)
        withClue(output) {
          exitCode shouldBe 1
          output should include(s"This is Altitude's jar for $other")
          (output should not).include(LauncherStub.REPORT)
        }
      } finally refused.close()
    } finally FileUtils.deleteDirectory(folder.toFile)
  }

  test("A debugger's or JMX port among the JVM options is met with how to skip the second JVM") {

    /**
     * Setup:
     *
     * The JVM options the launcher passes on to its second JVM: with a debugger agent, with a JMX port, and with neither.
     *
     * Assertions:
     *
     * With a debugger agent or a JMX port the launcher has a line to say first, naming the telemetry variable that, set by the
     * person, runs Altitude in the one JVM; with neither it has none.
     */
    Seq("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "-Dcom.sun.management.jmxremote.port=9010")
      .foreach {
        option =>
          val hint = Option(Launcher.relaunchHint(java.util.List.of("-Xmx2g", option))).value
          hint should include(s"Set ${Launcher.TELEMETRY_VARIABLE}=${Launcher.TELEMETRY_OFF} yourself")
      }
    Launcher.relaunchHint(java.util.List.of("-Xmx2g", "-Dfoo=bar")) shouldBe null
    Launcher.relaunchHint(java.util.List.of()) shouldBe null
  }

  test("The Java version is read in both of its forms") {

    /**
     * Setup:
     *
     * `java.specification.version` as Java 8 and older give it, as Java 9 and later do, and values that are no version.
     *
     * Assertions:
     *
     * `1.8` is 8, and `17`, `21` and `25` are themselves; an empty value, a missing one and a word are 0, which the check takes
     * for a version it cannot judge.
     */
    Launcher.javaMajor("1.8") shouldBe 8
    Launcher.javaMajor("1.7") shouldBe 7
    Launcher.javaMajor("17") shouldBe 17
    Launcher.javaMajor("21") shouldBe 21
    Launcher.javaMajor("25") shouldBe 25
    Launcher.javaMajor("") shouldBe 0
    Launcher.javaMajor(null) shouldBe 0
    Launcher.javaMajor("unknown") shouldBe 0
  }

  test("The machine's platform is named as the native jars are") {

    /**
     * Setup:
     *
     * `os.name` and `os.arch` as Java gives them on each system a native jar is built for, and on others.
     *
     * Assertions:
     *
     * Linux, macOS and Windows on 64-bit x86 and ARM are named `<os>-<arch>` as the jars are, an Intel Mac and Windows on ARM
     * included, though no jar exists for either.
     *
     * Edge cases:
     *
     * A 32-bit system, another architecture and another operating system have no platform.
     */
    Launcher.platformOf("Linux", "amd64") shouldBe "linux-x86_64"
    Launcher.platformOf("Linux", "aarch64") shouldBe "linux-arm64"
    Launcher.platformOf("Mac OS X", "aarch64") shouldBe "macosx-arm64"
    Launcher.platformOf("Mac OS X", "x86_64") shouldBe "macosx-x86_64"
    Launcher.platformOf("Windows 11", "amd64") shouldBe "windows-x86_64"
    Launcher.platformOf("Windows 11", "aarch64") shouldBe "windows-arm64"

    Launcher.platformOf("Linux", "arm") shouldBe null
    Launcher.platformOf("Linux", "riscv64") shouldBe null
    Launcher.platformOf("Windows 10", "x86") shouldBe null
    Launcher.platformOf("FreeBSD", "amd64") shouldBe null
  }

  test("A start on an older Java or another platform is refused with what to do instead") {

    /**
     * Setup:
     *
     * The check of each start the launcher refuses and of those it lets through: the jar's platform and version against the
     * machine's `os.name`, `os.arch` and Java version.
     *
     * Assertions:
     *
     * The linux-x86_64 jar on Linux x64 with Java 21 or 25 starts. On Java 17 or 8 it is refused naming Java 21 and where to get
     * it. On Apple Silicon it is refused naming the jar of that platform to download; on an older Java as well, both are said. On
     * an Intel Mac and on a system with no build it is refused naming Docker, and on Windows on ARM naming Docker Desktop and the
     * x64 jar, without a word about the Java version, since no jar would run there. An Intel Java on a Mac is told that on Apple
     * Silicon an arm64 Java is what to install, first of all when the jar is Apple Silicon's, and a 32-bit Java that on a 64-bit
     * machine a 64-bit Java is.
     *
     * Edge cases:
     *
     * A launcher with no platform of its own, as from a classes folder, checks the Java version alone, and a jar with no version
     * names the jar to download by its platform.
     */
    def problem(java: String, os: String, arch: String, jar: String = "linux-x86_64", version: String = "1.2.3"): Option[String] =
      Option(Launcher.startProblem(java, "/opt/java", os, arch, jar, version))

    problem("21", "Linux", "amd64") shouldBe None
    problem("25", "Linux", "amd64") shouldBe None

    Seq("17", "1.8").foreach {
      java =>
        val refusal = problem(java, "Linux", "amd64").value
        refusal should include(s"this is Java ${Launcher.javaMajor(java)} (/opt/java)")
        refusal should include("Java 21")
        refusal should include("https://adoptium.net")
    }

    val otherJar = problem("21", "Mac OS X", "aarch64").value
    otherJar should include("download altitude-1.2.3-macosx-arm64.jar")
    (otherJar should not).include("Altitude needs Java")
    val both = problem("17", "Mac OS X", "aarch64").value
    both should include("download altitude-1.2.3-macosx-arm64.jar")
    both should include("Altitude needs Java 21")

    Seq(problem("17", "Mac OS X", "x86_64").value, problem("17", "Linux", "arm").value).foreach {
      refusal =>
        refusal should include("Docker")
        // Java 17 is not complained of: no jar runs here, whatever the Java
        (refusal should not).include("Altitude needs Java")
    }
    problem("17", "Mac OS X", "x86_64").value should include("Intel Mac")

    // An Intel Java on Apple Silicon reports x86_64 as an Intel Mac does: the arm64 jar is the tell
    val rosetta = problem("21", "Mac OS X", "x86_64", jar = "macosx-arm64").value
    rosetta should include("Rosetta")
    rosetta should include("install an arm64 Java 21")
    problem("21", "Mac OS X", "x86_64").value should include("install an arm64 Java 21")
    // A 32-bit Java, on what may be a 64-bit machine
    Seq("x86", "i386", "arm").foreach(arch => problem("21", "Linux", arch).value should include("install a 64-bit Java 21"))
    (problem("21", "Linux", "riscv64").value should not).include("64-bit Java")
    val windowsArm = problem("21", "Windows 11", "aarch64", jar = "windows-x86_64").value
    windowsArm should include("Docker Desktop")
    windowsArm should include("windows-x86_64 jar")

    problem("21", "Mac OS X", "x86_64", jar = null) shouldBe None
    problem("17", "Mac OS X", "x86_64", jar = null).value should include("Java 21")
    problem("21", "Linux", "aarch64", version = null).value should include("download the linux-arm64 jar")
  }
}

/** What the launcher of [[LauncherTests]] calls in place of the application, by its first argument */
object LauncherStub {
  val REPORT = "Stub report:"
  val HOLDING = "Stub holding as process"

  /** A system property the launcher's JVM is started with, which its second JVM must have too */
  val PROPERTY = "altitude.launcher.test.property"

  def main(args: Array[String]): Unit = args.toList match {
    // Its process, its parent, the telemetry variable, the property, its other arguments and what its JVM runs, on one line
    case "report" :: rest =>
      val process = ProcessHandle.current()
      val parent = process.parent().map[String](_.pid().toString).orElse("")
      // What the JVM was started to run: the jar's path for `-jar`, else the main class
      val command = sys.props.getOrElse("sun.java.command", "").split(' ').head
      println(
        s"$REPORT pid=${process.pid()} parent=$parent variable=${sys.env.getOrElse(Launcher.TELEMETRY_VARIABLE, "")} " +
          s"property=${sys.props.getOrElse(PROPERTY, "")} args=${rest.mkString(",")} command=$command")

    case "exit" :: status :: Nil => sys.exit(status.toInt)

    // Stays up until it is stopped, and then takes two seconds before it writes the marker file named
    case "hold" :: marker :: Nil =>
      Runtime.getRuntime.addShutdownHook(
        new Thread(
          () => {
            Thread.sleep(2000)
            Files.writeString(Path.of(marker), "drained"): Unit
          }))
      println(s"$HOLDING: ${ProcessHandle.current().pid()}")
      Thread.sleep(Long.MaxValue)

    case other => throw IllegalArgumentException(s"Unknown stub arguments: ${other.mkString(" ")}")
  }
}
