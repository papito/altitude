package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

trait FaceDao extends BaseDao[Face]:
  def add(face: Face, asset: Asset, person: Person): Face
  def getAssetFaces(assetId: String): List[Face]

  /** A person's faces by detection score, best first and then by ID, no more than `limit` of them */
  def getTopFaces(personId: String, limit: Int): List[Face]
  def searchClosestFaceMatches(features: Array[Float]): List[Face]
