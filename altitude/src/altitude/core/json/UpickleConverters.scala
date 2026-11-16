package altitude.core.json

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import upickle.default.ReadWriter
import upickle.default.readwriter

object UpickleConverters:
  implicit val localDateTimeRW: ReadWriter[LocalDateTime] =
    readwriter[String].bimap[LocalDateTime](
      dt => dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
      str => LocalDateTime.parse(str, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    )
