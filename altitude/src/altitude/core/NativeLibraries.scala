package altitude.core

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.control.NonFatal

import altitude.core.transactions.TransactionManager
import altitude.core.util.LockFile
import altitude.core.util.UserCache

/**
 * The native jar's ONNX Runtime, sqlite-jdbc and sqlite-vector libraries, unpacked once for every start of the same build into
 * the per-user cache, `<user cache>/altitude/natives/<build>-<platform>/`, beside the models' default folder whatever
 * `models.dir` says. Left to themselves, ONNX Runtime and sqlite-jdbc copy their libraries into the temp folder at every start,
 * about 30 MB, which a killed run leaves there, and which on Windows, where a loaded library cannot be deleted, even a clean stop
 * leaves; SQLite loads the vector extension from a file alone. javacpp's libraries (OpenCV, FFmpeg, OpenBLAS) are left to
 * javacpp, which keeps a cache of its own in the home folder.
 *
 * A start loads each library from the folder through its loader's system property, which [[prepare]] sets before either loader
 * runs: `onnxruntime.native.path`, `org.sqlite.lib.path` with `org.sqlite.lib.name`, and `altitude.native.dir`
 * (`TransactionManager.vectorExtensionPath`). A property that is set already is the caller's and is kept: the Docker image sets
 * all of them, with its libraries in `/app/native`, so its start writes nothing (its root filesystem is read-only, and its home
 * folder may not be writable). Started from a classes folder (Mill's `run`, the tests), or from any jar whose manifest names no
 * build (none but `./mill altitude.platformJar`'s does), it does nothing either.
 *
 * Each start touches its folder, and so does a running instance every [[TOUCH_EVERY]] ([[touchInUse]], which `Altitude`
 * schedules), whatever its engine: SQLite's pools load sqlite-vector from the folder anew with each connection they replace, and
 * on Windows a pruning start would delete what a running instance has not loaded and leave the rest. A start prunes the folders
 * of other builds untouched for [[UNUSED_FOR]], so no start removes the folder another running instance loads from.
 */
object NativeLibraries:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The manifest attributes of a platform jar: a hash of its native files, and its platform (`build.mill`'s `platformJar`) */
  val BUILD = "Altitude-Build"
  val PLATFORM = "Altitude-Platform"

  /** The loaders' own system properties */
  val ONNX_RUNTIME_DIR = "onnxruntime.native.path"
  val SQLITE_JDBC_DIR = "org.sqlite.lib.path"
  val SQLITE_JDBC_NAME = "org.sqlite.lib.name"

  /** How long a folder of another build is left untouched before a start prunes it */
  val UNUSED_FOR: Duration = Duration.ofDays(30)

  /** How often a running instance touches the folder it loads from, well within [[UNUSED_FOR]] */
  val TOUCH_EVERY: FiniteDuration = 1.day

  /** The lock file in the natives folder, which every unpack and prune holds */
  val LOCK_FILE = ".lock"

  /** What an unpack writes before it moves the file to its name, so a name always holds a whole library */
  private val PART = ".part"

  /** A build and a platform as a platform jar has them: safe in a folder's name on every system */
  private val Safe = "[0-9A-Za-z_-]+".r

  /**
   * A library in a platform jar: the folder its files are under there (`build.mill`'s `platformNatives`, which puts each where
   * its loader looks on the classpath), and the property that makes its loader load from a folder. sqlite-jdbc's loader is also
   * given the name of its one file.
   */
  private enum Library(val folder: String, val property: String):
    case OnnxRuntime extends Library("ai/onnxruntime/native/", ONNX_RUNTIME_DIR)
    case SqliteJdbc extends Library("org/sqlite/native/", SQLITE_JDBC_DIR)
    case SqliteVector extends Library("sqlite-vector/", TransactionManager.NATIVE_DIR_PROPERTY)

  /** The folder this process's start unpacked into, which [[touchInUse]] touches; None where it unpacked nothing */
  @volatile private[core] var inUse: Option[Path] = None

  /**
   * The start's: the jar this class is in, the per-user cache, the JVM's system properties and the time now. Throws when the
   * libraries cannot be put in place, which fails the start.
   */
  def prepare(): Unit = inUse = prepare(ownJar, userNatives, System.getProperties, Instant.now())

  /** `natives/` in the per-user cache; asked for only where there is something to unpack */
  private def userNatives: Path =
    val home = System.getProperty("user.home")
    val cache = UserCache.folder(sys.env.get, home).getOrElse {
      throw IllegalStateException(
        "The native libraries' folder is unknown: XDG_CACHE_HOME is not an absolute path, and the home directory " +
          s"(user.home: $home) is not one either. Set XDG_CACHE_HOME to a folder to keep them in.")
    }
    cache.resolve("natives")

  /**
   * Unpacks the libraries of `jar`, a platform jar, into `natives/<build>-<platform>/` and sets each property that `properties`
   * does not have (an empty one counts as unset) to that folder; answers the folder. None, with nothing changed or written and
   * `natives` never asked for, when every property is set, when there is no jar, or when its manifest names no build or no
   * platform.
   *
   * Holding the lock file against every other process and thread, it removes what an unpack cut short left, writes each library
   * whose file is missing or not its length in the jar to a temporary name and moves it to its name, touches the folder at `now`
   * and prunes the folders of other builds untouched for [[UNUSED_FOR]] before `now`. A library in place is never written again,
   * so a library another instance has loaded is never replaced under it.
   *
   * Throws `IllegalStateException`, naming the folder and the error, when the libraries cannot be put in place.
   */
  def prepare(jar: Option[Path], natives: => Path, properties: Properties, now: Instant): Option[Path] =
    val unset = Library.values.filter(library => Option(properties.getProperty(library.property)).forall(_.isEmpty))
    if unset.isEmpty then
      logger.debug("Each native library's folder is named by its property: nothing is unpacked")
      return None

    jar.flatMap(platformJarOf).flatMap {
      case (file, build, platform) =>
        Using.resource(file) {
          file =>
            val entries = Library.values.toSeq.map(library => library -> entriesOf(file, library)).toMap
            // Asked for once, and only here: the user cache may be unknown where nothing is unpacked
            val root = natives
            val folder = root.resolve(s"$build-$platform")
            try
              Files.createDirectories(root)
              LockFile.locked(root.resolve(LOCK_FILE), "unpacking the native libraries") {
                Files.createDirectories(folder)
                unpack(file, entries.values.flatten.toSeq, folder)
                Files.setLastModifiedTime(folder, FileTime.from(now))
                prune(root, folder, now)
              }
            catch
              case e: IOException =>
                throw IllegalStateException(
                  s"The native libraries could not be unpacked into $folder: ${e.getClass.getSimpleName}: ${e.getMessage}",
                  e)

            unset.filter(entries(_).nonEmpty).foreach {
              library =>
                properties.setProperty(library.property, folder.toString)
                if library == Library.SqliteJdbc && Option(properties.getProperty(SQLITE_JDBC_NAME)).forall(_.isEmpty) then
                  properties.setProperty(SQLITE_JDBC_NAME, fileName(entries(library).head))
            }
            logger.info(s"Native libraries: $folder")
            Some(folder)
        }
    }

  /**
   * Touches the folder this process's start unpacked the libraries into, if it did, so another start never prunes it while it is
   * in use. A failure is logged, never thrown: a scheduled task that throws is not run again.
   */
  def touchInUse(): Unit =
    inUse.foreach {
      folder =>
        try Files.setLastModifiedTime(folder, FileTime.from(Instant.now()))
        catch case NonFatal(e) => logger.warn(s"Could not touch the native libraries' folder $folder: $e")
    }

  /** The jar the application is in; None when it is loaded from a folder */
  private def ownJar: Option[Path] = Environment.codeLocation.filter(Files.isRegularFile(_))

  /** The jar opened, with its build and platform, when its manifest names both; else None, with the jar closed */
  private def platformJarOf(jar: Path): Option[(JarFile, String, String)] =
    val file = JarFile(jar.toFile)
    val attributes = Option(file.getManifest).map(_.getMainAttributes)
    (attributes.flatMap(a => Option(a.getValue(BUILD))), attributes.flatMap(a => Option(a.getValue(PLATFORM)))) match
      case (Some(build @ Safe()), Some(platform @ Safe())) => Some((file, build, platform))
      case (Some(build), Some(platform)) =>
        file.close()
        throw IllegalStateException(
          s"$jar names a build or a platform that is no folder name: $BUILD $build, $PLATFORM $platform")
      case _ =>
        file.close()
        logger.debug(s"$jar is no platform jar ($BUILD and $PLATFORM are not both in its manifest): nothing is unpacked")
        None

  /** The library's files in the jar, which holds its platform's alone, in name order */
  private def entriesOf(jar: JarFile, library: Library): Seq[JarEntry] =
    jar.entries().asScala.filter(entry => !entry.isDirectory && entry.getName.startsWith(library.folder)).toSeq.sortBy(_.getName)

  private def fileName(entry: JarEntry): String = entry.getName.split('/').last

  /** Writes the libraries not in place, each to a temporary name first, and logs what it wrote */
  private def unpack(jar: JarFile, entries: Seq[JarEntry], folder: Path): Unit =
    val names = entries.map(fileName)
    if names.distinct.size != names.size then
      throw IllegalStateException(
        s"Two native libraries of one name in ${jar.getName}: ${names.diff(names.distinct).mkString(", ")}")

    // The lock is held, so a temporary file is what an unpack that was killed left
    Using.resource(Files.newDirectoryStream(folder, s"*$PART"))(_.asScala.toList).foreach(Files.delete)

    val written = entries.filterNot(entry => inPlace(entry, folder.resolve(fileName(entry))))
    written.foreach {
      entry =>
        val name = fileName(entry)
        val part = Files.createTempFile(folder, s"$name.", PART)
        try
          Using.resource(jar.getInputStream(entry))(in => Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING))
          // On the disk before the move gives it its name, so a crash cannot leave a short library named whole
          Using.resource(FileChannel.open(part, StandardOpenOption.WRITE))(_.force(true))
          Files.move(part, folder.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        finally Files.deleteIfExists(part): Unit
    }
    if written.isEmpty then logger.debug(s"The native libraries are in place in $folder")
    else logger.info(s"Unpacked ${written.map(fileName).mkString(", ")} into $folder")

  /** A library's file is in place when it is a file of the entry's length: it was moved to its name whole */
  private def inPlace(entry: JarEntry, file: Path): Boolean =
    Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && entry.getSize >= 0 && Files.size(file) == entry.getSize

  /**
   * Removes the folders of other builds last touched more than [[UNUSED_FOR]] before `now`, with what they hold. A link is never
   * followed, and a folder that cannot be removed (a library a running instance on Windows holds) is logged and left.
   */
  private def prune(natives: Path, own: Path, now: Instant): Unit =
    val before = now.minus(UNUSED_FOR)
    val folders = Using
      .resource(Files.list(natives))(_.iterator().asScala.toList)
      .filter(path => path.getFileName != own.getFileName && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
    folders.filter(Files.getLastModifiedTime(_, LinkOption.NOFOLLOW_LINKS).toInstant.isBefore(before)).foreach {
      folder =>
        try
          FileUtils.deleteDirectory(folder.toFile)
          logger.info(s"Removed $folder, native libraries no start has used for ${UNUSED_FOR.toDays} days")
        catch case NonFatal(e) => logger.warn(s"Could not remove $folder, native libraries unused for long: $e")
    }
