package altitude.core.models

import java.nio.file.Path

/**
 * Where a file in the import pipeline came from: the Import file to settle, the folders under the Import's destination it lands
 * in (none for a file at the top of the delivery), and for a Watch folder's file, the file its staged copy was made from
 */
case class ImportOrigin(importFileId: String, folderPath: Seq[String] = Seq.empty, source: Option[Path] = None)
