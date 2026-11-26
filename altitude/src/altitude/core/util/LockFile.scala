package altitude.core.util

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A lock file held against every other process and thread, for the files a start puts in place once for every instance of the
 * user: the model files and the native libraries.
 */
object LockFile:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * One lock per lock file for the whole JVM. A file lock is held by the process, not the thread: a second thread locking the
   * same file would get an `OverlappingFileLockException` rather than wait, so the threads of one JVM queue here first.
   */
  private val processLocks = ConcurrentHashMap[Path, ReentrantLock]()

  /**
   * Runs `body` holding the lock file, which is created when it is not there; its folder must exist. A wait for either lock is
   * logged, since what the other holder does can take minutes: `doing` says what that is ("putting the model files in place").
   */
  def locked[A](lockFile: Path, doing: String)(body: => A): A =
    // The same file reached by two spellings is one lock
    val key = lockFile.getParent.toRealPath().resolve(lockFile.getFileName)
    val processLock = processLocks.computeIfAbsent(key, _ => ReentrantLock())
    if !processLock.tryLock() then
      logger.info(s"Another thread is $doing: waiting for it to finish ($key)")
      processLock.lock()
    try
      val channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val fileLock = Option(channel.tryLock()).getOrElse {
          logger.info(s"Another process is $doing: waiting for it to finish ($key)")
          channel.lock()
        }
        try body
        finally fileLock.release()
      finally channel.close()
    finally processLock.unlock()
