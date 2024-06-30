package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.dao.SearchDao
import software.altitude.core.models.Asset
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

object SearchService {
  // TODO: sound weird. Non-indexable? It's indexable, but can't be a facet
  private val NON_INDEXABLE_FIELD_TYPES: Set[FieldType.Value] = Set(FieldType.TEXT)
}

class SearchService(val app: Altitude) {
  private final val log = LoggerFactory.getLogger(getClass)
  private val searchDao: SearchDao = app.injector.instance[SearchDao]

  def indexAsset(asset: Asset): Unit = {
    require(asset.id.isDefined, "Asset ID cannot be empty")
    log.info(s"Indexing asset $asset")
    val metadataFields: Map[String, MetadataField] = app.service.metadata.getAllFields
    searchDao.indexAsset(asset, metadataFields)
  }

  def reindexAsset(asset: Asset): Unit = {
    log.info(s"Reindexing asset $asset")
    val metadataFields: Map[String, MetadataField] = app.service.metadata.getAllFields
    searchDao.reindexAsset(asset, metadataFields)
  }

  def search(query: SearchQuery): SearchResult = {
    searchDao.search(query)
  }

  def addMetadataValue(asset: Asset, field: MetadataField, value: String): Unit = {
    // some fields are not eligible for parameterized search
    if (SearchService.NON_INDEXABLE_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValue(asset, field, value)
  }

  def addMetadataValues(asset: Asset, field: MetadataField, values: Set[String]): Unit = {
    // some fields are not eligible for parameterized search
    if (SearchService.NON_INDEXABLE_FIELD_TYPES.contains(field.fieldType)) return

    searchDao.addMetadataValues(asset, field, values)
  }
}
