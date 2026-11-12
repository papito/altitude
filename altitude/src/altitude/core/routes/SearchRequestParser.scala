package altitude.core.routes

import java.time.LocalDate
import java.time.format.DateTimeParseException

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
 *
 * An advanced search names several people or several Locations, comma-separated in `personId` or `locationId` and at most
 * [[SearchRequestParser.MAX_IDS]] in each, and narrows by a Date Taken range (`takenFrom`, `takenTo`) and a Date Imported range
 * (`importedFrom`, `importedTo`), each end an ISO date and either left out. `peopleMatch` says whether several people match `all`
 * of them, as they do when it is left out, or `any`. An ID that names nothing in the repository matches nothing. An advanced
 * search searches outside the trash, as text does: a date range, several people or several Locations with the trash view are
 * refused.
 */
object SearchRequestParser:

  /** The most people, and the most Locations, one search names */
  val MAX_IDS = 10

  case class Scope(
      params: Map[String, Any],
      text: Option[String],
      folderIds: Set[String],
      personIds: Set[String],
      albumIds: Set[String],
      locationIds: Set[String],
      bbox: Option[BoundingBox],
      subset: Option[TriageSubset],
      // How several people combine, all of them unless the request says any, and the Date Taken and Date Imported ranges
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
      subset: Option[String] = None,
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None): Either[String, Scope] =
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
    val isTrash = view == Const.Search.View.TRASHBIN
    if text.isDefined && isTrash then return Left("Search text does not search the trash")

    val personIds = idsOf(personId)
    val locationIds = idsOf(locationId)
    if personIds.size > MAX_IDS then return Left(s"A search names at most $MAX_IDS people")
    if locationIds.size > MAX_IDS then return Left(s"A search names at most $MAX_IDS Locations")
    if isTrash && (personIds.size > 1 || locationIds.size > 1) then
      return Left("Several people or Locations do not search the trash")

    val matched = peopleMatch.map(PeopleMatch.fromApiValue) match
      case None => PeopleMatch.All
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.PEOPLE_MATCH} value")
      case Some(Some(found)) => found

    val takenRange = rangeOf(Api.Field.Search.TAKEN_FROM, takenFrom, Api.Field.Search.TAKEN_TO, takenTo) match
      case Left(message) => return Left(message)
      case Right(range) => range
    val importedRange = rangeOf(Api.Field.Search.IMPORTED_FROM, importedFrom, Api.Field.Search.IMPORTED_TO, importedTo) match
      case Left(message) => return Left(message)
      case Right(range) => range
    if isTrash && (takenRange.isDefined || importedRange.isDefined) then return Left("A date range does not search the trash")

    val box =
      try bbox.map(BoundingBox.parse)
      catch case ex: IllegalArgumentException => return Left(s"Invalid bbox: ${ex.getMessage}")

    val triageSubset = subset.map(TriageSubset.fromApiValue) match
      case None => None
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.SUBSET} value")
      case Some(found) => found

    Right(
      Scope(
        params = params,
        text = text,
        folderIds = folderId.toSet,
        personIds = personIds.toSet,
        albumIds = albumId.toSet,
        locationIds = locationIds.toSet,
        bbox = box,
        subset = triageSubset,
        peopleMatch = matched,
        takenRange = takenRange,
        importedRange = importedRange
      ))

  /** The IDs a comma-separated parameter names, each once, in the order given; none for a parameter left out */
  def idsOf(value: Option[String]): List[String] =
    value.toList.flatMap(_.split(",", -1)).distinct

  /**
   * The range of days two date parameters make, none when both are left out. A date that is not an ISO day, and a range whose
   * first day is after its last, is the message of a 400 naming the parameters.
   */
  private def rangeOf(
      fromName: String,
      from: Option[String],
      toName: String,
      to: Option[String]): Either[String, Option[DateRange]] =
    def day(name: String, value: Option[String]): Either[String, Option[LocalDate]] =
      try Right(value.map(LocalDate.parse))
      catch case _: DateTimeParseException => Left(s"$name must be a date, as 2024-06-01")

    for
      first <- day(fromName, from)
      last <- day(toName, to)
      range <-
        try Right(Option.when(first.isDefined || last.isDefined)(DateRange(first, last)))
        catch case ex: IllegalArgumentException => Left(s"Invalid $fromName and $toName: ${ex.getMessage}")
    yield range
