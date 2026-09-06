package altitude.core.dao.jdbc

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Album

abstract class AlbumDao(override val config: Config) extends BaseDao[Album] with altitude.core.dao.AlbumDao:
  final override val tableName = "album"
  private val membershipTable = "album_asset"

  override protected def makeModel(rec: Map[String, AnyRef]): Album =
    Album(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Album.NAME).asInstanceOf[String],
      numOfAssets = rec.get(FieldConst.Album.NUM_OF_ASSETS).map(getIntField).getOrElse(0)
    )

  override def add(album: Album): Album =
    val id = album.id.getOrElse(BaseDao.genId)

    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Album.NAME}, ${FieldConst.Album.NAME_LC})
             VALUES (?, ?, ?, ?)
    """

    addRecord(sql, List(id, RequestContext.getRepository.persistedId, album.name, album.nameLowercase))
    album.copy(id = Some(id))

  override def getAll: List[Album] =
    val sql = s"""
      SELECT a.*, (
        SELECT COUNT(*)
          FROM $membershipTable aa
         WHERE aa.${FieldConst.Album.ALBUM_ID} = a.${FieldConst.ID}
      ) AS ${FieldConst.Album.NUM_OF_ASSETS}
        FROM $tableName a
       WHERE a.${FieldConst.REPO_ID} = ?
       ORDER BY a.${FieldConst.Album.NAME_LC}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId)).map(makeModel)

  override def addAssets(albumId: String, assetIds: Set[String]): Int =
    val placeholders = List.fill(assetIds.size)("?").mkString(", ")

    // Rows are selected from the asset table so that unknown, foreign, and recycled ids are dropped, and assets already in
    // the album are skipped rather than tripping the unique index
    val sql = s"""
      INSERT INTO $membershipTable (${FieldConst.REPO_ID}, ${FieldConst.Album.ALBUM_ID}, ${FieldConst.Album.ASSET_ID})
      SELECT asset.${FieldConst.REPO_ID}, ?, asset.${FieldConst.ID}
        FROM asset
       WHERE asset.${FieldConst.REPO_ID} = ?
         AND asset.${FieldConst.ID} IN ($placeholders)
         AND asset.${FieldConst.Asset.IS_RECYCLED} = ?
         AND NOT EXISTS (
           SELECT 1
             FROM $membershipTable aa
            WHERE aa.${FieldConst.Album.ALBUM_ID} = ?
              AND aa.${FieldConst.Album.ASSET_ID} = asset.${FieldConst.ID})
    """

    val values: List[Any] =
      List(albumId, RequestContext.getRepository.persistedId) ++ assetIds.toList ++ List(nativeBool(false), albumId)

    updateByBySql(sql, values)

  override def removeAssets(albumId: String, assetIds: Set[String]): Int =
    val placeholders = List.fill(assetIds.size)("?").mkString(", ")

    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.Album.ALBUM_ID} = ?
         AND ${FieldConst.Album.ASSET_ID} IN ($placeholders)
    """

    updateByBySql(sql, albumId :: assetIds.toList)

  override def removeAssetsFromAllAlbums(assetIds: Set[String]): Int =
    val placeholders = List.fill(assetIds.size)("?").mkString(", ")

    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Album.ASSET_ID} IN ($placeholders)
    """

    updateByBySql(sql, RequestContext.getRepository.persistedId :: assetIds.toList)

  override def getAssetIds(albumId: String): Set[String] =
    val sql = s"""
      SELECT ${FieldConst.Album.ASSET_ID}
        FROM $membershipTable
       WHERE ${FieldConst.Album.ALBUM_ID} = ?
    """

    manyBySqlQuery(sql, List(albumId)).map(_(FieldConst.Album.ASSET_ID).asInstanceOf[String]).toSet
