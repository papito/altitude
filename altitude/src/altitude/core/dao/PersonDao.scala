package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Person

trait PersonDao extends BaseDao:
  def getAll: Map[String, Person]
  def getAllNotDiscarded: Map[String, Person]
  def getAllAboveThreshold: List[Person]
  def getAllBelowThreshold: List[Person]
  def getAllHidden: List[Person]
  def recycleFacesForAssets(assetIds: Set[String]): Unit
  def restoreFacesForAssets(assetIds: Set[String]): Unit
