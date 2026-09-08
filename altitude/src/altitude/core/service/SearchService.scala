package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.dao.SearchDao
import altitude.core.models.Asset
import altitude.core.models.FieldType
import altitude.core.models.UserMetadataField
import altitude.core.transactions.TransactionManager
import altitude.core.util.GroupedSearchResult
import altitude.core.util.SearchCursor
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

object SearchService:
  private val NON_FACETED_FIELD_TYPES: Set[FieldType] = Set(FieldType.TEXT)

class SearchService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val searchDao: SearchDao = app.DAO.search
  protected val txManager: TransactionManager = app.txManager

  def indexAsset(asset: Asset): Unit =
    require(asset.id.isDefined, "Asset ID cannot be empty")
    logger.info(s"Indexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.indexAsset(asset, metadataFields)
    }

  def reindexAsset(asset: Asset): Unit =
    logger.info(s"Reindexing asset $asset")
    val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
    searchDao.reindexAsset(asset, metadataFields)

  def search(query: SearchQuery): SearchResult =
    searchDao.search(query)

  /**
   * A grouped page: the DAO returns the rows and counts, the groups and the continuation cursor are assembled here. The cursor
   * points at the last returned image and carries the scope fingerprint of the search as requested.
   */
  def searchGrouped(query: SearchQuery, scopeFingerprint: String): GroupedSearchResult =
    val started = System.currentTimeMillis
    val page = searchDao.searchGrouped(query)

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      SearchCursor(day = last.day, sortValue = last.sortValue, id = last.asset.persistedId, scope = scopeFingerprint)
    }

    val groups = GroupedSearchResult.groupsOf(page.rows)
    val result = GroupedSearchResult(
      groups = groups,
      total = page.total,
      grouping = query.grouping.get,
      sort = query.searchSort.head,
      nextCursor = nextCursor,
      continuesGroup = query.cursor.exists(cursor => groups.headOption.exists(_.date == cursor.day))
    )

    logger.debug(
      s"Grouped search by ${result.grouping.by} ${result.grouping.direction}, sorted ${result.sort}: " +
        s"${result.assets.length} images in ${result.groups.length} groups" +
        result.total.map(total => s" of $total matching").getOrElse(" (continued)") +
        s", in ${System.currentTimeMillis - started}ms")

    result

  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    // some fields are not eligible for parameterized search
    if SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType) then return

    searchDao.addMetadataValue(asset, field, value)

  def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
    // some fields are not eligible for parameterized search
    if SearchService.NON_FACETED_FIELD_TYPES.contains(field.fieldType) then return

    searchDao.addMetadataValues(asset, field, values)
