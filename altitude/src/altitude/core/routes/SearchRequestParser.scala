package altitude.core.routes

import altitude.core.Api
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.util.BoundingBox
import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SearchText
import altitude.core.util.TriageSubset

/**
 * The grid, map cells, map bounds and the counts of the Triage routes (`TriageController`) interpret search scope identically;
 * each caller owns its presentation and paging.
 *
 * A scope has Search text only when `q` has a usable term: text that parses to nothing (blank, a lone `-`, a dangling `OR`) is no
 * text, for the filter, for the default sort and for the rules below alike. Text searches the repository outside the trash, so
 * text with the trash view is refused.
 *
 * The repository view holds the sorted assets, whatever the folder, person, album, Location, text or map area, and `triaged=true`
 * adds the triaged ones to them. In the trash `triaged` tells the assets recycled from triage (`true`) from the rest (`false`).
 * The triage view holds nothing but triaged assets, so `triaged=false` with it is refused.
 *
 * `subset` narrows any view to a Triage subset, the assets one of the Triage summary's counts shows (`TriageSubset`):
 * `unknownPeople`, those with a Face of an Unknown person, or `noLocation`, those no Location points at, whatever their own GPS
 * position. Only Triage offers it, but every view takes it. Any other value, empty included, is refused.
 */
object SearchRequestParser:
  case class Scope(
      params: Map[String, Any],
      text: Option[String],
      folderIds: Set[String],
      personIds: Set[String],
      albumIds: Set[String],
      locationIds: Set[String],
      bbox: Option[BoundingBox],
      subset: Option[TriageSubset],
      // How several people combine, and the Date Taken and Date Imported ranges; `parse` leaves them at these defaults
      peopleMatch: PeopleMatch = PeopleMatch.All,
      takenRange: Option[DateRange] = None,
      importedRange: Option[DateRange] = None):
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
        peopleMatch = peopleMatch,
        albumIds = albumIds,
        locationIds = locationIds,
        takenRange = takenRange,
        importedRange = importedRange,
        bbox = bbox,
        subset = subset,
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
      triaged: Option[Boolean] = None,
      subset: Option[String] = None): Either[String, Scope] =
    if view == Const.Search.View.TRIAGE && triaged.contains(false) then
      return Left(s"The ${Const.Search.View.TRIAGE} view holds only triaged assets")

    val viewParams: Map[String, Any] = view match
      case Const.Search.View.TRIAGE => SearchQuery.TRIAGE_VIEW
      case Const.Search.View.TRASHBIN => Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false)
      case _ => Map(FieldConst.Asset.IS_RECYCLED -> false)
    val triageParam = view match
      case Const.Search.View.TRIAGE => None
      case Const.Search.View.TRASHBIN => triaged.map(FieldConst.Asset.IS_TRIAGED -> _)
      case _ => Option.unless(triaged.contains(true))(FieldConst.Asset.IS_TRIAGED -> false)
    val params = viewParams ++ triageParam

    val text = q.filter(SearchText.parse(_).isDefined)
    if text.isDefined && view == Const.Search.View.TRASHBIN then return Left("Search text does not search the trash")

    val box =
      try bbox.map(BoundingBox.parse)
      catch case ex: IllegalArgumentException => return Left(s"Invalid bbox: ${ex.getMessage}")

    val triageSubset = subset.map(TriageSubset.fromApiValue) match
      case None => None
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.SUBSET} value")
      case Some(found) => found

    Right(Scope(params, text, folderId.toSet, personId.toSet, albumId.toSet, locationId.toSet, box, triageSubset))
