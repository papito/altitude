package software.altitude.core.models

import play.api.libs.json._
import software.altitude.core.dao.jdbc.BaseDao

import scala.language.implicitConversions

object UserMetadata {
  implicit def fromJson(json: JsObject): UserMetadata = {
    val data = json.keys.foldLeft(Map[String, Set[UserMetadataValue]]()) { (res, fieldId) =>
      val valuesJson = (json \ fieldId).as[List[JsValue]]
      valuesJson.map(UserMetadataValue.fromJson)
      res + (fieldId -> valuesJson.map(UserMetadataValue.fromJson).toSet)
    }

    new UserMetadata(data)
  }

  /**
   * Adapter to easily set metadata from a set of plain strings.
   * Note: dummy implicit is added to prevent compiler from complaining about double definition
   * due to type erasure
   * @param data { fieldId -> Set of string }
   */
  def apply(data: Map[String, Set[String]])(implicit d: DummyImplicit): UserMetadata = {
    val convertedData: Map[String, Set[UserMetadataValue]] = data.foldLeft(Map[String, Set[UserMetadataValue]]()) {
      case (a, (fieldId, strValues)) =>
        a ++ Map[String, Set[UserMetadataValue]](fieldId -> strValues.map(UserMetadataValue.apply))
    }

    UserMetadata(convertedData)
  }

  def apply(): UserMetadata = {
    UserMetadata(Map[String, Set[UserMetadataValue]]())
  }

  def withIds(metadata: UserMetadata): UserMetadata = {
    val dataWithIds = metadata.data.map{ case (fieldId, mdVal) =>
      val mdValsWithIds = mdVal.map{ mdVal =>
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

case class UserMetadata(data: Map[String, Set[UserMetadataValue]])
  extends BaseModel with NoId {

  def get(key: String): Option[Set[UserMetadataValue]] = data.get(key)
  def apply(key: String): Set[UserMetadataValue] = data(key)
  def contains(key: String): Boolean = data.keys.toSeq.contains(key)
  def isEmpty: Boolean = data.isEmpty

  override val toJson: JsObject = data.foldLeft(Json.obj()) { (res, m) =>
    val fieldId = m._1

    val valuesJsArray: JsArray = JsArray(m._2.toSeq.map(_.toJson))

    // append to the resulting JSON object
    res ++ Json.obj(fieldId -> valuesJsArray)
  }
}
