package altitude.core.routes

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

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
 * Reads a search scope the same way for the grid, the map's cells and bounds and the Triage counts; each caller owns its
 * presentation and paging.
 *
 * `q` is Search text only when it has a usable term: text that parses to nothing (blank, a lone `-`, a dangling `OR`) is no text,
 * for the filter and the default sort alike. The repository view holds the sorted assets and `triaged=true` adds the triaged
 * ones; in the trash `triaged` tells the assets recycled from Triage from the rest. `subset` narrows any view to one of the
 * Triage summary's counts, though only Triage offers it.
 *
 * Search text, a date range and several people or Locations never search the trash. What only the advanced search panel combines
 * (a date range, several people or Locations, or more than one of the text, a folder, a person and a Location) replaces the album
 * and the subset, so it is refused beside either. An ID that names nothing matches nothing.
 *
 * A description (`describe`) ranks by likeness the still images its other filters match, in the repository or the Triage view.
 */
object SearchRequestParser:

  /** The most people, and the most Locations, one search names */
  val MAX_IDS = 10

  /**
   * A search's parameters as the request spells them. A route builds one by name and hands it on whole, so no two parameters can
   * change places on the way.
   */
  case class Params(
      view: String = Const.Search.View.DEFAULT,
      q: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None,
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None,
      bbox: Option[String] = None,
      triaged: Option[Boolean] = None,
      subset: Option[String] = None,
      describe: Option[String] = None,
      // Handed on by a route that takes a description, which refuses the map layout
      layout: Option[String] = None)

  /**
   * The people and the Locations are in request order, each once. `description` stands beside the filters it ranks within: it is
   * no part of the query the scope builds.
   */
  case class Scope(
      params: Map[String, Any],
      text: Option[String],
      folderIds: Set[String],
      personIds: List[String],
      albumIds: Set[String],
      locationIds: List[String],
      bbox: Option[BoundingBox],
      subset: Option[TriageSubset],
      peopleMatch: PeopleMatch = PeopleMatch.All,
      takenRange: Option[DateRange] = None,
      importedRange: Option[DateRange] = None,
      description: Option[String] = None):
    def query(
        rpp: Int = 0,
        searchSort: List[SearchSort] = Nil,
        grouping: Option[SearchGrouping] = None,
        cursor: Option[SearchCursor] = None): SearchQuery =
      new SearchQuery(
        params = params,
        text = text,
        folderIds = folderIds,
        personIds = personIds.toSet,
        peopleMatch = peopleMatch,
        albumIds = albumIds,
        locationIds = locationIds.toSet,
        takenRange = takenRange,
        importedRange = importedRange,
        bbox = bbox,
        subset = subset,
        rpp = rpp,
        searchSort = searchSort,
        grouping = grouping,
        cursor = cursor
      )

  def parse(request: Params): Either[String, Scope] =
    val view = request.view
    val triaged = request.triaged
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

    val description = request.describe.map(descriptionOf(request, _)) match
      case Some(Left(message)) => return Left(message)
      case Some(Right(description)) => Some(description)
      case None => None

    val text = request.q.filter(SearchText.parse(_).isDefined)
    val isTrash = view == Const.Search.View.TRASHBIN
    if text.isDefined && isTrash then return Left("Search text does not search the trash")

    val personIds = idsOf(request.personId)
    val locationIds = idsOf(request.locationId)
    if personIds.size > MAX_IDS then return Left(s"A search names at most $MAX_IDS people")
    if locationIds.size > MAX_IDS then return Left(s"A search names at most $MAX_IDS Locations")
    val names = personIds.size > 1 || locationIds.size > 1
    if isTrash && names then return Left("Several people or Locations do not search the trash")

    val matched = request.peopleMatch.map(PeopleMatch.fromApiValue) match
      case None => PeopleMatch.All
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.PEOPLE_MATCH} value")
      case Some(Some(found)) => found

    val takenRange =
      rangeOf(Api.Field.Search.TAKEN_FROM, request.takenFrom, Api.Field.Search.TAKEN_TO, request.takenTo) match
        case Left(message) => return Left(message)
        case Right(range) => range
    val importedRange =
      rangeOf(Api.Field.Search.IMPORTED_FROM, request.importedFrom, Api.Field.Search.IMPORTED_TO, request.importedTo) match
        case Left(message) => return Left(message)
        case Right(range) => range
    val dates = takenRange.isDefined || importedRange.isDefined
    if isTrash && dates then return Left("A date range does not search the trash")

    val box =
      try request.bbox.map(BoundingBox.parse)
      catch case ex: IllegalArgumentException => return Left(s"Invalid bbox: ${ex.getMessage}")

    val triageSubset = request.subset.map(TriageSubset.fromApiValue) match
      case None => None
      case Some(None) => return Left(s"Unknown ${Api.Field.Search.SUBSET} value")
      case Some(found) => found

    // A folder counts whichever it is: the root is told apart where a repository is at hand
    // (`SearchResultsController.isFiltered`)
    val places = Seq(text.isDefined, request.folderId.isDefined, personIds.nonEmpty, locationIds.nonEmpty).count(identity)
    if (dates || names || places > 1) && (request.albumId.isDefined || triageSubset.isDefined) then
      return Left("A search by several filters takes no album and no subset")

    Right(
      Scope(
        params = params,
        text = text,
        folderIds = request.folderId.toSet,
        personIds = personIds,
        albumIds = request.albumId.toSet,
        locationIds = locationIds,
        bbox = box,
        subset = triageSubset,
        peopleMatch = matched,
        takenRange = takenRange,
        importedRange = importedRange,
        description = description
      ))

  /**
   * The stripped description, or a 400's message; its length is counted in UTF-16 units, as a form counts it. Beside a
   * description a search takes its filters and the grid layout, and any other parameter is refused by name. What is taken is what
   * `notTaken` clears below, so a new `Params` field is refused beside a description until it is cleared there too.
   */
  private def descriptionOf(request: Params, written: String): Either[String, String] =
    val description = written.strip
    if description.isEmpty then return Left("A description cannot be blank")
    if description.length > Const.Search.MAX_DESCRIPTION_LENGTH then
      return Left(s"A description has at most ${Const.Search.MAX_DESCRIPTION_LENGTH} characters")

    if request.view == Const.Search.View.TRASHBIN then return Left("A description does not search the trash")

    // Every parameter is a `Params` field named as the request spells it. The layout is always handed in; only the map is refused
    val notTaken = request.copy(
      q = None,
      folderId = None,
      personId = None,
      locationId = None,
      peopleMatch = None,
      takenFrom = None,
      takenTo = None,
      importedFrom = None,
      importedTo = None,
      triaged = None,
      describe = None,
      layout = None
    )
    val refused = notTaken.productElementNames.zip(notTaken.productIterator).collect { case (name, Some(_)) => name }.toList ++
      request.layout.filter(_ == Const.Search.Layout.MAP).map(layout => s"${Api.Field.Search.LAYOUT}=$layout")
    Either.cond(refused.isEmpty, description, s"A description takes no ${refused.mkString(", ")}")

  /** An empty segment is no ID, as the browser's store reads it (`idsOf` in js/stores/search-params.js) */
  private def idsOf(value: Option[String]): List[String] =
    value.toList.flatMap(_.split(",")).filter(_.nonEmpty).distinct

  /**
   * Exactly `2024-06-01`, a day the calendar has. `LocalDate.parse` would also read a longer, signed year (`+10000-01-01`), whose
   * instants SQLite's text does not order with the others.
   */
  private val ISO_DAY = new DateTimeFormatterBuilder()
    .appendValue(ChronoField.YEAR, 4)
    .appendLiteral('-')
    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
    .appendLiteral('-')
    .appendValue(ChronoField.DAY_OF_MONTH, 2)
    .toFormatter
    .withChronology(IsoChronology.INSTANCE)
    .withResolverStyle(ResolverStyle.STRICT)

  /** None when both ends are left out; a bad day or a reversed range is a 400's message naming the parameters */
  private def rangeOf(
      fromName: String,
      from: Option[String],
      toName: String,
      to: Option[String]): Either[String, Option[DateRange]] =
    def day(name: String, value: Option[String]): Either[String, Option[LocalDate]] =
      try Right(value.map(LocalDate.parse(_, ISO_DAY)))
      catch case _: DateTimeParseException => Left(s"$name must be a date, as 2024-06-01")

    for
      first <- day(fromName, from)
      last <- day(toName, to)
      range <-
        try Right(Option.when(first.isDefined || last.isDefined)(DateRange(first, last)))
        catch case ex: IllegalArgumentException => Left(s"Invalid $fromName and $toName: ${ex.getMessage}")
    yield range
