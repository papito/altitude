/*
_
_._ _..._ .-',     _.._(`))
'-. `     '  /-._.-'    ',/
)         \            '.
/ _    _    |             \
|  a    a    /              |
\   .-.                     ;
'-('' ).-'       ,'       ;
'-;           |      .'
\           \    /
| 7  .__  _.-\   \
  | |  |  ``/  /`  /
/,_|  |   /,_/   /
/,_/      '`-'

SAFETY PIG HAS ARRIVED

THIS FILE is the first draft of user-defined metadata system. It may be overengineered and
unnecessarily complex, but since this is only implemented against tests, the only
way to flesh it out is to actually build the user-facing feature.

Regard this code as for reference use only in the meantime.

 */
package altitude.core.models

import altitude.core.dao.jdbc.BaseDao
import altitude.core.util.JsonCodec

object UserMetadata:
  given JsonCodec.ReadWriter[UserMetadata] = JsonCodec.readwriter[ujson.Value].bimap(
    (userMetadata: UserMetadata) => {
      val result = ujson.Obj()
      userMetadata.data.foreach { case (fieldId, values) =>
        result(fieldId) = ujson.Arr(values.toSeq.map(v => JsonCodec.writeJs(v))*)
      }
      result
    },
    (json: ujson.Value) =>
      UserMetadata(
        json.obj.keys.foldLeft(Map[String, Set[UserMetadataValue]]()) { (res, fieldId) =>
          val valuesJson = json(fieldId).arr
          res + (fieldId -> valuesJson.map(v => JsonCodec.read[UserMetadataValue](v)).toSet)
        }
      )
  )

  given Conversion[ujson.Value, UserMetadata] = json => JsonCodec.read[UserMetadata](json)

  def fromJson(json: ujson.Value): UserMetadata = JsonCodec.read[UserMetadata](json)

  /**
   * Adapter to easily set metadata from a set of plain strings. Note: dummy implicit is added to prevent compiler from
   * complaining about double definition due to type erasure
   */
  def apply(data: Map[String, Set[String]])(using d: DummyImplicit): UserMetadata =
    val convertedData: Map[String, Set[UserMetadataValue]] = data.foldLeft(Map[String, Set[UserMetadataValue]]()) {
      case (a, (fieldId, strValues)) =>
        a ++ Map[String, Set[UserMetadataValue]](fieldId -> strValues.map(value => UserMetadataValue(None, value)))
    }
    UserMetadata(convertedData)

  def apply(): UserMetadata = UserMetadata(Map[String, Set[UserMetadataValue]]())

  def withIds(metadata: UserMetadata): UserMetadata =
    val dataWithIds = metadata.data.map {
      case (fieldId, mdVal) =>
        val mdValsWithIds = mdVal.map {
          mdVal =>
            mdVal.id match
              case None => UserMetadataValue(id = Some(BaseDao.genId), value = mdVal.value)
              case Some(_) => mdVal
        }
        (fieldId, mdValsWithIds)
    }
    UserMetadata(dataWithIds)

case class UserMetadata(data: Map[String, Set[UserMetadataValue]]) extends BaseModel with NoId with NoDates:

  def get(key: String): Option[Set[UserMetadataValue]] = data.get(key)
  def apply(key: String): Set[UserMetadataValue] = data(key)
  def contains(key: String): Boolean = data.keys.toSeq.contains(key)
  def isEmpty: Boolean = data.isEmpty

  def toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
