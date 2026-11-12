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
