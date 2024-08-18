package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.RequestContext
import software.altitude.core.models.Asset
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.service.FaceRecognitionService
import software.altitude.core.util.MurmurHash
import software.altitude.core.{Const => C}

import java.sql.PreparedStatement

abstract class FaceDao(override val config: Config) extends BaseDao with software.altitude.core.dao.FaceDao {

  override final val tableName = "face"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val liteModel = makeLiteModel(rec)

    // the lite model, used for caching in memory, does not have image data by default
    val model = liteModel ++ Json.obj(
      C.Face.IMAGE -> rec(C.Face.IMAGE).asInstanceOf[Array[Byte]],
      C.Face.DISPLAY_IMAGE -> rec(C.Face.DISPLAY_IMAGE).asInstanceOf[Array[Byte]],
      C.Face.ALIGNED_IMAGE -> rec(C.Face.ALIGNED_IMAGE).asInstanceOf[Array[Byte]],
      C.Face.ALIGNED_IMAGE_GS -> rec(C.Face.ALIGNED_IMAGE_GS).asInstanceOf[Array[Byte]]
    )

    model
  }

  private def makeLiteModel(rec: Map[String, AnyRef]): JsObject = {
    val embeddingsArray = getFloatListByJsonKey(rec(C.Face.EMBEDDINGS).asInstanceOf[String], C.Face.EMBEDDINGS)
    val featuresArray = getFloatListByJsonKey(rec(C.Face.FEATURES).asInstanceOf[String], C.Face.FEATURES)

    val model = Face(
      id = Option(rec(C.Base.ID).asInstanceOf[String]),
      x1 = rec(C.Face.X1).asInstanceOf[Int],
      y1 = rec(C.Face.Y1).asInstanceOf[Int],
      width = rec(C.Face.WIDTH).asInstanceOf[Int],
      height = rec(C.Face.HEIGHT).asInstanceOf[Int],
      assetId = Option(rec(C.Face.ASSET_ID).asInstanceOf[String]),
      personId = Option(rec(C.Face.PERSON_ID).asInstanceOf[String]),
      personLabel = Option(rec(C.Face.PERSON_LABEL).getClass match {
        case c if c == classOf[java.lang.Integer] => rec(C.Face.PERSON_LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(C.Face.PERSON_LABEL).asInstanceOf[Long].toInt
      }),
      detectionScore = rec(C.Face.DETECTION_SCORE).asInstanceOf[Double],
      embeddings = embeddingsArray.toArray,
      features = featuresArray.toArray,
      image = new Array[Byte](0),
      displayImage = new Array[Byte](0),
      alignedImage = new Array[Byte](0),
      alignedImageGs = new Array[Byte](0)
    )

    model
  }

  override def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject = {
    val face: Face = jsonIn: Face

    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO $tableName (${C.Face.ID}, ${C.Base.REPO_ID}, ${C.Face.X1}, ${C.Face.Y1}, ${C.Face.WIDTH}, ${C.Face.HEIGHT},
                                ${C.Face.ASSET_ID}, ${C.Face.PERSON_ID}, ${C.Face.PERSON_LABEL}, ${C.Face.DETECTION_SCORE},
                                ${C.Face.EMBEDDINGS}, ${C.Face.FEATURES}, ${C.Face.IMAGE}, ${C.Face.DISPLAY_IMAGE},
                                ${C.Face.ALIGNED_IMAGE}, ${C.Face.ALIGNED_IMAGE_GS}, ${C.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    val conn = RequestContext.getConn

    /**
     * Embeddings and Features are an array of floats, and even though Postgres supports float array natively,
     * there is really no value in creating a separate DAO hierarchy just for that.
     * Both DBs store this data as JSON in a TEXT field - faces are preloaded into memory anyway.
     *
     * Why not as a CSV? Casting floats into Strings and back is a pain, and JSON is more pliable for this, without
     * worrying about messing with precision. This just works.
     */
    val embeddingsArrayJson = Json.obj(
      C.Face.EMBEDDINGS -> Json.toJson(face.embeddings),
    )

    val featuresArrayJson = Json.obj(
      C.Face.FEATURES -> Json.toJson(face.features),
    )

    val checksum = MurmurHash.hash32(face.image)

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
    preparedStatement.setBytes(13, face.image)
    preparedStatement.setBytes(14, face.displayImage)
    preparedStatement.setBytes(15, face.alignedImage)
    preparedStatement.setBytes(16, face.alignedImageGs)
    preparedStatement.setInt(17, checksum)
    preparedStatement.execute()

    jsonIn ++ Json.obj(
      C.Base.ID -> id,
      C.Face.ASSET_ID -> asset.id.get,
      C.Face.PERSON_ID -> person.id.get,
      C.Face.PERSON_LABEL -> person.label,
    )
  }

  /**
   * Get faces for all people in this repo, but only the top X faces per person.
   * We use those to brute-force compare a new face, if there is no machine-learned hit,
   * and to verify ML hits as well.
   */
  def getAllForCache: List[Face] = {
    val selectColumns = List(C.Face.ID, C.Base.REPO_ID, C.Face.X1, C.Face.Y1, C.Face.WIDTH, C.Face.HEIGHT,
      C.Face.ASSET_ID, C.Face.PERSON_ID, C.Face.PERSON_LABEL, C.Face.DETECTION_SCORE, C.Face.EMBEDDINGS,
      C.Face.FEATURES)

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
      sql, List(
        RequestContext.getRepository.persistedId,
        RequestContext.getRepository.persistedId,
        FaceRecognitionService.MAX_COMPARISONS_PER_PERSON))

    recs.map(makeLiteModel)
  }
}
