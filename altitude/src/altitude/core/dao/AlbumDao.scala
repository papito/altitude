package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Album

/** Albums and their memberships. `album_asset` has no model of its own and is written only through this DAO. */
trait AlbumDao extends BaseDao[Album]:
  /** By name, with asset counts */
  def getAll: List[Album]

  /**
   * Returns how many were new. Only existing, non-recycled assets are added, and a member already there is skipped. The caller
   * share-locks the assets first (`AssetDao.shareLockLive`), so none recycled meanwhile is added.
   */
  def addAssets(albumId: String, assetIds: Set[String]): Int

  def removeAssets(albumId: String, assetIds: Set[String]): Int

  def removeAssetsFromAllAlbums(assetIds: Set[String]): Int

  def getAssetIds(albumId: String): Set[String]

  /** Those of the assets that some album holds */
  def getAssetsInAnyAlbum(assetIds: Set[String]): Set[String]

  /** By name ignoring case, without asset counts */
  def getAlbumsOfAsset(assetId: String): List[Album]
