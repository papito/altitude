package software.altitude.core.dao

import play.api.libs.json.JsObject
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.{Asset, Face, Person}

trait FaceDao extends BaseDao {
  def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject
  def getAllForCache: List[Face]
}
