package altitude.core.util

import java.time.LocalDate

/**
 * A range of whole calendar days that narrows a search by Date Taken or by Date Imported: both ends are included, and either may
 * be left open. Which calendar a day belongs to is the filter's to say: the camera's wall clock for Date Taken, the server's time
 * zone for Date Imported.
 */
case class DateRange(from: Option[LocalDate], to: Option[LocalDate]):

  if from.isEmpty && to.isEmpty then throw IllegalArgumentException("A date range needs a first or a last day")
  if from.zip(to).exists((first, last) => first.isAfter(last)) then
    throw IllegalArgumentException("A date range's first day must not be after its last")

  /**
   * The range as the results title shows it (`1 Jun – 30 Jun 2024`, `28 Dec 2023 – 3 Jan 2024`, `from 8 Jun 2024`). It must agree
   * with `rangeLabel` in `js/alpine/components/date-range.js`, which spells months from its own English table, so neither side
   * depends on a locale's abbreviations.
   */
  def label: String = (from, to) match
    case (Some(first), None) => s"from ${Util.humanReadableShortDate(first)}"
    case (None, Some(last)) => s"until ${Util.humanReadableShortDate(last)}"
    case (Some(first), Some(last)) if first == last => Util.humanReadableShortDate(first)
    case (Some(first), Some(last)) =>
      val firstDay =
        if first.getYear == last.getYear then Util.humanReadableDayAndMonth(first) else Util.humanReadableShortDate(first)
      s"$firstDay – ${Util.humanReadableShortDate(last)}"
    // The constructor refuses a range with neither end
    case (None, None) => ""
