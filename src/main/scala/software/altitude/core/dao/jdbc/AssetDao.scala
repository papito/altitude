package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import play.api.libs.json._
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.models.ExtractedMetadata
import software.altitude.core.models.PublicMetadata
import software.altitude.core.models.UserMetadata
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult


abstract class AssetDao(val config: Config) extends BaseDao with software.altitude.core.dao.AssetDao {
  override final val tableName = "asset"

  override val sqlQueryBuilder = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val assetType = new AssetType(
      mediaType = rec(FieldConst.AssetType.MEDIA_TYPE).asInstanceOf[String],
      mediaSubtype = rec(FieldConst.AssetType.MEDIA_SUBTYPE).asInstanceOf[String],
      mime = rec(FieldConst.AssetType.MIME_TYPE).asInstanceOf[String])

    Asset(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      userId = rec(FieldConst.USER_ID).asInstanceOf[String],
      fileName = rec(FieldConst.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(FieldConst.Asset.CHECKSUM).asInstanceOf[Int],
      assetType = assetType,
      sizeBytes = rec(FieldConst.Asset.SIZE_BYTES).asInstanceOf[Int],
      extractedMetadata = getJsonFromColumn(rec(FieldConst.Asset.EXTRACTED_METADATA)): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(FieldConst.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata =getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)): UserMetadata,
      folderId = rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(FieldConst.Asset.IS_RECYCLED)),
      isTriaged = getBooleanField(rec(FieldConst.Asset.IS_TRIAGED)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )
  }

  override def queryNotRecycled(q: Query): QueryResult = {
    this.query(q.add(FieldConst.Asset.IS_RECYCLED -> false).withRepository(), sqlQueryBuilder)
  }

  override def queryRecycled(q: Query): QueryResult = {
    this.query(q.add(FieldConst.Asset.IS_RECYCLED -> true).withRepository(), sqlQueryBuilder)
  }

  override def queryAll(q: Query): QueryResult = {
    this.query(q.withRepository(), sqlQueryBuilder)
  }

  override def getUserMetadata(assetId: String): Option[UserMetadata] = {
    val sql = s"""
      SELECT ${FieldConst.Asset.USER_METADATA}
         FROM $tableName
       WHERE ${FieldConst.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val userMetadataJson = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA))
    val userMetadata = UserMetadata.fromJson(userMetadataJson)
    Some(userMetadata)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val asset = jsonIn: Asset

    val sql = s"""
        INSERT INTO $tableName (
             ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.USER_ID}, ${FieldConst.Asset.CHECKSUM},
             ${FieldConst.Asset.FILENAME}, ${FieldConst.Asset.SIZE_BYTES},
             ${FieldConst.AssetType.MEDIA_TYPE}, ${FieldConst.AssetType.MEDIA_SUBTYPE}, ${FieldConst.AssetType.MIME_TYPE},
             ${FieldConst.Asset.FOLDER_ID}, ${FieldConst.Asset.IS_TRIAGED}, ${FieldConst.Asset.USER_METADATA},
             ${FieldConst.Asset.EXTRACTED_METADATA}, ${FieldConst.Asset.PUBLIC_METADATA})
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
      UserMetadata.withIds(asset.userMetadata).toJson.toString,
      asset.extractedMetadata.toJson.toString,
      asset.publicMetadata.toJson.toString
    )

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(FieldConst.ID -> id)
  }

  override def setUserMetadata(assetId: String, userMetadata: UserMetadata): Unit = {
    BaseDao.incrWriteQueryCount()

    val metadataWithIds = UserMetadata.withIds(userMetadata)

    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.Asset.USER_METADATA} = $jsonFunc
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} = ?
      """

    val updateValues = List(
      metadataWithIds.toJson.toString,
      RequestContext.getRepository.persistedId, assetId)
    logger.debug(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues: _*)
  }
}
