package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.AlbumRow
import altitude.core.models.Album

abstract class AlbumDao(override val config: Config) extends BaseDao[Album] with altitude.core.dao.AlbumDao:
  final override val tableName = "album"
  private val membershipTable = "album_asset"

  final override type Row[T[_]] = AlbumRow[T]
  final override protected def table: Table[Row] = AlbumRow

  // `numOfAssets` is counted only by `getAll`'s hand-written query
  override protected def toModel(row: AlbumRow[Sc]): Album =
    Album(
      id = Option(row.id),
      name = row.name,
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime))

  override protected def makeModel(rec: Map[String, AnyRef]): Album =
    Album(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Album.NAME).asInstanceOf[String],
      numOfAssets = rec.get(FieldConst.Album.NUM_OF_ASSETS).map(getIntField).getOrElse(0),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def add(album: Album): Album =
    val id = album.id.getOrElse(BaseDao.genId)

    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Album.NAME}, ${FieldConst.Album.NAME_KEY})
             VALUES (?, ?, ?, ?)
    """

    addRecord(sql, List(id, RequestContext.getRepository.persistedId, album.name, album.nameKey))
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
       ORDER BY a.${FieldConst.Album.NAME_KEY}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId)).map(makeModel)

  override def addAssets(albumId: String, assetIds: Set[String]): Int =
    // Selected from the asset table to drop unknown, foreign and recycled IDs, and to skip members rather than trip the unique
    // index
    val sql = s"""
      INSERT INTO $membershipTable (${FieldConst.REPO_ID}, ${FieldConst.Album.ALBUM_ID}, ${FieldConst.Album.ASSET_ID})
      SELECT asset.${FieldConst.REPO_ID}, ?, asset.${FieldConst.ID}
        FROM asset
       WHERE asset.${FieldConst.REPO_ID} = ?
         AND asset.${FieldConst.ID} $inIdSet
         AND asset.${FieldConst.Asset.IS_RECYCLED} = ?
         AND NOT EXISTS (
           SELECT 1
             FROM $membershipTable aa
            WHERE aa.${FieldConst.Album.ALBUM_ID} = ?
              AND aa.${FieldConst.Album.ASSET_ID} = asset.${FieldConst.ID})
    """

    val values: List[Any] =
      List(albumId, RequestContext.getRepository.persistedId, idSet(assetIds), nativeBool(false), albumId)

    updateByBySql(sql, values)

  override def removeAssets(albumId: String, assetIds: Set[String]): Int =
    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Album.ALBUM_ID} = ?
         AND ${FieldConst.Album.ASSET_ID} $inIdSet
    """

    updateByBySql(sql, List(RequestContext.getRepository.persistedId, albumId, idSet(assetIds)))

  override def removeAssetsFromAllAlbums(assetIds: Set[String]): Int =
    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Album.ASSET_ID} $inIdSet
    """

    updateByBySql(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))

  override def getAssetIds(albumId: String): Set[String] =
    val sql = s"""
      SELECT ${FieldConst.Album.ASSET_ID}
        FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Album.ALBUM_ID} = ?
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, albumId))
      .map(_(FieldConst.Album.ASSET_ID).asInstanceOf[String])
      .toSet

  override def getAlbumsOfAsset(assetId: String): List[Album] =
    val sql = s"""
      SELECT a.*
        FROM $tableName a
             JOIN $membershipTable aa ON aa.${FieldConst.Album.ALBUM_ID} = a.${FieldConst.ID}
       WHERE aa.${FieldConst.REPO_ID} = ?
         AND aa.${FieldConst.Album.ASSET_ID} = ?
       ORDER BY a.${FieldConst.Album.NAME_KEY}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId)).map(makeModel)

  override def getAssetsInAnyAlbum(assetIds: Set[String]): Set[String] =
    if assetIds.isEmpty then return Set.empty[String]

    val sql = s"""
      SELECT DISTINCT ${FieldConst.Album.ASSET_ID}
        FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Album.ASSET_ID} $inIdSet
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))
      .map(_(FieldConst.Album.ASSET_ID).asInstanceOf[String])
      .toSet
