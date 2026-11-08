package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Person

trait PersonDao extends BaseDao[Person]:
  def getAll: Map[String, Person]

  /**
   * The people in any of the given People buckets (`Const.PeopleBucket`), named first and then by sort name. An empty set is an
   * empty list; a name that is no bucket is an `IllegalArgumentException`.
   */
  def getInBuckets(buckets: Set[String]): List[Person]

  /** The live person of the repository (neither merged away nor a bad match, hidden or not) whose sort name is the name key */
  def getLiveByNameKey(nameKey: String): Option[Person]

  def recycleFacesForAssets(assetIds: Set[String]): Unit
  def restoreFacesForAssets(assetIds: Set[String]): Unit

  /** Locks the people of an asset's Faces for the caller's transaction */
  def lockAssetPeople(assetId: String): Unit

  /**
   * Locks the people for the caller's transaction, in ID order so that transactions locking the same people cannot deadlock, and
   * gives back the IDs of those still live (neither merged away nor a bad match)
   */
  def lockAndGetLive(personIds: Set[String]): Set[String]

  /** Deletes those of the people who have no Face left, and gives back their IDs */
  def deleteFaceless(personIds: Set[String]): Set[String]
