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
      frameTimeMs = row.frameTimeMs,
      sharpness = row.sharpness
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
      checksum = rec(FieldConst.Face.CHECKSUM).asInstanceOf[String],
      quality = rec(FieldConst.Face.QUALITY).asInstanceOf[Double],
      isEnrolled = getBooleanField(rec(FieldConst.Face.IS_ENROLLED)),
      frameTimeMs = getLongField(rec(FieldConst.Face.FRAME_TIME_MS)),
      sharpness = getDoubleField(rec(FieldConst.Face.SHARPNESS))
    )

  override def getTopFaces(personId: String, limit: Int): List[Face] = coverCandidates(personId, Set.empty, limit)

  override def getBestCoverCandidate(personId: String, excludedAssetIds: Set[String]): Option[Face] =
    coverCandidates(personId, excludedAssetIds, limit = 1).headOption

  /**
   * The person's Faces a cover can be: on none of `excludedAssetIds` and on no asset marked for purging, by detection score, best
   * first, then by ID, no more than `limit`. They come from `face_02`, which leads with the person and orders them by detection
   * score, so the read stops at the limit, each Face's asset probed by its key.
   */
  private def coverCandidates(personId: String, excludedAssetIds: Set[String], limit: Int): List[Face] =
    val sql = s"""
        SELECT face.*
          FROM face, asset
         WHERE face.repository_id = ?
           AND face.person_id = ?
           AND NOT (face.asset_id $inIdSet)
           AND asset.id = face.asset_id
           AND asset.${FieldConst.Asset.IS_PURGED} = FALSE
         ORDER BY face.detection_score DESC, face.id
         LIMIT ?
      """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, personId, idSet(excludedAssetIds), limit))
      .map(makeModel)

  // Reads `face_01`, which leads with the asset
  override def getAllAssetFaces(assetId: String): List[Face] =
    import dialect.*

    val repositoryId = RequestContext.getRepository.persistedId
    val faces = FaceRow.select.filter(face => face.assetId `=` assetId && face.repositoryId `=` repositoryId)
    Db.read(dialect)(_.run(faces)).map(toModel).toList

  override def hasFaceOfPerson(assetId: String, personId: String): Boolean =
    import dialect.*

    val repositoryId = RequestContext.getRepository.persistedId
    val one = FaceRow.select
      .filter(face => face.assetId `=` assetId && face.repositoryId `=` repositoryId && face.personId `=` personId)
      .map(_.id)
      .take(1)
    Db.read(dialect)(_.run(one)).nonEmpty

  // The one read of the vector column, through the engine's `getVectorField`: pgvector's literal, or the BLOB `vector_as_f32` wrote
  override def getFeatures(faceId: String): Option[Array[Float]] =
    val sql = s"SELECT ${FieldConst.Face.FEATURES} FROM face WHERE ${FieldConst.ID} = ? AND ${FieldConst.REPO_ID} = ?"

    manyBySqlQuery(sql, List(faceId, RequestContext.getRepository.persistedId)).headOption
      .flatMap(rec => getVectorField(rec(FieldConst.Face.FEATURES)))
      .map(embedding => IArray.genericWrapArray(embedding.values).toArray)

  // Each person's Faces come from `face_02`, which leads with the person and carries the asset
  override def getFacesOnSharedVideos(personId: String, otherPersonId: String): List[Face] =
    val sql = s"""
        SELECT face.*
          FROM face, asset
         WHERE face.repository_id = ?
           AND face.person_id IN (?, ?)
           AND asset.id = face.asset_id
           AND asset.${FieldConst.AssetType.MEDIA_TYPE} = ?
           AND face.asset_id IN (SELECT mine.asset_id FROM face mine WHERE mine.person_id = ?)
           AND face.asset_id IN (SELECT theirs.asset_id FROM face theirs WHERE theirs.person_id = ?)
      """

    manyBySqlQuery(
      sql,
      List(RequestContext.getRepository.persistedId, personId, otherPersonId, "video", personId, otherPersonId)
    ).map(makeModel)

  override def getInRepository(faceId: String): Option[Face] =
    import dialect.*

    val repositoryId = RequestContext.getRepository.persistedId
    val one = FaceRow.select.filter(face => face.id `=` faceId && face.repositoryId `=` repositoryId)
    Db.read(dialect)(_.run(one)).headOption.map(toModel)

  def getAssetFaces(assetId: String): List[Face] =
    visibleFaces("= ?", assetId)

  override def getFacesOfAssets(assetIds: Set[String]): List[Face] =
    if assetIds.isEmpty then return List.empty[Face]

    visibleFaces(inIdSet, idSet(assetIds))

  /** The Faces of the people on show, of the assets `assetTest` picks with its one bind value */
  private def visibleFaces(assetTest: String, assetValue: Any): List[Face] =
    val sql = s"""
        SELECT face.*
          FROM face, person
         WHERE face.repository_id = ?
           AND face.asset_id $assetTest
           AND face.person_id = person.id
           AND person.is_hidden = FALSE
           AND person.is_bad_match = FALSE
      """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetValue)).map(makeModel)
