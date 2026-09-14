package altitude.core.routes

import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.util.BoundingBox
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort

/** The grid, map cells and map bounds interpret search scope identically; each caller owns its presentation and paging. */
object SearchRequestParser:
  case class Scope(
      params: Map[String, Any],
      text: Option[String],
      folderIds: Set[String],
      personIds: Set[String],
      albumIds: Set[String],
      locationIds: Set[String],
      bbox: Option[BoundingBox]):
    def query(
        rpp: Int = 0,
        page: Int = 1,
        searchSort: List[SearchSort] = Nil,
        grouping: Option[SearchGrouping] = None,
        cursor: Option[SearchCursor] = None): SearchQuery =
      new SearchQuery(
        params = params,
        text = text,
        folderIds = folderIds,
        personIds = personIds,
        albumIds = albumIds,
        locationIds = locationIds,
        bbox = bbox,
        rpp = rpp,
        page = page,
        searchSort = searchSort,
        grouping = grouping,
        cursor = cursor
      )

  def parse(
      view: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      locationId: Option[String],
      bbox: Option[String]): Either[String, Scope] =
    val params: Map[String, Any] = view match
      case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true)
      case Const.Search.View.TRASHBIN => Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false)
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)

    val box =
      try bbox.map(BoundingBox.parse)
      catch case ex: IllegalArgumentException => return Left(s"Invalid bbox: ${ex.getMessage}")

    Right(Scope(params, q, folderId.toSet, personId.toSet, albumId.toSet, locationId.toSet, box))
