package altitude.core.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
   * The range as a filtered search's results title reads it, and as the advanced search panel's date field shows it (`rangeLabel`
   * in js/alpine/components/date-range.js, which this has to agree with): `1 Jun – 30 Jun 2024`, each end with its year when the
   * two differ (`28 Dec 2023 – 3 Jan 2024`), a single day once (`8 Jun 2024`), and an open end as `from 8 Jun 2024` or
   * `until 21 Jun 2024`. The months are British English's short ones, as the browser's `en-GB` names them.
   */
  def label: String = (from, to) match
    case (Some(first), None) => s"from ${first.format(DateRange.dayWithYear)}"
    case (None, Some(last)) => s"until ${last.format(DateRange.dayWithYear)}"
    case (Some(first), Some(last)) if first == last => first.format(DateRange.dayWithYear)
    case (Some(first), Some(last)) =>
      val firstFormat = if first.getYear == last.getYear then DateRange.day else DateRange.dayWithYear
      s"${first.format(firstFormat)} – ${last.format(DateRange.dayWithYear)}"
    // The constructor refuses a range with neither end
    case (None, None) => ""

object DateRange:
  private val day = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
  private val dayWithYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
