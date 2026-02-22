package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import play.api.libs.json.JsObject

trait FaceDao extends BaseDao:
  def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject
  def getAssetFaces(assetId: String): List[Face]
  def searchClosestFaceMatches(features: Array[Float]): Unit