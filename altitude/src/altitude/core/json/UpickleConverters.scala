package altitude.core.json


import upickle.default.ReadWriter
import upickle.default.ReadWriter.join

import upickle.default.readwriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


object UpickleConverters {
  // Custom ReadWriter for LocalDateTime
  implicit val localDateTimeRW: ReadWriter[LocalDateTime] =
    readwriter[String].bimap[LocalDateTime](
      dt => dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
      str => LocalDateTime.parse(str, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    )
}