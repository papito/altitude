package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.dao.SearchDao
import altitude.core.models.Asset
import altitude.core.models.FieldType
import altitude.core.models.UserMetadataField
import altitude.core.transactions.TransactionManager
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

object SearchService {
  private val NON_FACETED_FIELD_TYPES: Set[FieldType.Value] = Set(FieldType.TEXT)
}

class SearchService(val app: Altitude) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val searchDao: SearchDao = app.DAO.search
  protected val txManager: TransactionManager = app.txManager

  def indexAsset(asset: Asset): Unit = {
    require(asset.id.isDefined, "Asset ID cannot be empty")
    logger.info(s"Indexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.indexAsset(asset, metadataFields)
    }
  }

  def reindexAsset(asset: Asset): Unit = {
    logger.info(s"Reindexing asset $asset")
    val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
    searchDao.reindexAsset(asset, metadataFields)
  }

  def search(query: SearchQuery): SearchResult = {
    searchDao.search(query)
  }

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit = {
    // some fields are not eligible for parameterized search
    if (SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValue(asset, field, value)
  }

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit = {
    // some fields are not eligible for parameterized search
    if (SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValues(asset, field, values)
  }
}
