package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

trait FaceDao extends BaseDao:
  def add(jsonIn: ujson.Obj, asset: Asset, person: Person): ujson.Obj
  def getAssetFaces(assetId: String): List[Face]
  def searchClosestFaceMatches(features: Array[Float]): List[Face]