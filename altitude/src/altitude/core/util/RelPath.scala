package altitude.core.util

import altitude.core.Const
import altitude.core.ValidationException

/**
 * A file's path as an upload delivers it, relative to what was chosen: the names of the folders it is in, then its own, separated
 * by "/". A file chosen on its own is its name alone.
 */
object RelPath:
  val SEPARATOR = "/"

  /**
   * The path's names, the file's last. A path with an empty name (an empty path, a leading, trailing or doubled separator), with
   * a ".." or with a name imports ignore (`IgnoredFiles`) is a `ValidationException` that says which.
   */
  def segments(relPath: String): List[String] =
    // A negative limit keeps the empty names a trailing separator leaves
    val names = relPath.split(SEPARATOR, -1).toList

    if names.exists(_.isEmpty) then throw ValidationException(Const.Msg.Err.PATH_EMPTY_NAME)
    if names.contains("..") then throw ValidationException(Const.Msg.Err.PATH_PARENT)
    names.find(IgnoredFiles.ignores).foreach(name => throw ValidationException(Const.Msg.Err.PATH_IGNORED.format(name)))
    names
