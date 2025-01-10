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
package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions

import software.altitude.core.dao.jdbc.BaseDao

object UserMetadata {
  implicit val reads: Reads[UserMetadata] = (json: JsValue) => {
    val data = json.as[JsObject].keys.foldLeft(Map[String, Set[UserMetadataValue]]()) {
      (res, fieldId) =>
        val valuesJson = (json \ fieldId).as[List[JsValue]]
        res + (fieldId -> valuesJson.map(_.as[UserMetadataValue]).toSet)
    }
    JsSuccess(UserMetadata(data))
  }

  implicit val writes: OWrites[UserMetadata] = (userMetadata: UserMetadata) => {
    userMetadata.data.foldLeft(Json.obj()) {
      (res, m) =>
        val fieldId = m._1
        val valuesJsArray: JsArray = JsArray(m._2.toSeq.map(Json.toJson(_)))
        res ++ Json.obj(fieldId -> valuesJsArray)
    }
  }

  implicit def fromJson(json: JsValue): UserMetadata = Json.fromJson[UserMetadata](json).get

  /**
   * Adapter to easily set metadata from a set of plain strings. Note: dummy implicit is added to prevent compiler from
   * complaining about double definition due to type erasure
   */
  def apply(data: Map[String, Set[String]])(implicit d: DummyImplicit): UserMetadata = {
    val convertedData: Map[String, Set[UserMetadataValue]] = data.foldLeft(Map[String, Set[UserMetadataValue]]()) {
      case (a, (fieldId, strValues)) =>
        a ++ Map[String, Set[UserMetadataValue]](fieldId -> strValues.map(value => UserMetadataValue(None, value)))
    }

    UserMetadata(convertedData)
  }

  def apply(): UserMetadata = {
    UserMetadata(Map[String, Set[UserMetadataValue]]())
  }

  def withIds(metadata: UserMetadata): UserMetadata = {
    val dataWithIds = metadata.data.map {
      case (fieldId, mdVal) =>
        val mdValsWithIds = mdVal.map {
          mdVal =>
            mdVal.id match {
              case None => UserMetadataValue(id = Some(BaseDao.genId), value = mdVal.value)
              case Some(_) => mdVal
            }
        }
        (fieldId, mdValsWithIds)
    }

    UserMetadata(dataWithIds)
  }
}

case class UserMetadata(data: Map[String, Set[UserMetadataValue]]) extends BaseModel with NoId with NoDates {

  def get(key: String): Option[Set[UserMetadataValue]] = data.get(key)
  def apply(key: String): Set[UserMetadataValue] = data(key)
  def contains(key: String): Boolean = data.keys.toSeq.contains(key)
  def isEmpty: Boolean = data.isEmpty

  override def toJson: JsObject = Json.toJson(this).as[JsObject]
}
