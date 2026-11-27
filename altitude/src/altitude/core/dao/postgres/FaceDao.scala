package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.RequestContext
import altitude.core.models.Face
import altitude.core.models.FaceMatch
import altitude.core.service.FaceDetectionService

object FaceDao:

  /**
   * Candidates asked of the vector index per match wanted: the index is approximate and orders by half-precision vectors, so
   * exact distances are taken over a few more than the answer needs
   */
  private val CANDIDATES_PER_MATCH = 4

  /**
   * The stored Faces within a threshold of a query vector, nearest first, with their exact cosine distance.
   *
   * The inner order and `is_enrolled = TRUE` match the half-precision HNSW index, and its filters apply as the index is read
   * (`TransactionManager.withFaceVector` keeps the scan going until the limit is met). The threshold is applied outside, on the
   * full-precision distance: inside, a query with nothing near enough would send the index looking for rows that are not there.
   * `nearest.*` rather than `*`: the join would put the person's ID and dates into the row map under the same keys.
   */
  private def closestMatchesSql(excludingPerson: Boolean): String =
    val personFilter = if excludingPerson then "AND face.person_id <> ?" else ""

    s"""
      SELECT nearest.*, nearest.features <=> ?::vector AS ${altitude.core.dao.jdbc.FaceDao.DISTANCE}
        FROM (SELECT face.*
                FROM face JOIN person ON person.id = face.person_id
               WHERE face.repository_id = ?
                 AND face.is_enrolled = TRUE
                 AND person.is_bad_match = FALSE
                 AND person.is_deleted = FALSE
                 $personFilter
               ORDER BY face.features::halfvec(${FaceDetectionService.EMBEDDING_DIMENSIONS}) <=> ?::halfvec(${FaceDetectionService.EMBEDDING_DIMENSIONS})
               LIMIT ?) AS nearest
       WHERE nearest.features <=> ?::vector < ?
       ORDER BY ${altitude.core.dao.jdbc.FaceDao.DISTANCE}
       LIMIT ?
    """

  val CLOSEST_MATCHES_SQL: String = closestMatchesSql(excludingPerson = false)

  val CLOSEST_OF_OTHERS_SQL: String = closestMatchesSql(excludingPerson = true)

class FaceDao(override val config: Config) extends altitude.core.dao.jdbc.FaceDao(config) with PostgresOverrides:

  def searchClosestFaceMatches(features: Array[Float], matchCount: Int, distanceThreshold: Double): List[FaceMatch] =
    nearest(FaceDao.CLOSEST_MATCHES_SQL, features, Nil, matchCount, distanceThreshold).map(faceMatchOf)

  override def searchNearestFacesOfOthers(
      features: Array[Float],
      excludedPersonId: String,
      limit: Int,
      distanceThreshold: Double): List[Face] =
    nearest(FaceDao.CLOSEST_OF_OTHERS_SQL, features, List(excludedPersonId), limit, distanceThreshold).map(makeModel)

  /** Runs one of the two nearest-Faces statements; `filterBinds` are the binds of its filters after the repository's */
  private def nearest(
      sql: String,
      features: Array[Float],
      filterBinds: List[Any],
      limit: Int,
      distanceThreshold: Double): List[Map[String, AnyRef]] =
    val vector = PostgresOverrides.vectorLiteral(features)
    val binds = List(vector, RequestContext.getRepository.persistedId) ++ filterBinds ++
      List(vector, limit * FaceDao.CANDIDATES_PER_MATCH, vector, distanceThreshold, limit)

    manyBySqlQuery(sql, binds)
