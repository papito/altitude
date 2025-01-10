package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.PreparedStatement
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models.Asset
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.service.FaceRecognitionService

abstract class FaceDao(override val config: Config) extends BaseDao with software.altitude.core.dao.FaceDao {

  final override val tableName = "face"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val embeddingsArray = getFloatListByJsonKey(rec(FieldConst.Face.EMBEDDINGS).asInstanceOf[String], FieldConst.Face.EMBEDDINGS)
    val featuresArray = getFloatListByJsonKey(rec(FieldConst.Face.FEATURES).asInstanceOf[String], FieldConst.Face.FEATURES)

    val model = Face(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      x1 = rec(FieldConst.Face.X1).asInstanceOf[Int],
      y1 = rec(FieldConst.Face.Y1).asInstanceOf[Int],
      width = rec(FieldConst.Face.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Face.HEIGHT).asInstanceOf[Int],
      assetId = Option(rec(FieldConst.Face.ASSET_ID).asInstanceOf[String]),
      personId = Option(rec(FieldConst.Face.PERSON_ID).asInstanceOf[String]),
      personLabel = Option(rec(FieldConst.Face.PERSON_LABEL).getClass match {
        case c if c == classOf[java.lang.Integer] => rec(FieldConst.Face.PERSON_LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(FieldConst.Face.PERSON_LABEL).asInstanceOf[Long].toInt
      }),
      detectionScore = rec(FieldConst.Face.DETECTION_SCORE).asInstanceOf[Double],
      embeddings = embeddingsArray.toArray,
      features = featuresArray.toArray,
      checksum = rec(FieldConst.Face.CHECKSUM).asInstanceOf[Int]
    )

    model
  }

  override def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject = {
    val face: Face = jsonIn: Face

    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                                ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.PERSON_LABEL}, ${FieldConst.Face.DETECTION_SCORE},
                                ${FieldConst.Face.EMBEDDINGS}, ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    val conn = RequestContext.getConn

    /**
     * Embeddings and Features are an array of floats, and even though Postgres supports float array natively, there is really no
     * value in creating a separate DAO hierarchy just for that.
     *
     * Both DBs store this data as JSON in a TEXT field - faces are preloaded into memory anyway.
     *
     * Why not as a CSV? Casting floats into Strings and back is a pain, and JSON is more pliable for this, without worrying about
     * messing with precision. This just works.
     */
    val embeddingsArrayJson = Json.obj(
      FieldConst.Face.EMBEDDINGS -> Json.toJson(face.embeddings)
    )

    val featuresArrayJson = Json.obj(
      FieldConst.Face.FEATURES -> Json.toJson(face.features)
    )

    val preparedStatement: PreparedStatement = conn.prepareStatement(sql)
    preparedStatement.setString(1, id)
    preparedStatement.setString(2, RequestContext.getRepository.persistedId)
    preparedStatement.setInt(3, face.x1)
    preparedStatement.setInt(4, face.y1)
    preparedStatement.setInt(5, face.width)
    preparedStatement.setInt(6, face.height)
    preparedStatement.setString(7, asset.persistedId)
    preparedStatement.setString(8, person.persistedId)
    preparedStatement.setInt(9, person.label)
    preparedStatement.setDouble(10, face.detectionScore)
    preparedStatement.setString(11, embeddingsArrayJson.toString())
    preparedStatement.setString(12, featuresArrayJson.toString())
    preparedStatement.setInt(13, face.checksum)
    preparedStatement.execute()

    jsonIn ++ Json.obj(
      FieldConst.ID -> id,
      FieldConst.Face.ASSET_ID -> asset.id.get,
      FieldConst.Face.PERSON_ID -> person.id.get,
      FieldConst.Face.PERSON_LABEL -> person.label
    )
  }

  /**
   * Get faces for all people in this repo, but only the top X faces per person. We use those to brute-force compare a new face,
   * if there is no machine-learned hit, and to verify ML hits as well.
   */
  def getAllForCache: List[Face] = {
    val selectColumns = List(
      FieldConst.ID,
      FieldConst.REPO_ID,
      FieldConst.Face.X1,
      FieldConst.Face.Y1,
      FieldConst.Face.WIDTH,
      FieldConst.Face.HEIGHT,
      FieldConst.Face.ASSET_ID,
      FieldConst.Face.PERSON_ID,
      FieldConst.Face.PERSON_LABEL,
      FieldConst.Face.DETECTION_SCORE,
      FieldConst.Face.EMBEDDINGS,
      FieldConst.Face.FEATURES,
      FieldConst.Face.CHECKSUM
    )

    val sql = s"""
       SELECT ${selectColumns.mkString(", ")}
         FROM (
               SELECT ROW_NUMBER()
                 OVER (PARTITION BY person_label ORDER BY detection_score DESC)
                   AS r_num, ${selectColumns.map("sub_face." + _).mkString(", ")}
                 FROM face AS sub_face
                 WHERE repository_id = ?) face
         WHERE repository_id =?
           AND face.r_num <= ?
        """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(
      sql,
      List(
        RequestContext.getRepository.persistedId,
        RequestContext.getRepository.persistedId,
        FaceRecognitionService.MAX_COMPARISONS_PER_PERSON))

    recs.map(makeModel)
  }

  def getAllForTraining: List[Face] = {
    val sql = s"""
        SELECT ${FieldConst.ID}, ${FieldConst.Face.PERSON_LABEL}, ${FieldConst.REPO_ID}
          FROM $tableName
         WHERE repository_id = ?
      """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    recs.map(
      rec => {
        Face(
          id = Some(rec(FieldConst.ID).asInstanceOf[String]),
          personLabel = rec(FieldConst.Face.PERSON_LABEL).getClass match {
            case c if c == classOf[java.lang.Integer] => Some(rec(FieldConst.Face.PERSON_LABEL).asInstanceOf[Int])
            case c if c == classOf[java.lang.Long] => Some(rec(FieldConst.Face.PERSON_LABEL).asInstanceOf[Long].toInt)
          },
          checksum = 0,
          detectionScore = 0,
          embeddings = Array.emptyFloatArray,
          features = Array.emptyFloatArray,
          x1 = 0,
          y1 = 0,
          width = 0,
          height = 0
        )
      })
  }
}
