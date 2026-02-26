package altitude.core.dao.jdbc

import altitude.core.actors.FaceRecManagerActor
import altitude.core.actors.FaceRecModelActor.FacePrediction
import altitude.core.{App, FieldConst, RequestContext}
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import com.typesafe.config.Config

import java.sql.PreparedStatement
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory


import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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
                          ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, vector_as_f32(?), ?)
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
    preparedStatement.setString(10, toVectorAsF32Arg(face.features))
    preparedStatement.setInt(11, face.checksum)

    preparedStatement.execute()

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

  def searchClosestFaceMatches(features: Array[Float]): List[Face] =
    val conn = RequestContext.getConn

    val sql =
      """
      SELECT
        v.rowid,
        row_number() OVER (ORDER BY v.distance) AS rank_number,
        v.distance,
        face.*
      FROM vector_full_scan('face', 'features', vector_as_f32(?)) AS v
      JOIN face ON face.rowid = v.rowid
      WHERE face.repository_id = ?
        AND v.distance < 0.49
      ORDER BY v.distance
      LIMIT 1;
   """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(toVectorAsF32Arg(features), RequestContext.getRepository.persistedId))

    recs.map(makeModel)
