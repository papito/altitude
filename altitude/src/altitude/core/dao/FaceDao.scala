package altitude.core.dao

import play.api.libs.json.JsObject

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

trait FaceDao extends BaseDao:
  def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject
  def getAllForCache: List[Face]
  def getAllForTraining: List[Face]
  def getAssetFaces(assetId: String): List[Face]
