package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.FaceRow
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.FaceMatch
import altitude.core.models.Person

abstract class FaceDao(override val config: Config) extends BaseDao[Face] with altitude.core.dao.FaceDao:

  final override val tableName = "face"

  final override type Row[T[_]] = FaceRow[T]
  final override protected def table: Table[Row] = FaceRow

  // The row class leaves out the feature vector, which only `getFeatures` reads
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

  override def add(face: Face, asset: Asset, person: Person): Face =
    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO face (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                          ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.DETECTION_SCORE},
                          ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM}, ${FieldConst.Face.FRAME_TIME_MS},
                          ${FieldConst.Face.QUALITY}, ${FieldConst.Face.IS_ENROLLED}, ${FieldConst.Face.SHARPNESS})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, $vectorPlaceholder, ?, ?, ?, ?, ?)
    """

    val values: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      face.x1,
      face.y1,
      face.width,
      face.height,
      asset.persistedId,
      person.persistedId,
      face.detectionScore,
      vectorBind(IArray.unsafeFromArray(face.features)),
      face.checksum,
      face.frameTimeMs.getOrElse(null),
      face.quality,
      face.isEnrolled,
      face.sharpness.getOrElse(null)
    )

    trace(s"Inserting face [$id] for person [${person.persistedId}] in asset [${asset.persistedId}]")
    addRecord(sql, values)
    face.copy(id = Some(id), assetId = asset.id, personId = person.id)

  override def getTopFaces(personId: String, limit: Int): List[Face] = coverCandidates(personId, Set.empty, limit)

  override def getBestCoverCandidate(personId: String, excludedAssetIds: Set[String]): Option[Face] =
    coverCandidates(personId, excludedAssetIds, limit = 1).headOption

  /**
   * The person's Faces a cover can be, on no excluded or purged asset, best detection score first. `face_02` leads with the
   * person and orders by detection score, so the read stops at the limit.
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

  // The only read of the vector column
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
           AND face.asset_id IN (SELECT mine.asset_id FROM face mine WHERE mine.repository_id = ? AND mine.person_id = ?)
           AND face.asset_id IN (SELECT theirs.asset_id FROM face theirs WHERE theirs.repository_id = ? AND theirs.person_id = ?)
      """

    val repositoryId = RequestContext.getRepository.persistedId
    manyBySqlQuery(
      sql,
      List(repositoryId, personId, otherPersonId, "video", repositoryId, personId, repositoryId, otherPersonId)
    ).map(makeModel)

  /** A row of a nearest-Faces statement, which selects the Face's columns and its `distance`, as a match */
  protected def faceMatchOf(rec: Map[String, AnyRef]): FaceMatch =
    FaceMatch(rec(FieldConst.Face.PERSON_ID).asInstanceOf[String], rec(FaceDao.DISTANCE).asInstanceOf[Number].doubleValue)

  def getAssetFaces(assetId: String): List[Face] =
    visibleFaces("= ?", assetId)

  override def getFacesOfAssets(assetIds: Set[String]): List[Face] =
    if assetIds.isEmpty then return List.empty[Face]

    visibleFaces(inIdSet, idSet(assetIds))

  /** The Faces of people not hidden or bad matches, on the assets `assetTest` picks with its one bind value */
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

object FaceDao:

  /** The column of a nearest-Faces statement that holds a Face's cosine distance to the vector searched */
  val DISTANCE = "distance"
