package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Album

/** Albums and their memberships. The `album_asset` table has no model of its own and is written only through this DAO. */
trait AlbumDao extends BaseDao[Album]:
  /** Every album in the context repository, by name, with its asset count */
  def getAll: List[Album]

  /** Points the album at the given assets; returns how many were new. Only existing, non-recycled assets are added. */
  def addAssets(albumId: String, assetIds: Set[String]): Int

  def removeAssets(albumId: String, assetIds: Set[String]): Int

  def removeAssetsFromAllAlbums(assetIds: Set[String]): Int

  def getAssetIds(albumId: String): Set[String]
