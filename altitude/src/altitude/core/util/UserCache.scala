package altitude.core.util

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The per-user cache folder Altitude keeps what it puts in place once for every instance of the user: the model files' default
 * folder (`models/`) and the native jar's unpacked libraries (`natives/`).
 */
object UserCache:

  /**
   * `$XDG_CACHE_HOME/altitude`, else `~/.cache/altitude`; the XDG spec says an empty or relative `XDG_CACHE_HOME` is ignored.
   * None when that leaves the home folder and it is not an absolute path (`user.home` is `?` for a user with no passwd entry, as
   * in some containers): a caller says what to set instead, rather than resolve under whatever the working directory is.
   */
  def folder(env: String => Option[String], home: String): Option[Path] =
    env("XDG_CACHE_HOME")
      .flatMap(absolute)
      .orElse(absolute(home).map(_.resolve(".cache")))
      .map(_.resolve("altitude"))

  /** `path` as a path when it is an absolute one; None for null and for what is no path here, as `?` is none on Windows */
  def absolute(path: String): Option[Path] =
    Option(path)
      .flatMap(
        value =>
          try Some(Path.of(value))
          catch case _: InvalidPathException => None)
      .filter(_.isAbsolute)
