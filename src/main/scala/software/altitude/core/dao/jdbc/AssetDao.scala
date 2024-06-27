package software.altitude.core.dao.jdbc

import org.apache.commons.dbutils.QueryRunner
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.AltitudeAppContext
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.models.Metadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

abstract class AssetDao(val appContext: AltitudeAppContext) extends BaseDao with software.altitude.core.dao.AssetDao {
  private final val log = LoggerFactory.getLogger(getClass)

  override final val tableName = "asset"

  override val sqlQueryBuilder = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val assetType = new AssetType(
      mediaType = rec(C.AssetType.MEDIA_TYPE).asInstanceOf[String],
      mediaSubtype = rec(C.AssetType.MEDIA_SUBTYPE).asInstanceOf[String],
      mime = rec(C.AssetType.MIME_TYPE).asInstanceOf[String])

    val metadataJson = getJsonFromColumn(rec(C.Asset.METADATA))

    val extractedMetadataCol = rec(C.Asset.EXTRACTED_METADATA)
    val extractedMetadataJsonStr: String = if (extractedMetadataCol == null) "{}" else extractedMetadataCol.asInstanceOf[String]
    val extractedMetadataJson = Json.parse(extractedMetadataJsonStr).as[JsObject]

    val model = new Asset(
      id = Some(rec(C.Base.ID).asInstanceOf[String]),
      userId = rec(C.Base.USER_ID).asInstanceOf[String],
      fileName = rec(C.Asset.FILENAME).asInstanceOf[String],
      isRecycled = getBooleanField(rec(C.Asset.IS_RECYCLED)),
      checksum = rec(C.Asset.CHECKSUM).asInstanceOf[String],
      assetType = assetType,
      sizeBytes = rec(C.Asset.SIZE_BYTES).asInstanceOf[Int],
      metadata = metadataJson: Metadata,
      extractedMetadata = extractedMetadataJson: Metadata,
      folderId = rec(C.Asset.FOLDER_ID).asInstanceOf[String])

    addCoreAttrs(model, rec)
  }

  override def queryNotRecycled(q: Query): QueryResult = {
    this.query(q.add(C.Asset.IS_RECYCLED -> false).withRepository(), sqlQueryBuilder)
  }

  override def queryRecycled(q: Query): QueryResult = {
    this.query(q.add(C.Asset.IS_RECYCLED -> true).withRepository(), sqlQueryBuilder)
  }

  override def queryAll(q: Query): QueryResult = {
    this.query(q.withRepository(), sqlQueryBuilder)
  }

  override def getMetadata(assetId: String): Option[Metadata] = {
    val sql = s"""
      SELECT ${C.Asset.METADATA}
         FROM $tableName
       WHERE ${C.Asset.ID} = ?
      """

    val rec = getOneRawRecordBySql(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(C.Asset.METADATA))
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
             ${C.Asset.ID}, ${C.Asset.REPO_ID}, ${C.Base.USER_ID}, ${C.Asset.CHECKSUM},
             ${C.Asset.FILENAME}, ${C.Asset.SIZE_BYTES},
             ${C.AssetType.MEDIA_TYPE}, ${C.AssetType.MEDIA_SUBTYPE}, ${C.AssetType.MIME_TYPE},
             ${C.Asset.FOLDER_ID}, ${C.Asset.METADATA}, ${C.Asset.EXTRACTED_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match {
      case Some(id) => id
      case None => BaseDao.genId
    }

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.id.get,
      asset.userId,
      asset.checksum,
      asset.fileName,
      asset.sizeBytes.asInstanceOf[Object],
      asset.assetType.mediaType,
      asset.assetType.mediaSubtype,
      asset.assetType.mime,
      asset.folderId,
      metadata,
      extractedMetadata)

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(C.Base.ID -> id)
  }

  override def setMetadata(assetId: String, metadata: Metadata)
                          : Unit = {

    val metadataWithIds = Metadata.withIds(metadata)

    val sql = s"""
      UPDATE $tableName
         SET ${C.Asset.METADATA} = $jsonFunc
       WHERE ${C.Base.REPO_ID} = ? AND ${C.Asset.ID} = ?
      """

    val updateValues = List(metadataWithIds.toString, RequestContext.getRepository.id.get, assetId)
    log.debug(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues: _*)
  }
}
