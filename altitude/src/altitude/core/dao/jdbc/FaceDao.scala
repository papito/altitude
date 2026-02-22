package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import com.typesafe.config.Config

import java.sql.{PreparedStatement, SQLException}
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import scala.language.implicitConversions

abstract class FaceDao(override val config: Config) extends BaseDao with altitude.core.dao.FaceDao:

  final override val tableName = "face"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject =
//    val embeddingsArray = getFloatListByJsonKey(rec(FieldConst.Face.EMBEDDINGS).asInstanceOf[String], FieldConst.Face.EMBEDDINGS)
//    val featuresArray = getFloatListByJsonKey(rec(FieldConst.Face.FEATURES).asInstanceOf[String], FieldConst.Face.FEATURES)

    Face(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      x1 = rec(FieldConst.Face.X1).asInstanceOf[Int],
      y1 = rec(FieldConst.Face.Y1).asInstanceOf[Int],
      width = rec(FieldConst.Face.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Face.HEIGHT).asInstanceOf[Int],
      assetId = Option(rec(FieldConst.Face.ASSET_ID).asInstanceOf[String]),
      personId = Option(rec(FieldConst.Face.PERSON_ID).asInstanceOf[String]),
      detectionScore = rec(FieldConst.Face.DETECTION_SCORE).asInstanceOf[Double],
      embeddings = Array[Float](), //embeddingsArray.toArray,
      features = Array[Float](), // featuresArray.toArray,
      checksum = rec(FieldConst.Face.CHECKSUM).asInstanceOf[Int]
    ).toJson

  private def toVectorAsF32Arg(values: Array[Float]): String =
    // Must match: vector_as_f32('[0.3, 1.0, ...]')
    // We bind just the bracketed list as a String parameter.
    values.mkString("[", ", ", "]")

  override def add(jsonIn: JsObject, asset: Asset, person: Person): JsObject =
    val face: Face = jsonIn: Face

    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO face (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                          ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.DETECTION_SCORE},
                          ${FieldConst.Face.EMBEDDINGS}, ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, vector_as_f32(?), vector_as_f32(?), ?)
    """

    val conn = RequestContext.getConn
    txManager.loadVectorExtension(conn)

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
    preparedStatement.setString(10, toVectorAsF32Arg(face.embeddings))
    preparedStatement.setString(11, toVectorAsF32Arg(face.features))
    preparedStatement.setInt(12, face.checksum)

    try {
      preparedStatement.execute()
    }
    catch {
      case e: SQLException =>
        println(s"Error inserting face into database: ${e.getMessage}")
    }

    jsonIn ++ Json.obj(
      FieldConst.ID -> id,
      FieldConst.Face.ASSET_ID -> asset.id.get,
      FieldConst.Face.PERSON_ID -> person.id.get,
    )

  def getAssetFaces(assetId: String): List[Face] =
    val sql = """
        SELECT face.*
          FROM face, person
         WHERE face.repository_id = ?
           AND face.asset_id = ?
           AND face.person_id = person.id
           AND person.is_hidden = FALSE
           AND person.is_bad_match = FALSE
      """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId))

    recs.map(makeModel)

  def searchClosestFaceMatches(features: Array[Float]): Unit =
    val conn = RequestContext.getConn
    txManager.loadVectorExtension(conn)

    // FIXME: filter by repository_id
    val sql =
      """
      SELECT rowid, distance
      FROM vector_full_scan('face', 'features', vector_as_f32(?), ?);
    """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(toVectorAsF32Arg(features), 5))
    println(s"Found ${recs.size} face matches")
    for (rec <- recs) {
      val faceId = rec("rowid").asInstanceOf[Int]
      val distance = rec("distance").asInstanceOf[Double]

      println(s"Found face match: faceId=$faceId, distance=$distance")
    }
