package altitude.core.util

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
      .map(Path.of(_))
      .filter(_.isAbsolute)
      .orElse(absolute(home).map(_.resolve(".cache")))
      .map(_.resolve("altitude"))

  /** `home` as a path when it is an absolute one */
  def absolute(home: String): Option[Path] = Option(home).map(Path.of(_)).filter(_.isAbsolute)
