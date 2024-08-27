package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json._
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.models.Field
import software.altitude.core.models.Metadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult


abstract class AssetDao(val config: Config) extends BaseDao with software.altitude.core.dao.AssetDao {
  override final val tableName = "asset"

  override val sqlQueryBuilder = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val assetType = new AssetType(
      mediaType = rec(Field.AssetType.MEDIA_TYPE).asInstanceOf[String],
      mediaSubtype = rec(Field.AssetType.MEDIA_SUBTYPE).asInstanceOf[String],
      mime = rec(Field.AssetType.MIME_TYPE).asInstanceOf[String])

    val metadataJson = getJsonFromColumn(rec(Field.Asset.METADATA))

    val extractedMetadataCol = rec(Field.Asset.EXTRACTED_METADATA)
    val extractedMetadataJsonStr: String = if (extractedMetadataCol == null) "{}" else extractedMetadataCol.asInstanceOf[String]
    val extractedMetadataJson = Json.parse(extractedMetadataJsonStr).as[JsObject]

    val model = new Asset(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      userId = rec(Field.USER_ID).asInstanceOf[String],
      fileName = rec(Field.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(Field.Asset.CHECKSUM).asInstanceOf[Int],
      assetType = assetType,
      sizeBytes = rec(Field.Asset.SIZE_BYTES).asInstanceOf[Int],
      metadata = metadataJson: Metadata,
      extractedMetadata = extractedMetadataJson: Metadata,
      folderId = rec(Field.Asset.FOLDER_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(Field.Asset.IS_RECYCLED)),
      isTriaged = getBooleanField(rec(Field.Asset.IS_TRIAGED)),
    )

    addCoreAttrs(model, rec)
  }

  override def queryNotRecycled(q: Query): QueryResult = {
    this.query(q.add(Field.Asset.IS_RECYCLED -> false).withRepository(), sqlQueryBuilder)
  }

  override def queryRecycled(q: Query): QueryResult = {
    this.query(q.add(Field.Asset.IS_RECYCLED -> true).withRepository(), sqlQueryBuilder)
  }

  override def queryAll(q: Query): QueryResult = {
    this.query(q.withRepository(), sqlQueryBuilder)
  }

  override def getMetadata(assetId: String): Option[Metadata] = {
    val sql = s"""
      SELECT ${Field.Asset.METADATA}
         FROM $tableName
       WHERE ${Field.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(Field.Asset.METADATA))
    val metadata = Metadata.fromJson(metadataJson)
    Some(metadata)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val asset = jsonIn: Asset

    val metadataWithIds = Metadata.withIds(asset.metadata)

    val metadata: String = metadataWithIds.toString().replaceAll("\\\\u0000", "")
    val extractedMetadata: String = asset.extractedMetadata.toString().replaceAll("\\\\u0000", "")

    val sql = s"""
        INSERT INTO $tableName (
             ${Field.ID}, ${Field.REPO_ID}, ${Field.USER_ID}, ${Field.Asset.CHECKSUM},
             ${Field.Asset.FILENAME}, ${Field.Asset.SIZE_BYTES},
             ${Field.AssetType.MEDIA_TYPE}, ${Field.AssetType.MEDIA_SUBTYPE}, ${Field.AssetType.MIME_TYPE},
             ${Field.Asset.FOLDER_ID}, ${Field.Asset.IS_TRIAGED}, ${Field.Asset.METADATA}, ${Field.Asset.EXTRACTED_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match {
      case Some(id) => id
      case None => BaseDao.genId
    }

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
      metadata,
      extractedMetadata)

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(Field.ID -> id)
  }

  override def setMetadata(assetId: String, metadata: Metadata): Unit = {
    BaseDao.incrWriteQueryCount()

    val metadataWithIds = Metadata.withIds(metadata)

    val sql = s"""
      UPDATE $tableName
         SET ${Field.Asset.METADATA} = $jsonFunc
       WHERE ${Field.REPO_ID} = ? AND ${Field.ID} = ?
      """

    val updateValues = List(metadataWithIds.toString, RequestContext.getRepository.persistedId, assetId)
    logger.debug(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues: _*)
  }
}
