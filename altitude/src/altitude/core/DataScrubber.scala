package altitude.core

/** Trims and lower-cases the named string fields of an API JSON payload */
case class DataScrubber(trim: List[String] = List(), lower: List[String] = List()):

  /** Scrubs the object in place and returns the same object */
  def scrub(json: ujson.Obj): ujson.Obj =
    doTrim(json)
    doLower(json)
    json

  private def doTrim(json: ujson.Obj): Unit =
    trim.foreach {
      field =>
        json.obj.get(field).flatMap(v => v.strOpt).filter(_.nonEmpty) match
          case Some(v) => json(field) = v.trim
          case None =>
    }

  private def doLower(json: ujson.Obj): Unit =
    lower.foreach {
      field =>
        json.obj.get(field).flatMap(v => v.strOpt).filter(_.nonEmpty) match
          case Some(v) => json(field) = v.toLowerCase
          case None =>
    }
