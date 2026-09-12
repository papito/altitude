package altitude.core.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64

import altitude.core.SearchCursorException
import altitude.core.util.Query.QueryParam

/**
 * Where a grouped search continues from: the last returned image's position (day, sort value, ID) and a fingerprint of the search
 * the cursor belongs to. It is an opaque, versioned token to the client and supplies only a position: every request re-applies
 * authorization and every filter, and the values are bound, never inlined. The page size is not part of it, so a continuation may
 * ask for a different one.
 */
case class SearchCursor(day: Option[LocalDate], sortValue: SortValue, id: String, scope: String):

  def encode: String =
    val json = ujson.Obj(
      "v" -> SearchCursor.VERSION,
      "d" -> day.map(d => ujson.Str(d.toString)).getOrElse(ujson.Null),
      "s" -> SearchCursor.sortValueToJson(sortValue),
      "i" -> id,
      "f" -> scope
    )
    Base64.getUrlEncoder.withoutPadding.encodeToString(ujson.write(json).getBytes(StandardCharsets.UTF_8))

  /** A cursor continues only the search it was issued for */
  def requireScope(currentScope: String): Unit =
    if scope != currentScope then throw SearchCursorException("The cursor does not belong to this search")

object SearchCursor:
  private val VERSION = 3

  def decode(token: String): SearchCursor =
    try
      val json = ujson.read(new String(Base64.getUrlDecoder.decode(token), StandardCharsets.UTF_8))
      if json("v").num.toInt != VERSION then throw SearchCursorException("Unsupported cursor version")
      SearchCursor(
        day = json("d").strOpt.map(LocalDate.parse),
        sortValue = sortValueFromJson(json("s")),
        id = json("i").str,
        scope = json("f").str
      )
    catch
      case ex: SearchCursorException => throw ex
      case _: Exception => throw SearchCursorException("Malformed cursor")

  /**
   * Identifies the search a cursor may continue: the engine, repository, filters as requested (a folder filter as given, not its
   * currently expanded descendants), grouping, ordering. Not a secret: it only tells apart searches.
   */
  def scopeFingerprint(query: SearchQuery, repositoryId: String, dbEngine: String): String =
    def canonical(value: Any): String = value match
      case param: QueryParam => s"${param.paramType}:${param.negate}:${param.values.map(_.toString).toList.sorted.mkString(",")}"
      case other => other.toString

    def sortedPairs(values: Map[String, Any]): String =
      values.toList.map { case (key, value) => s"$key=${canonical(value)}" }.sorted.mkString(";")

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A cursor needs a grouped search"))
    val sort = query.searchSort.head
    val description = List(
      dbEngine,
      repositoryId,
      query.text.getOrElse(""),
      sortedPairs(query.params),
      sortedPairs(query.metadataFilters),
      query.folderIds.toList.sorted.mkString(","),
      query.personIds.toList.sorted.mkString(","),
      query.albumIds.toList.sorted.mkString(","),
      grouping.by.apiValue,
      grouping.direction.id.toString,
      sort.field,
      sort.direction.id.toString
    ).mkString("|")

    MessageDigest
      .getInstance("SHA-256")
      .digest(description.getBytes(StandardCharsets.UTF_8))
      .take(16)
      .map("%02x".format(_))
      .mkString

  private def sortValueToJson(value: SortValue): ujson.Obj = value match
    case SortValue.Text(text) => ujson.Obj("t" -> "text", "v" -> text)
    case SortValue.Num(number) => ujson.Obj("t" -> "num", "v" -> number.toString)
    case SortValue.LocalTimestamp(dateTime) => ujson.Obj("t" -> "ts", "v" -> dateTime.toString)
    case SortValue.UtcInstant(instant) => ujson.Obj("t" -> "tsz", "v" -> instant.toString)
    case SortValue.Null => ujson.Obj("t" -> "null")

  private def sortValueFromJson(json: ujson.Value): SortValue = json("t").str match
    case "text" => SortValue.Text(json("v").str)
    case "num" => SortValue.Num(json("v").str.toLong)
    case "ts" => SortValue.LocalTimestamp(LocalDateTime.parse(json("v").str))
    case "tsz" => SortValue.UtcInstant(OffsetDateTime.parse(json("v").str))
    case "null" => SortValue.Null
    case other => throw SearchCursorException(s"Unknown cursor value type: $other")
