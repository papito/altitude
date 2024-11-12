package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json._
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.models.ExtractedMetadata
import software.altitude.core.models.Field
import software.altitude.core.models.PublicMetadata
import software.altitude.core.models.UserMetadata
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

    val model = new Asset(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      userId = rec(Field.USER_ID).asInstanceOf[String],
      fileName = rec(Field.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(Field.Asset.CHECKSUM).asInstanceOf[Int],
      assetType = assetType,
      sizeBytes = rec(Field.Asset.SIZE_BYTES).asInstanceOf[Int],
      extractedMetadata = getJsonFromColumn(rec(Field.Asset.EXTRACTED_METADATA)): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(Field.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata =getJsonFromColumn(rec(Field.Asset.USER_METADATA)): UserMetadata,
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

  override def getUserMetadata(assetId: String): Option[UserMetadata] = {
    val sql = s"""
      SELECT ${Field.Asset.USER_METADATA}
         FROM $tableName
       WHERE ${Field.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val userMetadataJson = getJsonFromColumn(rec(Field.Asset.USER_METADATA))
    val userMetadata = UserMetadata.fromJson(userMetadataJson)
    Some(userMetadata)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val asset = jsonIn: Asset

    val sql = s"""
        INSERT INTO $tableName (
             ${Field.ID}, ${Field.REPO_ID}, ${Field.USER_ID}, ${Field.Asset.CHECKSUM},
             ${Field.Asset.FILENAME}, ${Field.Asset.SIZE_BYTES},
             ${Field.AssetType.MEDIA_TYPE}, ${Field.AssetType.MEDIA_SUBTYPE}, ${Field.AssetType.MIME_TYPE},
             ${Field.Asset.FOLDER_ID}, ${Field.Asset.IS_TRIAGED}, ${Field.Asset.USER_METADATA},
             ${Field.Asset.EXTRACTED_METADATA}, ${Field.Asset.PUBLIC_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc, $jsonFunc)
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
      UserMetadata.withIds(asset.userMetadata).toString,
      asset.extractedMetadata.toString,
      asset.publicMetadata.toString
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(Field.ID -> id)
  }

  override def setUserMetadata(assetId: String, userMetadata: UserMetadata): Unit = {
    BaseDao.incrWriteQueryCount()

    val metadataWithIds = UserMetadata.withIds(userMetadata)

    val sql = s"""
      UPDATE $tableName
         SET ${Field.Asset.USER_METADATA} = $jsonFunc
       WHERE ${Field.REPO_ID} = ? AND ${Field.ID} = ?
      """

    val updateValues = List(metadataWithIds.toString, RequestContext.getRepository.persistedId, assetId)
    logger.debug(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues: _*)
  }
}
