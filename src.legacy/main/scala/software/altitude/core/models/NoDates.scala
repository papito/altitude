package software.altitude.core.models

import java.time.LocalDateTime

trait NoDates {
  val createdAt: Option[LocalDateTime] = None
  val updatedAt: Option[LocalDateTime] = None
}
