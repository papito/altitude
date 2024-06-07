package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.dao.SearchDao
import software.altitude.core.models.Asset
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

object SearchService {
  // TODO: sound weird. Non-indexable? It's indexable, but can't be a facet
  val NON_INDEXABLE_FIELD_TYPES: Set[FieldType.Value] = Set(FieldType.TEXT)
}

class SearchService(val app: Altitude) {
  private final val log = LoggerFactory.getLogger(getClass)
  protected val searchDao: SearchDao = app.injector.instance[SearchDao]

  def indexAsset(asset: Asset)
                (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    require(asset.path.isEmpty)
    log.info(s"Indexing asset $asset")
    val metadataFields: Map[String, MetadataField] = app.service.metadata.getAllFields
    searchDao.indexAsset(asset, metadataFields)
  }

  def reindexAsset(asset: Asset)
                (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    log.info(s"Reindexing asset $asset")
    val metadataFields: Map[String, MetadataField] = app.service.metadata.getAllFields
    searchDao.reindexAsset(asset, metadataFields)
  }

  def search(query: SearchQuery)
            (implicit ctx: Context, txId: TransactionId = new TransactionId): SearchResult = {
    searchDao.search(query)
  }

  def addMetadataValue(asset: Asset, field: MetadataField, value: String)
                      (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    // some fields are not eligible for parametarized search
    if (SearchService.NON_INDEXABLE_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValue(asset, field, value)
  }

  def addMetadataValues(asset: Asset, field: MetadataField, values: Set[String])
                       (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    // some fields are not eligible for parametarized search
    if (SearchService.NON_INDEXABLE_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValues(asset, field, values)
  }
}
