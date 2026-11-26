package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Location

/**
 * Categories, Locations and memberships. `location_asset` has no model and is written only here. Rename and move go through
 * `updateById`.
 */
trait LocationDao extends BaseDao[Location]:
  /**
   * With category names and asset counts, in path order: categories and top-level Locations by name, each category followed by
   * its Locations
   */
  def getAll: List[Location]

  /**
   * Returns how many were new. Only existing, non-recycled assets are added, and a member already there is skipped. The caller
   * share-locks the assets first (`AssetDao.shareLockLive`), so none recycled meanwhile is added.
   */
  def addAssets(locationId: String, assetIds: Set[String]): Int

  def removeAssets(locationId: String, assetIds: Set[String]): Int

  def removeAssetsFromAllLocations(assetIds: Set[String]): Int

  def getAssetIds(locationId: String): Set[String]

  /** Those of the assets that some Location holds */
  def getAssetsInAnyLocation(assetIds: Set[String]): Set[String]

  /** In path order, with category names and without asset counts */
  def getLocationsOfAsset(assetId: String): List[Location]

  /** Returns how many moved */
  def moveChildrenToRoot(categoryId: String): Int
