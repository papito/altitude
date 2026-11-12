package altitude.core.models

import java.nio.file.Path

/**
 * Where a file in the import pipeline came from: the Import file to settle with what becomes of it, the names of the folders
 * under the Import's destination that it lands in (none for a file at the top of what was delivered), and, for a Watch folder's
 * file, the file in the Watch folder that its staged copy was made from
 */
case class ImportOrigin(importFileId: String, folderPath: Seq[String] = Seq.empty, source: Option[Path] = None)
