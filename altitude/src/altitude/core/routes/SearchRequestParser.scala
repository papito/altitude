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
 * refused. It searches by the panel's filters alone, so what only the panel combines (a date range, several people, several
 * Locations, or more than one of the Search text, a folder, a person and a Location) is refused with an album or a subset.
 *
 * A Describe search (`describe`) is asked for by a description alone, the words that say what a photo shows: at most
 * [[altitude.core.Const.Search.MAX_DESCRIPTION_LENGTH]] characters once the white space around it is dropped, and not blank. It
 * searches the repository view, with the triaged assets when `triaged=true`, and takes no Search text, no filter, no sort, no
 * grouping and no map layout: its results are the still images that look most like the description, most alike first, on the
 * grid. Only the results route reads a description; the other routes that parse a scope never hand one in.
 */
object SearchRequestParser:

  /** The most people, and the most Locations, one search names */
  val MAX_IDS = 10

  /**
   * A search's own parameters as a request spells them, each under its name: what [[parse]] reads into a [[Scope]]. A route
   * builds one from its query parameters, by name, and hands it on whole, so no two of them can change places on the way.
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
      // The description of a Describe search, which stands alone
      describe: Option[String] = None,
      // How the request orders, groups and lays out the results, as it spells them: a route that takes a description hands them
      // on, since a Describe search has one order and one layout and names neither
      sort: Option[String] = None,
      groupBy: Option[String] = None,
      groupDirection: Option[String] = None,
      layout: Option[String] = None)

  /**
   * A search's scope as read: the people and the Locations are in the order the request names them, each once. A Describe
   * search's scope is its `description`, as written but for the white space around it, with the view's flags and nothing else.
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
      // How several people combine, all of them unless the request says any, and the Date Taken and Date Imported ranges
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

    request.describe match
      case Some(written) => return describeScope(request, written, params)
      case None =>

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

    // What only the advanced search panel combines, which replaces the album and the subset of the search before it. A folder
    // counts whichever it is: the root is told apart where a repository is at hand (`SearchResultsController.isFiltered`)
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
        importedRange = importedRange
      ))

  /**
   * The parameters a description takes beside it: the triage filter, the layout, of which the grid alone is taken
   * ([[describeScope]]), and how several people combine, which says nothing with no person
   */
  private val BESIDE_DESCRIPTION =
    Set(Api.Field.Search.DESCRIBE, Api.Field.Search.TRIAGED, Api.Field.Search.LAYOUT, Api.Field.Search.PEOPLE_MATCH)

  /**
   * The scope of a Describe search, whose flags are `params`, or the message of a 400: a description is not blank, has at most
   * `Const.Search.MAX_DESCRIPTION_LENGTH` characters, as a form counts them, searches the repository view alone, and stands
   * alone. Any other parameter of a search beside it is refused, whatever its value, and named by the message: the Search text, a
   * folder, a person, a Location, an album, a Triage subset, a date, a map area, a sort, a grouping or the map layout. What is
   * taken beside it is listed ([[BESIDE_DESCRIPTION]]), so a parameter `Params` gains is refused until it is.
   */
  private def describeScope(request: Params, written: String, params: Map[String, Any]): Either[String, Scope] =
    val description = written.strip
    if description.isEmpty then return Left("A description cannot be blank")
    if description.length > Const.Search.MAX_DESCRIPTION_LENGTH then
      return Left(s"A description has at most ${Const.Search.MAX_DESCRIPTION_LENGTH} characters")

    request.view match
      case Const.Search.View.TRASHBIN => return Left("A description does not search the trash")
      case Const.Search.View.TRIAGE => return Left(s"A description does not search the ${Const.Search.View.TRIAGE} view")
      case _ =>

    // Every parameter is a field of `Params` under the name the request spells it with; the layout is always handed in, and
    // only the map is refused
    val stated = request.productElementNames.zip(request.productIterator).collect { case (name, Some(_)) => name }.toList
    val beside = stated.filterNot(BESIDE_DESCRIPTION) ++
      request.layout.filter(_ == Const.Search.Layout.MAP).map(layout => s"${Api.Field.Search.LAYOUT}=$layout")
    if beside.nonEmpty then return Left(s"A description stands alone, without ${beside.mkString(", ")}")

    Right(
      Scope(
        params = params,
        text = None,
        folderIds = Set.empty,
        personIds = Nil,
        albumIds = Set.empty,
        locationIds = Nil,
        bbox = None,
        subset = None,
        description = Some(description)))

  /**
   * The IDs a comma-separated parameter names, each once, in the order given. An empty segment is no ID, so a parameter left out,
   * an empty one and one of commas alone name nothing, as the browser's store reads them (`idsOf` in js/stores/search-params.js).
   */
  private def idsOf(value: Option[String]): List[String] =
    value.toList.flatMap(_.split(",")).filter(_.nonEmpty).distinct

  /**
   * A day as a search parameter spells it, `2024-06-01`: four digits of year, two of month and two of day, of a day the calendar
   * has. `LocalDate.parse` would also read a longer, signed year (`+10000-01-01`), whose instants SQLite's text does not order
   * with the others.
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

  /**
   * The range of days two date parameters make, none when both are left out. A date that is not an ISO day ([[ISO_DAY]]), and a
   * range whose first day is after its last, is the message of a 400 naming the parameters.
   */
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
