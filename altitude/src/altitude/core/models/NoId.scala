package altitude.core.models

/** For submodels and transient objects not stored as entities of their own */
trait NoId:
  val id: Option[String] = None
