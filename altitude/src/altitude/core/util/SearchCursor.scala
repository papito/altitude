package altitude.core.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64

import altitude.core.Api
import altitude.core.SearchCursorException
import altitude.core.util.Query.QueryParam

/**
 * Where a search continues from, never a page number: the last returned image's position and a fingerprint of its search. To the
 * client it is an opaque, versioned token that supplies only a position: every request re-applies authorization and every filter,
 * and the values are bound, never inlined. The page size is not part of it, so a continuation may change it.
 *
 * `key` and `groupId` are the group's (`SearchGroupKey.cursorKey`, `cursorGroupId`); a flat search sorted by capture time is
 * ordered by day first, so its `key` is the image's ISO day. `secondSortValue` is the Relevance sort's capture time,
 * [[SortValue.Null]] when there is none. A Describe search in likeness order is positioned by cosine distance, a
 * [[SortValue.Real]]; sorted or grouped, it is read in two parts, and the fingerprint names the part.
 */
case class SearchCursor(
    key: Option[String],
    groupId: Option[String],
    sortValue: SortValue,
    id: String,
    scope: String,
    secondSortValue: Option[SortValue] = None):

  def encode: String =
    val json = ujson.Obj(
      "v" -> SearchCursor.VERSION,
      "k" -> key.map(ujson.Str.apply).getOrElse(ujson.Null),
      "g" -> groupId.map(ujson.Str.apply).getOrElse(ujson.Null),
      "s" -> SearchCursor.sortValueToJson(sortValue),
      "s2" -> secondSortValue.map(SearchCursor.sortValueToJson).getOrElse(ujson.Null),
      "i" -> id,
      "f" -> scope
    )
    Base64.getUrlEncoder.withoutPadding.encodeToString(ujson.write(json).getBytes(StandardCharsets.UTF_8))

  /** A cursor continues only the search it was issued for */
  def requireScope(currentScope: String): Unit =
    if scope != currentScope then throw SearchCursorException("The cursor does not belong to this search")

  /** A cursor without it cannot continue a Relevance-sorted search */
  def requireSecondSortValue: SortValue =
    secondSortValue.getOrElse(throw SearchCursorException("Malformed cursor"))

object SearchCursor:
  private val VERSION = 6

  def decode(token: String): SearchCursor =
    try
      val json = ujson.read(new String(Base64.getUrlDecoder.decode(token), StandardCharsets.UTF_8))
      if json("v").num.toInt != VERSION then throw SearchCursorException("Unsupported cursor version")
      SearchCursor(
        key = json("k").strOpt,
        groupId = json("g").strOpt,
        sortValue = sortValueFromJson(json("s")),
        id = json("i").str,
        scope = json("f").str,
        secondSortValue = Option.unless(json("s2").isNull)(sortValueFromJson(json("s2")))
      )
    catch
      case ex: SearchCursorException => throw ex
      case _: Exception => throw SearchCursorException("Malformed cursor")

  /**
   * The filters as requested: a folder filter as given, not its current descendants, and a date range as its days, not the
   * instants a Date Imported day spans. Not a secret: it only tells searches apart.
   */
  def scopeFingerprint(query: SearchQuery, repositoryId: String, dbEngine: String): String =
    def canonical(value: Any): String = value match
      case param: QueryParam => s"${param.paramType}:${param.negate}:${param.values.map(_.toString).toList.sorted.mkString(",")}"
      case other => other.toString

    def sortedPairs(values: Map[String, Any]): String =
      values.toList.map { case (key, value) => s"$key=${canonical(value)}" }.sorted.mkString(";")

    fingerprintOf(
      dbEngine,
      repositoryId,
      query.text.getOrElse(""),
      sortedPairs(query.params),
      sortedPairs(query.metadataFilters),
      query.folderIds.toList.sorted.mkString(","),
      query.personIds.toList.sorted.mkString(","),
      query.peopleMatch.apiValue,
      query.albumIds.toList.sorted.mkString(","),
      query.locationIds.toList.sorted.mkString(","),
      query.takenRange.map(_.toString).getOrElse(""),
      query.importedRange.map(_.toString).getOrElse(""),
      query.bbox.map(_.toString).getOrElse(""),
      query.subset.fold("")(_.apiValue),
      query.grouping.fold("")(_.by.apiValue),
      query.grouping.fold("")(_.direction.id.toString),
      query.searchSort.headOption.fold("")(_.field),
      query.searchSort.headOption.fold("")(_.direction.id.toString)
    )

  /**
   * Marked as a description's, so the same filters without the description have another fingerprint, whatever their Search text.
   * A cursor of a sorted or grouped Describe search continues one of its two parts, so the `part` is in it too.
   */
  def describeFingerprint(
      description: String,
      query: SearchQuery,
      repositoryId: String,
      dbEngine: String,
      part: Option[DescribedPart] = None): String =
    fingerprintOf(
      Seq(Api.Field.Search.DESCRIBE, description) ++ part.map(_.toString) :+ scopeFingerprint(query, repositoryId, dbEngine)*)

  /** The first 16 bytes of the SHA-256, in hex */
  private def fingerprintOf(parts: String*): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(parts.mkString("|").getBytes(StandardCharsets.UTF_8))
      .take(16)
      .map("%02x".format(_))
      .mkString

  private def sortValueToJson(value: SortValue): ujson.Obj = value match
    case SortValue.Text(text) => ujson.Obj("t" -> "text", "v" -> text)
    case SortValue.Num(number) => ujson.Obj("t" -> "num", "v" -> number.toString)
    // A double's text reads back as the same double
    case SortValue.Real(number) => ujson.Obj("t" -> "real", "v" -> number.toString)
    case SortValue.LocalTimestamp(dateTime) => ujson.Obj("t" -> "ts", "v" -> dateTime.toString)
    case SortValue.UtcInstant(instant) => ujson.Obj("t" -> "tsz", "v" -> instant.toString)
    case SortValue.Null => ujson.Obj("t" -> "null")

  private def sortValueFromJson(json: ujson.Value): SortValue = json("t").str match
    case "text" => SortValue.Text(json("v").str)
    case "num" => SortValue.Num(json("v").str.toLong)
    case "real" => SortValue.Real(json("v").str.toDouble)
    case "ts" => SortValue.LocalTimestamp(LocalDateTime.parse(json("v").str))
    case "tsz" => SortValue.UtcInstant(OffsetDateTime.parse(json("v").str))
    case "null" => SortValue.Null
    case other => throw SearchCursorException(s"Unknown cursor value type: $other")
