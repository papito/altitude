package altitude.core.routes

import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.util.BoundingBox
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SearchText

/**
 * The grid, map cells and map bounds interpret search scope identically; each caller owns its presentation and paging.
 *
 * A scope has Search text only when `q` has a usable term: text that parses to nothing (blank, a lone `-`, a dangling `OR`) is no
 * text, for the filter, for the default sort and for the rules below alike. Text searches the repository outside the trash, so
 * text with the trash view is refused.
 *
 * `triaged` overlays the view's own rule on the triage flag: `false` leaves the triaged assets out of a view that holds them,
 * `true` keeps them alone. The triage view holds nothing else, so `triaged=false` with it is refused.
 */
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
      bbox: Option[String],
      triaged: Option[Boolean] = None): Either[String, Scope] =
    if view == Const.Search.View.TRIAGE && triaged.contains(false) then
      return Left(s"The ${Const.Search.View.TRIAGE} view holds only triaged assets")

    val viewParams: Map[String, Any] = view match
      // An asset recycled from triage keeps its triage flag, so the trash is left out explicitly
      case Const.Search.View.TRIAGE => Map(FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)
      case Const.Search.View.TRASHBIN => Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false)
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)
    val params = viewParams ++ triaged.map(FieldConst.Asset.IS_TRIAGED -> _)

    val text = q.filter(SearchText.parse(_).isDefined)
    if text.isDefined && view == Const.Search.View.TRASHBIN then return Left("Search text does not search the trash")

    val box =
      try bbox.map(BoundingBox.parse)
      catch case ex: IllegalArgumentException => return Left(s"Invalid bbox: ${ex.getMessage}")

    Right(Scope(params, text, folderId.toSet, personId.toSet, albumId.toSet, locationId.toSet, box))
