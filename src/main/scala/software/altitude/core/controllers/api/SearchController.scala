package software.altitude.core.controllers.api

import org.scalatra.ActionResult
import org.scalatra.Ok
import play.api.libs.json.JsNull
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.Const
import software.altitude.core.RequestContext
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Asset
import software.altitude.core.models.Repository
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchSort

class SearchController extends BaseApiController {

  get("/r/:repoId/") {
    val repo: Repository = RequestContext.getRepository
    val foldersQuery = params.getOrElse(Api.Field.Search.FOLDERS, "")

    val folderId = if (foldersQuery.isEmpty) repo.rootFolderId else foldersQuery

    defaultQuery(folderId)
  }

  get(s"/r/:repoId/p/:${Api.Field.Search.PAGE}/rpp/:${Api.Field.Search.RESULTS_PER_PAGE}/?") {
    val repo: Repository = RequestContext.getRepository

    val rpp = params.getOrElse(Api.Field.Search.RESULTS_PER_PAGE, Const.Search.DEFAULT_RPP.toString).toInt
    val page = params.getOrElse(Api.Field.Search.PAGE, "1").toInt
    val queryText = params.get(Api.Field.Search.QUERY_TEXT)
    val sortArg = params.get(Api.Field.Search.SORT)
    logger.info(s"Query string: $queryText")

    logger.info(s"Sort: $sortArg")

    val sort: List[SearchSort] = if (sortArg.isDefined) {
      val fieldId :: directionInt :: _ = sortArg.get.split("\\|").toList
      logger.info(s"Sort field ID: $fieldId, direction: $directionInt")
      List()
    } else {
      List()
    }

    val foldersQuery = params.getOrElse(Api.Field.Search.FOLDERS, "")
    val folderIdsArg = if (foldersQuery.isEmpty) repo.rootFolderId else foldersQuery

    val q = new SearchQuery(
      text = queryText,
      rpp = rpp, page = page,
      folderIds = parseFolderIds(folderIds = folderIdsArg),
      searchSort = sort
    )

    query(q)
  }

  private def defaultQuery(folderId: String): ActionResult = {
    val q = new SearchQuery(
      rpp = Const.Search.DEFAULT_RPP,
      folderIds = Set(folderId)
    )

    val results = app.service.library.search(q)

    Ok(Json.obj(
      Api.Field.Search.ASSETS -> results.records.map { x =>
        val asset = x: Asset
        asset.userMetadata.toJson
      },
      Api.Field.TOTAL_RECORDS -> results.total,
      Api.Field.CURRENT_PAGE -> q.page,
      Api.Field.TOTAL_PAGES -> results.totalPages,
      Api.Field.RESULTS_PER_PAGE -> q.rpp,
      Api.Field.Search.SORT -> (if (results.sort.isEmpty) JsNull else results.sort.head.toJson)
    ))
  }

  private def query(q: SearchQuery): ActionResult = {
    val results = app.service.library.search(q)

    Ok(Json.obj(
      Api.Field.Search.ASSETS -> results.records.map { x =>
        val asset = x: Asset
        asset.userMetadata.toJson
      },
      Api.Field.TOTAL_RECORDS -> results.total,
      Api.Field.CURRENT_PAGE -> q.page,
      Api.Field.TOTAL_PAGES -> results.totalPages,
      Api.Field.RESULTS_PER_PAGE -> q.rpp,
      Api.Field.Search.SORT -> (if (results.sort.isEmpty) JsNull else results.sort.head.toJson)
    ))
  }

  private def parseFolderIds(folderIds: String): Set[String] = {
    if (folderIds.isEmpty) {
      Set[String]()
    }
    else {
      folderIds
        .split(s"\\${Api.Field.MULTI_VALUE_DELIM}")
        .map(_.trim)
        .filter(_.nonEmpty).toSet
    }
  }
}
