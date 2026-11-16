package altitude.core.util

import java.util.Locale

/**
 * The files an import never takes, by name: dot files, the operating systems' and the NAS's own files and directories, and
 * downloads or copies still in progress. A file below an ignored directory is ignored with it. The server and the Import page
 * apply this one list ([[toJson]]). A zero-byte file is ignored too, but by its size, which whoever holds the file checks.
 */
object IgnoredFiles:
  // In lower case, as a name is compared: the file systems these come from do not tell case apart
  private val NAMES: Set[String] = Set("thumbs.db", "desktop.ini", "@eadir", "#recycle", "$recycle.bin")
  private val PREFIXES: List[String] = List(".")
  private val SUFFIXES: List[String] = List(".part", ".tmp", ".crdownload")

  /** A file's or a directory's name */
  def ignores(name: String): Boolean =
    val folded = name.toLowerCase(Locale.ROOT)
    NAMES.contains(folded) || PREFIXES.exists(folded.startsWith) || SUFFIXES.exists(folded.endsWith)

  val toJson: ujson.Obj = ujson.Obj("names" -> NAMES.toSeq.sorted, "prefixes" -> PREFIXES, "suffixes" -> SUFFIXES)
