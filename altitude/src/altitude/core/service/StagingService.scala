package altitude.core.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.Const

/**
 * The staging directory, `data/staging`: where a file to import lands before the pipeline runs, and where it stays until the file
 * store renames it into place or the pipeline drops the asset. It is on the same filesystem as the store, so that rename is
 * atomic. A staged file is named by a fresh UUID, never by its upload name.
 */
class StagingService(app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  val dir: Path = Path.of(app.dataPath, Const.DataStore.STAGING)

  /** Moves a file into staging; the source is gone after */
  def stageMove(source: Path): Path =
    Files.move(source, freshPath(), StandardCopyOption.REPLACE_EXISTING)

  /** Copies a file into staging, leaving the source alone */
  def stageCopy(source: Path): Path =
    Files.copy(source, freshPath(), StandardCopyOption.REPLACE_EXISTING)

  def stage(bytes: Array[Byte]): Path =
    Files.write(freshPath(), bytes)

  /** Deletes a staged file; a path outside staging, such as a file already renamed into the store, is left alone */
  def discard(path: Path): Unit =
    if path.toAbsolutePath.startsWith(dir.toAbsolutePath) then
      logger.debug(s"Discarding staged file $path")
      Files.deleteIfExists(path)

  /** Empties staging: whatever is there was left by a run that did not finish */
  def clear(): Unit =
    FileUtils.forceMkdir(dir.toFile)
    FileUtils.cleanDirectory(dir.toFile)

  private def freshPath(): Path =
    Files.createDirectories(dir)
    dir.resolve(UUID.randomUUID().toString)
