package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Location

/**
 * Parents, Locations and the memberships. The `location_asset` table has no model of its own and is written only through this
 * DAO. Rename and move go through `updateById`.
 */
trait LocationDao extends BaseDao[Location]:
  /**
   * Every row in the context repository, with its parent's name and its asset count, in path order: parents and top-level
   * Locations interleaved by name, each parent directly followed by its Locations by name
   */
  def getAll: List[Location]

  /** Points the Location at the given assets; returns how many were new. Only existing, non-recycled assets are added. */
  def addAssets(locationId: String, assetIds: Set[String]): Int

  def removeAssets(locationId: String, assetIds: Set[String]): Int

  def removeAssetsFromAllLocations(assetIds: Set[String]): Int

  def getAssetIds(locationId: String): Set[String]

  /** Makes the parent's Locations top-level; returns how many moved */
  def moveChildrenToRoot(parentId: String): Int
