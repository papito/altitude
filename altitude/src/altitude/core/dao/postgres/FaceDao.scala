package altitude.core.dao.postgres

import com.typesafe.config.Config
import java.sql.PreparedStatement
import java.sql.Types

import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.service.FaceDetectionService

object FaceDao:

  /** A Float array as a pgvector literal, e.g. "[0.1,0.2,...]", which pgvector accepts when cast with `?::vector` or `?::halfvec` */
  def vectorLiteral(values: Array[Float]): String = values.mkString("[", ",", "]")

  /**
   * How many of the nearest Faces the vector index is asked for, as a multiple of the matches wanted: the index is approximate
   * and orders by half-precision vectors, so the exact distances are taken over a few more than the answer needs
   */
  private val CANDIDATES_PER_MATCH = 4

  /**
   * The stored Faces nearest a query vector, nearest first, with their exact cosine distance, no further than a threshold.
   *
   * The candidates come from `face_03`, the HNSW index over the enrolled Faces' vectors at half precision, which the inner order
   * and `is_enrolled = TRUE` are written to match. The repository and the bad matches are filtered as the index is read
   * (`TransactionManager.withFaceVector` has the scan go on until the limit is met). The threshold is applied outside, on the
   * full-precision distance: inside, a query with nothing near enough would send the index looking for rows that are not there.
   *
   * Binds: the vector, the repository, the vector, the number of candidates, the vector, the threshold, the number of matches.
   * `nearest.*` rather than `*`: the join would put the person's ID and dates into the row map under the same keys.
   */
  val CLOSEST_MATCHES_SQL: String =
    s"""
      SELECT nearest.*, nearest.features <=> ?::vector AS distance
        FROM (SELECT face.*
                FROM face JOIN person ON person.id = face.person_id
               WHERE face.repository_id = ?
                 AND face.is_enrolled = TRUE
                 AND person.is_bad_match = FALSE
               ORDER BY face.features::halfvec(${FaceDetectionService.EMBEDDING_DIMENSIONS}) <=> ?::halfvec(${FaceDetectionService.EMBEDDING_DIMENSIONS})
               LIMIT ?) AS nearest
       WHERE nearest.features <=> ?::vector < ?
       ORDER BY distance
       LIMIT ?
    """

class FaceDao(override val config: Config) extends altitude.core.dao.jdbc.FaceDao(config) with PostgresOverrides:

  override def add(face: Face, asset: Asset, person: Person): Face =
    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO face (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                          ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.DETECTION_SCORE},
                          ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM}, ${FieldConst.Face.FRAME_TIME_MS},
                          ${FieldConst.Face.QUALITY}, ${FieldConst.Face.IS_ENROLLED})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?)
    """

    val conn = RequestContext.getConn

    val preparedStatement: PreparedStatement = conn.prepareStatement(sql)
    preparedStatement.setString(1, id)
    preparedStatement.setString(2, RequestContext.getRepository.persistedId)
    preparedStatement.setInt(3, face.x1)
    preparedStatement.setInt(4, face.y1)
    preparedStatement.setInt(5, face.width)
    preparedStatement.setInt(6, face.height)
    preparedStatement.setString(7, asset.persistedId)
    preparedStatement.setString(8, person.persistedId)
    preparedStatement.setDouble(9, face.detectionScore)
    preparedStatement.setString(10, FaceDao.vectorLiteral(face.features))
    preparedStatement.setInt(11, face.checksum)
    face.frameTimeMs match
      case Some(frameTimeMs) => preparedStatement.setLong(12, frameTimeMs)
      case None => preparedStatement.setNull(12, Types.BIGINT)
    preparedStatement.setDouble(13, face.quality)
    preparedStatement.setBoolean(14, face.isEnrolled)

    logger.trace(s"Inserting face [$id] for person [${person.persistedId}] in asset [${asset.persistedId}]")
    preparedStatement.execute()

    face.copy(id = Some(id), assetId = asset.id, personId = person.id)

  def searchClosestFaceMatches(features: Array[Float]): List[Face] =
    val vector = FaceDao.vectorLiteral(features)
    val matchCount = config.getInt(Const.Conf.FACE_RECOGNITION_MATCH_COUNT)
    val threshold = config.getDouble(Const.Conf.FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD)

    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(
        FaceDao.CLOSEST_MATCHES_SQL,
        List(
          vector,
          RequestContext.getRepository.persistedId,
          vector,
          matchCount * FaceDao.CANDIDATES_PER_MATCH,
          vector,
          threshold,
          matchCount)
      )

    recs.map(makeModel)
