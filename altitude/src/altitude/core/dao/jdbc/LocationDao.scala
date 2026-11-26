package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.LocationRow
import altitude.core.models.Location
import altitude.core.models.LocationKind
import altitude.core.util.Query

abstract class LocationDao(override val config: Config) extends BaseDao[Location] with altitude.core.dao.LocationDao:
  final override val tableName = "location"
  private val membershipTable = "location_asset"

  final override type Row[T[_]] = LocationRow[T]
  final override protected def table: Table[Row] = LocationRow

  // `numOfAssets` and `categoryName` come only from `getAll`'s hand-written query; the typed paths never select them
  override protected def toModel(row: LocationRow[Sc]): Location =
    Location(
      id = Option(row.id),
      name = row.name,
      kind = kindOf(row.kind),
      categoryId = row.categoryId,
      latitude = row.latitude,
      longitude = row.longitude
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Location =
    Location(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Location.NAME).asInstanceOf[String],
      kind = kindOf(rec(FieldConst.Location.KIND).asInstanceOf[String]),
      categoryId = Option(rec(FieldConst.Location.CATEGORY_ID)).map(_.asInstanceOf[String]),
      latitude = getDoubleField(rec(FieldConst.Location.LATITUDE)),
      longitude = getDoubleField(rec(FieldConst.Location.LONGITUDE)),
      numOfAssets = rec.get(FieldConst.Location.NUM_OF_ASSETS).map(getIntField).getOrElse(0),
      categoryName = rec.get(FieldConst.Location.CATEGORY_NAME).flatMap(Option(_)).map(_.asInstanceOf[String])
    )

  private def kindOf(dbValue: String): LocationKind =
    LocationKind.fromDbValue(dbValue).getOrElse(throw IllegalStateException(s"Unknown location kind stored: $dbValue"))

  override def add(location: Location): Location =
    val id = location.id.getOrElse(BaseDao.genId)

    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Location.CATEGORY_ID},
                                ${FieldConst.Location.KIND}, ${FieldConst.Location.NAME}, ${FieldConst.Location.NAME_KEY},
                                ${FieldConst.Location.LATITUDE}, ${FieldConst.Location.LONGITUDE})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

    val values: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      location.categoryId.orNull,
      location.kind.dbValue,
      location.name,
      location.nameKey,
      location.latitude.orNull,
      location.longitude.orNull
    )

    addRecord(sql, values)
    location.copy(id = Some(id))

  override def getAll: List[Location] =
    // Path order: Locations sort under their category's name, after the category itself; name order alone would put "Alba"
    // before "Italy"
    val sql = s"""
      SELECT l.*, p.${FieldConst.Location.NAME} AS ${FieldConst.Location.CATEGORY_NAME}, (
        SELECT COUNT(*)
          FROM $membershipTable la
         WHERE la.${FieldConst.Location.LOCATION_ID} = l.${FieldConst.ID}
      ) AS ${FieldConst.Location.NUM_OF_ASSETS}
        FROM $tableName l
             LEFT JOIN $tableName p ON p.${FieldConst.ID} = l.${FieldConst.Location.CATEGORY_ID}
       WHERE l.${FieldConst.REPO_ID} = ?
       ORDER BY COALESCE(p.${FieldConst.Location.NAME_KEY}, l.${FieldConst.Location.NAME_KEY}),
                CASE WHEN l.${FieldConst.Location.CATEGORY_ID} IS NULL THEN 0 ELSE 1 END,
                l.${FieldConst.Location.NAME_KEY}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId)).map(makeModel)

  override def addAssets(locationId: String, assetIds: Set[String]): Int =
    // Selected from `asset` to drop unknown, foreign and recycled IDs. A member is skipped by the unique index rather than by a
    // test of the table, which cannot see a concurrent add's uncommitted row: that add's row is waited for, then skipped. The
    // rows are written in ID order, so two adds of the same assets wait on each other's rows in one order whatever each reads
    // them by. The count is of the rows that were new. The `WHERE` stays ahead of the `ORDER BY`: SQLite reads an upsert after
    // a `SELECT` with none as a join's `ON`.
    val sql = s"""
      INSERT INTO $membershipTable (${FieldConst.REPO_ID}, ${FieldConst.Location.LOCATION_ID}, ${FieldConst.Location.ASSET_ID})
      SELECT asset.${FieldConst.REPO_ID}, ?, asset.${FieldConst.ID}
        FROM asset
       WHERE asset.${FieldConst.REPO_ID} = ?
         AND asset.${FieldConst.ID} $inIdSet
         AND asset.${FieldConst.Asset.IS_RECYCLED} = ?
       ORDER BY asset.${FieldConst.ID}
          ON CONFLICT DO NOTHING
    """

    val values: List[Any] = List(locationId, RequestContext.getRepository.persistedId, idSet(assetIds), nativeBool(false))

    updateByBySql(sql, values)

  override def removeAssets(locationId: String, assetIds: Set[String]): Int =
    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Location.LOCATION_ID} = ?
         AND ${FieldConst.Location.ASSET_ID} $inIdSet
    """

    updateByBySql(sql, List(RequestContext.getRepository.persistedId, locationId, idSet(assetIds)))

  override def removeAssetsFromAllLocations(assetIds: Set[String]): Int =
    val sql = s"""
      DELETE FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Location.ASSET_ID} $inIdSet
    """

    updateByBySql(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))

  override def getAssetIds(locationId: String): Set[String] =
    val sql = s"""
      SELECT ${FieldConst.Location.ASSET_ID}
        FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Location.LOCATION_ID} = ?
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, locationId))
      .map(_(FieldConst.Location.ASSET_ID).asInstanceOf[String])
      .toSet

  override def getLocationsOfAsset(assetId: String): List[Location] =
    // By the index that leads with the asset. Path order as in `getAll`, less the category-first key: a member is never a
    // category
    val sql = s"""
      SELECT l.*, p.${FieldConst.Location.NAME} AS ${FieldConst.Location.CATEGORY_NAME}
        FROM $tableName l
             JOIN $membershipTable la ON la.${FieldConst.Location.LOCATION_ID} = l.${FieldConst.ID}
             LEFT JOIN $tableName p ON p.${FieldConst.ID} = l.${FieldConst.Location.CATEGORY_ID}
       WHERE la.${FieldConst.REPO_ID} = ?
         AND la.${FieldConst.Location.ASSET_ID} = ?
       ORDER BY COALESCE(p.${FieldConst.Location.NAME_KEY}, l.${FieldConst.Location.NAME_KEY}), l.${FieldConst.Location.NAME_KEY}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId)).map(makeModel)

  override def getAssetsInAnyLocation(assetIds: Set[String]): Set[String] =
    if assetIds.isEmpty then return Set.empty[String]

    val sql = s"""
      SELECT DISTINCT ${FieldConst.Location.ASSET_ID}
        FROM $membershipTable
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Location.ASSET_ID} $inIdSet
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))
      .map(_(FieldConst.Location.ASSET_ID).asInstanceOf[String])
      .toSet

  override def moveChildrenToRoot(categoryId: String): Int =
    val children = new Query().add(FieldConst.Location.CATEGORY_ID -> categoryId)
    updateByQuery(children, Map(FieldConst.Location.CATEGORY_ID -> None))
