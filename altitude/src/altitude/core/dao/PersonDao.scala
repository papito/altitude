package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Person

trait PersonDao extends BaseDao[Person]:
  def getAll: Map[String, Person]
  def getAllNotDiscarded: Map[String, Person]
  def getAllAboveThreshold: List[Person]
  def getAllBelowThreshold: List[Person]
  def getAllHidden: List[Person]
  def recycleFacesForAssets(assetIds: Set[String]): Unit
  def restoreFacesForAssets(assetIds: Set[String]): Unit

  /** Locks the people of an asset's Faces for the caller's transaction */
  def lockAssetPeople(assetId: String): Unit

  /** Deletes those of the people who have no Face left, and gives back their IDs */
  def deleteFaceless(personIds: Set[String]): Set[String]
