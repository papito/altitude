package altitude.core.models

/**
 * What restoring recycled assets did: the assets restored, and the ones left in the trash because an asset with the same content
 * is live again
 */
case class RestoreResult(restored: Set[String], duplicates: Set[String])
