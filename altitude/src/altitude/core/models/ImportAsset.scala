package altitude.core.models

import java.nio.file.Files
import java.nio.file.Path

/** A file to import, wherever it lives: the pipeline works on a staged copy of it, never on this file */
class ImportAsset(val fileName: String, val path: Path, val metadata: UserMetadata) extends BaseModel with NoId with NoDates:

  def bytes: Array[Byte] = Files.readAllBytes(path)

  lazy val toJson: ujson.Obj = ujson.Obj("fileName" -> ujson.Str(fileName))
