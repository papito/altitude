package altitude.core.models

import altitude.core.json.UpickleConverters._
import java.time.LocalDateTime
import ujson.Value
import upickle.default.ReadWriter
import upickle.default.ReadWriter.join
import upickle.default.write
import upickle.default.writeJs

case class Repository(
    id: Option[String] = None,
    name: String,
    ownerAccountId: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
  derives ReadWriter:
  def toJsonString: String = write(this)
  def toJson: Value = writeJs(this)

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
