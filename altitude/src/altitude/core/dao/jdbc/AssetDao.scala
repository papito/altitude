package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.models.PublicMetadata
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult

abstract class AssetDao(val config: Config) extends BaseDao[Asset] with altitude.core.dao.AssetDao:
  final override val tableName = "asset"

  final override type Row[T[_]] = AssetRow[T]
  final override protected def table: Table[Row] = AssetRow

  override protected def toModel(row: AssetRow[Sc]): Asset =
    val assetType = AssetType(mediaType = row.mediaType, mediaSubtype = row.mediaSubtype, mime = row.mimeType)

    Asset(
      id = Option(row.id),
      userId = row.userId,
      fileName = row.filename,
      checksum = row.checksum,
      assetType = assetType,
      width = row.width,
      height = row.height,
      durationMs = row.durationMs,
      sizeBytes = row.sizeBytes,
      extractedMetadata = getJsonFromColumn(row.extractedMetadata.orNull): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(row.publicMetadata.orNull): PublicMetadata,
      userMetadata = getJsonFromColumn(row.userMetadata.orNull): UserMetadata,
      folderId = row.folderId.trim,
      isRecycled = row.isRecycled,
      isTriaged = row.isTriaged,
      isPipelineProcessed = row.isPipelineProcessed,
      originalCreatedAt = row.originalCreatedAt,
      originalCreatedAtSource = row.originalCreatedAtSource.flatMap(CaptureDateSource.fromDbValue),
      latitude = row.latitude,
      longitude = row.longitude,
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Asset =
    val assetType = AssetType(
      mediaType = rec(FieldConst.AssetType.MEDIA_TYPE).asInstanceOf[String],
      mediaSubtype = rec(FieldConst.AssetType.MEDIA_SUBTYPE).asInstanceOf[String],
      mime = rec(FieldConst.AssetType.MIME_TYPE).asInstanceOf[String]
    )

    Asset(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      userId = rec(FieldConst.USER_ID).asInstanceOf[String],
      fileName = rec(FieldConst.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(FieldConst.Asset.CHECKSUM).asInstanceOf[Int],
      assetType = assetType,
      width = rec(FieldConst.Asset.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Asset.HEIGHT).asInstanceOf[Int],
      durationMs = getLongField(rec(FieldConst.Asset.DURATION_MS)),
      sizeBytes = getLongField(rec(FieldConst.Asset.SIZE_BYTES)).get,
      extractedMetadata = getJsonFromColumn(rec(FieldConst.Asset.EXTRACTED_METADATA)): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(FieldConst.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)): UserMetadata,
      folderId = rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String].trim,
      isRecycled = getBooleanField(rec(FieldConst.Asset.IS_RECYCLED)),
      isTriaged = getBooleanField(rec(FieldConst.Asset.IS_TRIAGED)),
      isPipelineProcessed = getBooleanField(rec(FieldConst.Asset.IS_PIPELINE_PROCESSED)),
      originalCreatedAt = getDateTimeField(rec.get(FieldConst.Asset.ORIGINAL_CREATED_AT)),
      originalCreatedAtSource =
        Option(rec(FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE)).flatMap(value => CaptureDateSource.fromDbValue(value.toString)),
      latitude = getDoubleField(rec(FieldConst.Asset.LATITUDE)),
      longitude = getDoubleField(rec(FieldConst.Asset.LONGITUDE)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def queryNotRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> false).withRepository())

  override def queryTriaged(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_TRIAGED -> true).withRepository())

  override def queryRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> true).withRepository())

  override def queryAll(q: Query): QueryResult[Asset] =
    queryRecords(q.withRepository())

  override def getUserMetadata(assetId: String): Option[UserMetadata] =
    val sql = s"""
      SELECT ${FieldConst.Asset.USER_METADATA}
         FROM asset
       WHERE ${FieldConst.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val userMetadataJson = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA))
    val userMetadata = UserMetadata.fromJson(userMetadataJson)
    Some(userMetadata)

  override def add(asset: Asset): Asset =
    val sql = s"""
        INSERT INTO asset (
             ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.USER_ID}, ${FieldConst.Asset.CHECKSUM},
             ${FieldConst.Asset.FILENAME}, ${FieldConst.Asset.SIZE_BYTES},
             ${FieldConst.AssetType.MEDIA_TYPE}, ${FieldConst.AssetType.MEDIA_SUBTYPE}, ${FieldConst.AssetType.MIME_TYPE},
             ${FieldConst.Asset.FOLDER_ID}, ${FieldConst.Asset.IS_TRIAGED}, ${FieldConst.Asset.ORIGINAL_CREATED_AT}, ${FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE},
             ${FieldConst.Asset.LATITUDE}, ${FieldConst.Asset.LONGITUDE},
             ${FieldConst.CREATED_AT},
             ${FieldConst.Asset.WIDTH}, ${FieldConst.Asset.HEIGHT}, ${FieldConst.Asset.AREA_SIZE}, ${FieldConst.Asset.DURATION_MS},
             ${FieldConst.Asset.USER_METADATA}, ${FieldConst.Asset.EXTRACTED_METADATA}, ${FieldConst.Asset.PUBLIC_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match
      case Some(id) => id
      case None => BaseDao.genId

    // Resolution belongs to the pipeline. Unknown capture times, their provenance and coordinates are bound as SQL NULL.
    val capture: Any = asset.originalCreatedAt.map(nativeLocalDateTime).orNull
    val captureSource: Any = asset.originalCreatedAtSource.map(_.dbValue).orNull
    val latitude: Any = asset.latitude.map(Double.box).orNull
    val longitude: Any = asset.longitude.map(Double.box).orNull
    val durationMs: Any = asset.durationMs.map(Long.box).orNull

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      asset.userId,
      asset.checksum,
      asset.fileName,
      asset.sizeBytes.asInstanceOf[Object],
      asset.assetType.mediaType,
      asset.assetType.mediaSubtype,
      asset.assetType.mime,
      asset.folderId,
      asset.isTriaged,
      capture,
      captureSource,
      latitude,
      longitude,
      // Bound explicitly in UTC rather than left to the engine default, whose value depends on the server zone
      nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC)),
      asset.width,
      asset.height,
      asset.width * asset.height,
      durationMs,
      UserMetadata.withIds(asset.userMetadata).toJson.toString,
      asset.extractedMetadata.toJson.toString,
      asset.publicMetadata.toJson.toString
    )

    addRecord(sql, sqlVals)
    asset.copy(id = Some(id))

  override def setUserMetadata(assetId: String, userMetadata: UserMetadata): Unit =
    BaseDao.incrWriteQueryCount()

    val metadataWithIds = UserMetadata.withIds(userMetadata)

    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.USER_METADATA} = $jsonFunc
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} = ?
      """

    val updateValues = List(metadataWithIds.toJson.toString, RequestContext.getRepository.persistedId, assetId)
    logger.debug(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues*)

  override def getAssetsToRecycle(assetIds: Set[String]): List[Asset] =
    getAssetsByIdAndRecycledFlag(assetIds, isRecycled = false)

  override def getAssetsToMove(assetIds: Set[String], folderId: String): List[Asset] =
    if assetIds.isEmpty then return List.empty[Asset]

    val placeHolders = List.fill(assetIds.size)("?").mkString(",")

    val sql = s"""
      SELECT asset.*,
             NULL AS ${FieldConst.Asset.USER_METADATA},
             NULL AS ${FieldConst.Asset.EXTRACTED_METADATA}
        FROM asset
       WHERE id IN ($placeHolders)
         $forUpdate
    """

    val res: List[Map[String, AnyRef]] = manyBySqlQuery(sql, assetIds.toList)
    res.map(makeModel)

  private def getAssetsByIdAndRecycledFlag(assetIds: Set[String], isRecycled: Boolean): List[Asset] =
    if assetIds.isEmpty then return List.empty[Asset]

    val placeHolders = List.fill(assetIds.size)("?").mkString(",")

    val sql = s"""
      SELECT asset.*,
             NULL AS ${FieldConst.Asset.USER_METADATA},
             NULL AS ${FieldConst.Asset.EXTRACTED_METADATA}
        FROM asset
       WHERE id IN ($placeHolders)
         AND is_recycled = ?
         $forUpdate
    """

    val res: List[Map[String, AnyRef]] = manyBySqlQuery(sql, assetIds.toList ++ List(this.nativeBool(isRecycled)))
    res.map(makeModel)

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit =
    val existingMetadata = getUserMetadata(assetId) match
      case Some(m) => m
      case None => UserMetadata()

    logger.debug(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new UserMetadata(newData)
    logger.debug(s"New metadata -> $newMetadata")

    setUserMetadata(assetId, newMetadata)

  override def countByFolder(): Map[String, Int] =
    // Mirrors the search predicate (SearchQueryBuilder): a folder's count must match what clicking it shows
    val sql = s"""
      SELECT ${FieldConst.Asset.FOLDER_ID}, COUNT(*) AS ${FieldConst.Folder.NUM_OF_ASSETS}
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_RECYCLED} = ?
         AND ${FieldConst.Asset.IS_TRIAGED} = ?
         AND ${FieldConst.Asset.IS_PURGED} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = ?
       GROUP BY ${FieldConst.Asset.FOLDER_ID}
    """

    val values =
      List(RequestContext.getRepository.persistedId, nativeBool(false), nativeBool(false), nativeBool(false), nativeBool(true))

    manyBySqlQuery(sql, values).map {
      rec => rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String] -> getIntField(rec(FieldConst.Folder.NUM_OF_ASSETS))
    }.toMap
