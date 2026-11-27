package altitude.core.dao.sqlite

import com.typesafe.config.Config

import altitude.core.RequestContext
import altitude.core.models.Face
import altitude.core.models.FaceMatch

class FaceDao(override val config: Config) extends altitude.core.dao.jdbc.FaceDao(config) with SqliteOverrides:

  private def toVectorAsF32Arg(values: Array[Float]): String =
    // The bracketed list `vector_as_f32(?)` parses, e.g. '[0.3, 1.0, ...]', bound as a String
    values.mkString("[", ", ", "]")

  def searchClosestFaceMatches(features: Array[Float], matchCount: Int, distanceThreshold: Double): List[FaceMatch] =
    nearest(features, Nil, matchCount, distanceThreshold).map(faceMatchOf)

  override def searchNearestFacesOfOthers(
      features: Array[Float],
      excludedPersonId: String,
      limit: Int,
      distanceThreshold: Double): List[Face] =
    nearest(features, List(excludedPersonId), limit, distanceThreshold).map(makeModel)

  /**
   * The nearest Faces to a vector by an exact scan of every Face, filtered afterwards. `excludedPersonId` holds at most one
   * Person, whose Faces are left out.
   */
  private def nearest(
      features: Array[Float],
      excludedPersonId: List[String],
      limit: Int,
      distanceThreshold: Double): List[Map[String, AnyRef]] =
    val personFilter = if excludedPersonId.nonEmpty then "AND face.person_id <> ?" else ""

    val sql =
      s"""
      SELECT
        v.rowid,
        row_number() OVER (ORDER BY v.distance) AS rank_number,
        v.distance AS ${altitude.core.dao.jdbc.FaceDao.DISTANCE},
        face.*
      FROM vector_full_scan('face', 'features', vector_as_f32(?)) AS v
      JOIN face ON face.rowid = v.rowid
      JOIN person ON person.id = face.person_id
      WHERE face.repository_id = ?
        AND face.is_enrolled = TRUE
        AND person.is_bad_match = FALSE
        AND person.is_deleted = FALSE
        $personFilter
        AND v.distance < ?
      ORDER BY v.distance
      LIMIT ?;
   """

    val binds = List(toVectorAsF32Arg(features), RequestContext.getRepository.persistedId) ++ excludedPersonId ++
      List(distanceThreshold, limit)

    manyBySqlQuery(sql, binds)
