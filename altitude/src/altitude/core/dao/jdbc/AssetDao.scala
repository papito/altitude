package altitude.core.dao.jdbc

import altitude.core.{ Const => C }
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.ExtractedMetadata
import altitude.core.models.PublicMetadata
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult
import com.typesafe.config.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json._

import scala.language.implicitConversions

abstract class AssetDao(val config: Config) extends BaseDao with altitude.core.dao.AssetDao:
  final override val tableName = "asset"

  override val sqlQueryBuilder = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject =
    val assetType = new AssetType(
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
      sizeBytes = rec(FieldConst.Asset.SIZE_BYTES).asInstanceOf[Int],
      extractedMetadata = getJsonFromColumn(rec(FieldConst.Asset.EXTRACTED_METADATA)): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(FieldConst.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)): UserMetadata,
      folderId = rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String].trim,
      isRecycled = getBooleanField(rec(FieldConst.Asset.IS_RECYCLED)),
      isTriaged = getBooleanField(rec(FieldConst.Asset.IS_TRIAGED)),
      isPipelineProcessed = getBooleanField(rec(FieldConst.Asset.IS_PIPELINE_PROCESSED)),
      originalCreatedAt = getDateTimeField(rec.get(FieldConst.Asset.ORIGINAL_CREATED_AT)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    ).toJson

  override def queryNotRecycled(q: Query): QueryResult =
    this.query(q.add(FieldConst.Asset.IS_RECYCLED -> false).withRepository(), sqlQueryBuilder)

  override def queryTriaged(q: Query): QueryResult =
    this.query(q.add(FieldConst.Asset.IS_TRIAGED -> true).withRepository(), sqlQueryBuilder)

  override def queryRecycled(q: Query): QueryResult =
    this.query(q.add(FieldConst.Asset.IS_RECYCLED -> true).withRepository(), sqlQueryBuilder)

  override def queryAll(q: Query): QueryResult =
    this.query(q.withRepository(), sqlQueryBuilder)

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

  override def add(jsonIn: JsObject): JsObject =
    val asset = jsonIn: Asset

    val sql = s"""
        INSERT INTO asset (
             ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.USER_ID}, ${FieldConst.Asset.CHECKSUM},
             ${FieldConst.Asset.FILENAME}, ${FieldConst.Asset.SIZE_BYTES},
             ${FieldConst.AssetType.MEDIA_TYPE}, ${FieldConst.AssetType.MEDIA_SUBTYPE}, ${FieldConst.AssetType.MIME_TYPE},
             ${FieldConst.Asset.FOLDER_ID}, ${FieldConst.Asset.IS_TRIAGED},  ${FieldConst.Asset.ORIGINAL_CREATED_AT},
            ${FieldConst.Asset.WIDTH}, ${FieldConst.Asset.HEIGHT}, ${FieldConst.Asset.AREA_SIZE},
             ${FieldConst.Asset.USER_METADATA}, ${FieldConst.Asset.EXTRACTED_METADATA}, ${FieldConst.Asset.PUBLIC_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match
      case Some(id) => id
      case None => BaseDao.genId

    val originalDateTime: LocalDateTime = asset.publicMetadata.dateTimeOriginal match
      case Some(dateTime) =>
        try
          val originalDt = LocalDateTime.parse(dateTime, exifDateTimeFormatterPattern)
          originalDt
        catch
          case _: DateTimeParseException =>
            logger.warn(s"DateTimeParseException. Failed to parse dateTimeOriginal [$dateTime] for asset with id [$id]")
            LocalDateTime.now()
          case _: Exception =>
            logger.error(s"Unknown exception. ailed to parse dateTimeOriginal [$dateTime] for asset with id [$id]")
            LocalDateTime.now()
      case None => LocalDateTime.now()

    val sqlOriginalDateTime = getDataSourceType match
      case C.DbEngineName.POSTGRES =>
        Some(originalDateTime)
      case C.DbEngineName.SQLITE =>
        Some(originalDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))

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
      sqlOriginalDateTime.orNull,
      asset.width,
      asset.height,
      asset.width * asset.height,
      UserMetadata.withIds(asset.userMetadata).toJson.toString,
      asset.extractedMetadata.toJson.toString,
      asset.publicMetadata.toJson.toString
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(FieldConst.ID -> id)

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
    /**
     * Pedestrian version of this just overwrites fields for old metadata and re-sets it on the asset. A better implementation -
     * for advanced engines - updates only the metadata fields of interest.
     */
    // OPTIMIZE
    val existingMetadata = getUserMetadata(assetId) match
      case Some(m) => m
      case None => UserMetadata()

    logger.debug(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new UserMetadata(newData)
    logger.debug(s"New metadata -> $newMetadata")

    setUserMetadata(assetId, newMetadata)
