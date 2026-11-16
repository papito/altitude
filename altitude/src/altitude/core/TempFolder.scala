package altitude.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.control.NonFatal

/**
 * The JVM's temp folder (`java.io.tmpdir`), where Undertow spools a multipart body and Tika and ImageIO spool what they read. A
 * run that is killed, that crashes or that exits on an `OutOfMemoryError` leaves its files there, and nothing removes them: in a
 * container, whose temp folder is a volume kept from one run to the next, they pile up on the host's disk without bound.
 *
 * So a start empties the folder, but only where it is told that the folder is this server's alone: the system's shared temp
 * folder, which is the JVM's wherever nothing says otherwise, holds other programs' files. The Docker image says so, and names a
 * folder of the server's own under `/tmp` (`-Djava.io.tmpdir=/tmp/altitude -Daltitude.tmpdir.own=true`), not `/tmp` itself, where
 * the JVM keeps files of its own (`hsperfdata_<user>`, the attach socket) whatever its temp folder is.
 */
object TempFolder:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The system property that says, as `true`, that the JVM's temp folder is this server's alone, to be emptied at a start */
  val OWN = "altitude.tmpdir.own"

  private val TMPDIR = "java.io.tmpdir"

  /** Empties the JVM's temp folder when the `altitude.tmpdir.own` property says it is the server's own; else nothing */
  def prepare(): Unit =
    val own = sys.props.get(OWN).exists(_.equalsIgnoreCase("true"))
    sys.props.get(TMPDIR).filter(_.nonEmpty).foreach(dir => prepare(Path.of(dir).toAbsolutePath, own))

  /**
   * With `own`, makes `dir` when it is not there, removes everything in it, and leaves it writable by any user, so a container
   * run later as another user (compose's `user:`) can use it and clear what this one left. The JVM does not make its temp folder
   * itself. Answers how many entries were removed. A symbolic link is removed, never followed; an entry that cannot be removed is
   * logged at WARN and left, since a start does not fail for a leftover. A file system's root is refused. Without `own`, nothing
   * is touched.
   */
  def prepare(dir: Path, own: Boolean): Int =
    if !own then 0
    else if dir.getParent == null then
      throw IllegalStateException(s"$OWN says the temp folder $dir is this server's own, to be emptied: a root never is")
    else
      Files.createDirectories(dir)
      val leftovers = Using.resource(Files.list(dir))(_.iterator().asScala.toList)
      val removed = leftovers.count {
        leftover =>
          // Deletes a link itself, and a folder with what it holds
          try
            FileUtils.forceDelete(leftover.toFile)
            true
          catch
            case NonFatal(e) =>
              logger.warn(s"Could not remove $leftover from the temp folder: $e")
              false
      }
      // Explicitly, whatever the process's umask
      try Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
      catch
        case _: UnsupportedOperationException => ()
        case NonFatal(e) => logger.warn(s"Could not make the temp folder $dir writable by any user: $e")
      if removed > 0 then logger.info(s"Emptied the temp folder $dir of $removed leftovers of an earlier run")
      else logger.debug(s"The temp folder $dir is empty")
      removed
