package software.altitude.core.models

/** Trait to use for submodels and transient objects that are not stored in databases as separate entities. */
trait NoId {
  val id: Option[String] = None
}
