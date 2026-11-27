package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Person

trait PersonDao extends BaseDao[Person]:
  def getAll: Map[String, Person]

  /** The people in any of the buckets (`Const.PeopleBucket`), named first, then by sort name */
  def getInBuckets(buckets: Set[String]): List[Person]

  /**
   * Per person, the triaged assets outside the trash they have a Face on: the part of `Person.numOfFaces` a search leaves out
   * while triaged items are hidden. An asset still being imported counts, as its Faces are in `numOfFaces`.
   */
  def getTriagedAssetCounts: Map[String, Int]

  /** The live person (neither merged away nor a bad match, hidden or not) whose sort name is the name key */
  def getLiveByNameKey(nameKey: String): Option[Person]

  /**
   * Makes the transactions that may create a person of the name key take turns, so the second finds the person the first created
   * instead of failing on the unique index (`BaseDao.advisoryLock`)
   */
  def lockName(nameKey: String): Unit

  /** The people a Face can be given to: every live one, hidden or not, named first, then by sort name */
  def getAssignable: List[Person]

  def recycleFacesForAssets(assetIds: Set[String]): Unit
  def restoreFacesForAssets(assetIds: Set[String]): Unit

  /** Locks the people with a Face on any of the assets, in ID order as `lockAndGetLive` locks, and returns their IDs */
  def lockAssetPeople(assetIds: Set[String]): Set[String]

  /** The people with a Face on any of the assets, read without a lock */
  def getAssetPeople(assetIds: Set[String]): Set[String]

  /** Locks the people in ID order, so transactions locking the same people cannot deadlock; returns the live ones' IDs */
  def lockAndGetLive(personIds: Set[String]): Set[String]

  /** Deletes those with no Face left, or with `keepNamed` only the unnamed among them; returns their IDs */
  def deleteFaceless(personIds: Set[String], keepNamed: Boolean): Set[String]
