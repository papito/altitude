package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Repository:
  given JsonCodec.ReadWriter[Repository] = JsonCodec.macroRW
  given Conversion[ujson.Value, Repository] = json => JsonCodec.read[Repository](json)

case class Repository(
    id: Option[String] = None,
    name: String,
    ownerAccountId: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
