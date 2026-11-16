package altitude.core.models

/** `duplicates` stay in the trash because an asset with the same content is live again */
case class RestoreResult(restored: Set[String], duplicates: Set[String])
