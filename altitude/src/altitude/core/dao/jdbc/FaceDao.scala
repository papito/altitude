package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.FaceRow
import altitude.core.models.Face

abstract class FaceDao(override val config: Config) extends BaseDao[Face] with altitude.core.dao.FaceDao:

  def searchClosestFaceMatches(features: Array[Float]): List[Face]

  final override val tableName = "face"

  final override type Row[T[_]] = FaceRow[T]
  final override protected def table: Table[Row] = FaceRow

  // The feature vector is never carried back out of the database, so the row class does not even select it
  override protected def toModel(row: FaceRow[Sc]): Face =
    Face(
      id = Option(row.id),
      x1 = row.x1,
      y1 = row.y1,
      width = row.width,
      height = row.height,
      assetId = Option(row.assetId),
      personId = Option(row.personId),
      detectionScore = row.detectionScore,
      features = Array[Float](),
      checksum = row.checksum,
      quality = row.quality,
      isEnrolled = row.isEnrolled,
      frameTimeMs = row.frameTimeMs
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Face =
    Face(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      x1 = rec(FieldConst.Face.X1).asInstanceOf[Int],
      y1 = rec(FieldConst.Face.Y1).asInstanceOf[Int],
      width = rec(FieldConst.Face.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Face.HEIGHT).asInstanceOf[Int],
      assetId = Option(rec(FieldConst.Face.ASSET_ID).asInstanceOf[String]),
      personId = Option(rec(FieldConst.Face.PERSON_ID).asInstanceOf[String]),
      detectionScore = rec(FieldConst.Face.DETECTION_SCORE).asInstanceOf[Double],
      features = Array[Float](),
      checksum = rec(FieldConst.Face.CHECKSUM).asInstanceOf[Int],
      quality = rec(FieldConst.Face.QUALITY).asInstanceOf[Double],
      isEnrolled = getBooleanField(rec(FieldConst.Face.IS_ENROLLED)),
      frameTimeMs = getLongField(rec(FieldConst.Face.FRAME_TIME_MS))
    )

  // Reads `face_02` backwards and stops at the limit: no count of the person's faces and no sort of them
  override def getTopFaces(personId: String, limit: Int): List[Face] =
    import dialect.*

    val top = FaceRow.select.filter(_.personId `=` personId).sortBy(_.id).asc.sortBy(_.detectionScore).desc.take(limit)
    Db.read(dialect)(_.run(top)).map(toModel).toList

  // Reads `face_01`, which leads with the asset
  override def getAllAssetFaces(assetId: String): List[Face] =
    import dialect.*

    val repositoryId = RequestContext.getRepository.persistedId
    val faces = FaceRow.select.filter(face => face.assetId `=` assetId && face.repositoryId `=` repositoryId)
    Db.read(dialect)(_.run(faces)).map(toModel).toList

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
