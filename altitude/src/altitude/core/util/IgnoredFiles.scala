package altitude.core.util

import java.util.Locale

/**
 * The files an import never takes, by name: a name starting with a dot, the operating systems' and the NAS's own files and
 * directories, and the files a download or a copy still in progress leaves. A name is a file's or a directory's, so a file below
 * an ignored directory is ignored with it. One list: the upload routes refuse a path that holds an ignored name, and the Import
 * page is served the list ([[toJson]]) and leaves such files out before it sends anything. A zero-byte file is ignored too, which
 * its name does not say: whoever holds the file checks its size.
 */
object IgnoredFiles:
  // In lower case, as a name is compared: the file systems these come from do not tell case apart
  private val NAMES: Set[String] = Set("thumbs.db", "desktop.ini", "@eadir", "#recycle", "$recycle.bin")
  private val PREFIXES: List[String] = List(".")
  private val SUFFIXES: List[String] = List(".part", ".tmp", ".crdownload")

  /** Whether a file or a directory of the name is ignored */
  def ignores(name: String): Boolean =
    val folded = name.toLowerCase(Locale.ROOT)
    NAMES.contains(folded) || PREFIXES.exists(folded.startsWith) || SUFFIXES.exists(folded.endsWith)

  /** The list as the Import page reads it: the names, the prefixes and the suffixes, each in lower case */
  val toJson: ujson.Obj = ujson.Obj("names" -> NAMES.toSeq.sorted, "prefixes" -> PREFIXES, "suffixes" -> SUFFIXES)
