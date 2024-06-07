package software.altitude.core.dao.jdbc

import org.apache.commons.dbutils.QueryRunner
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.models._
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult
import software.altitude.core.{Const => C}

import java.sql.PreparedStatement
import java.sql.Types

object SearchDao {
  val VALUE_INSERT_SQL: String = s"""
            INSERT INTO search_parameter (
                        ${C.SearchToken.REPO_ID}, ${C.SearchToken.ASSET_ID},
                        ${C.SearchToken.FIELD_ID},
                        ${C.SearchToken.FIELD_VALUE_KW},
                        ${C.SearchToken.FIELD_VALUE_NUM},
                        ${C.SearchToken.FIELD_VALUE_BOOL})
                 VALUES (?, ?, ?, ?, ?, ?)
            """
}

abstract class SearchDao(override val app: Altitude)
  extends AssetDao(app)
    with software.altitude.core.dao.SearchDao {

  private final val log = LoggerFactory.getLogger(getClass)

  override def search(query: SearchQuery)(implicit ctx: Context, txId: TransactionId): SearchResult =
    throw new NotImplementedError

  protected def addSearchDocument(asset: Asset)(implicit ctx: Context, txId: TransactionId): Unit =
    throw new NotImplementedError

  protected def replaceSearchDocument(asset: Asset)(implicit ctx: Context, txId: TransactionId): Unit =
    throw new NotImplementedError

  override def indexAsset(asset: Asset, metadataFields: Map[String, MetadataField])
                         (implicit ctx: Context, txId: TransactionId): Unit = {
    log.debug(s"Indexing asset $asset with metadata [${asset.metadata}]")
    indexMetadata(asset, metadataFields)
    addSearchDocument(asset)
  }

  def reindexAsset(asset: Asset, metadataFields: Map[String, MetadataField])
                  (implicit ctx: Context, txId: TransactionId): Unit = {
    log.debug(s"Reindexing asset $asset with metadata [${asset.metadata}]")

    clearMetadata(asset.id.get)
    indexMetadata(asset, metadataFields)
    replaceSearchDocument(asset)
  }

  def clearMetadata(assetId: String)(implicit ctx: Context, txId: TransactionId): Unit = {
    log.debug(s"Clearing asset $assetId metadata")

    val sql =
      s"""
         DELETE FROM search_parameter
               WHERE ${C.SearchToken.REPO_ID} = ?
                 AND ${C.SearchToken.ASSET_ID} = ?
      """

    val bindValues = List[Object](ctx.repo.id.get, assetId)

    log.debug(s"Delete SQL: $sql, with values: $bindValues")
    val runner: QueryRunner = new QueryRunner()
    val numDeleted = runner.update(conn, sql, bindValues: _*)
    log.debug(s"Deleted records: $numDeleted")
  }

  def indexMetadata(asset: Asset, metadataFields: Map[String, MetadataField])
                             (implicit ctx: Context, txId: TransactionId): Unit = {
    log.debug(s"Indexing metadata for asset $asset: [${asset.metadata}]")

    asset.metadata.data.foreach { m =>
      val fieldId = m._1

      if (!metadataFields.contains(fieldId)) {
        log.error(s"Asset $asset contains metadata field ID [$fieldId] that is not part of field configuration!")
        return
      }

      val field = metadataFields(fieldId)
      val values = m._2
      log.debug(s"Processing field [${field.nameLowercase}] with values [$values]")

      addMetadataValues(asset = asset, field = field, values = values.map(_.value))
    }
  }

  override def addMetadataValue(asset: Asset, field: MetadataField, value: String)
                               (implicit ctx: Context, txId: TransactionId): Unit = {
    addMetadataValues(asset = asset, field = field, values = Set(value))
  }

  override def addMetadataValues(asset: Asset, field: MetadataField, values: Set[String])
                                (implicit ctx: Context, txId: TransactionId): Unit = {
    log.debug(s"INSERT SQL: ${SearchDao.VALUE_INSERT_SQL}. ARGS: ${values.toString()}")

    val preparedStatement: PreparedStatement = conn.prepareStatement(SearchDao.VALUE_INSERT_SQL)

      values.foreach { value: String =>
        preparedStatement.clearParameters()
        preparedStatement.setString(1, ctx.repo.id.get)
        preparedStatement.setString(2, asset.id.get)
        preparedStatement.setString(3, field.id.get)

        // keyword
        if (field.fieldType == FieldType.KEYWORD) {
          preparedStatement.setString(4, value.toLowerCase)
        } else {
          preparedStatement.setNull(4, Types.VARCHAR)
        }
        // number
        if (field.fieldType == FieldType.NUMBER) {
          preparedStatement.setDouble(5, value.toDouble)
        } else {
          preparedStatement.setNull(5, Types.DOUBLE)
        }
        // boolean
        if (field.fieldType == FieldType.BOOL) {
          preparedStatement.setBoolean(6, value.toBoolean)
        } else {
          preparedStatement.setNull(6, Types.BOOLEAN)
        }

        preparedStatement.execute()
      }

    replaceSearchDocument(asset)
  }

  override protected def addRecord(jsonIn: JsObject, q: String, values: List[Any])
                                  (implicit ctx: Context, txId: TransactionId): JsObject = {
    val runner: QueryRunner = new QueryRunner()
    runner.update(conn, q, values.map(_.asInstanceOf[Object]): _*)
    jsonIn
  }

}

