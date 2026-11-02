package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.Table

import altitude.core.{ Const => C }
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash
import altitude.core.models.PublicMetadata
import altitude.core.models.SequenceMembers
import altitude.core.models.UserMetadata
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SequenceRule

abstract class AssetDao(val config: Config) extends BaseDao[Asset] with altitude.core.dao.AssetDao:
  final override val tableName = "asset"

  final override type Row[T[_]] = AssetRow[T]
  final override protected def table: Table[Row] = AssetRow

  override protected def toModel(row: AssetRow[Sc]): Asset =
    val assetType = AssetType(mediaType = row.mediaType, mediaSubtype = row.mediaSubtype, mime = row.mimeType)

    Asset(
      id = Option(row.id),
      userId = row.userId,
      fileName = row.filename,
      checksum = row.checksum,
      assetType = assetType,
      width = row.width,
      height = row.height,
      durationMs = row.durationMs,
      sizeBytes = row.sizeBytes,
      extractedMetadata = getJsonFromColumn(row.extractedMetadata.orNull): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(row.publicMetadata.orNull): PublicMetadata,
      userMetadata = getJsonFromColumn(row.userMetadata.orNull): UserMetadata,
      folderId = row.folderId.trim,
      isRecycled = row.isRecycled,
      isTriaged = row.isTriaged,
      isPipelineProcessed = row.isPipelineProcessed,
      originalCreatedAt = row.originalCreatedAt,
      originalCreatedAtSource = row.originalCreatedAtSource.flatMap(CaptureDateSource.fromDbValue),
      latitude = row.latitude,
      longitude = row.longitude,
      imageQuality =
        ImageQuality.fromColumns(row.sharpness, row.luminance, row.clippedHighlights, row.crushedShadows, row.imageQuality),
      perceptualHash = row.perceptualHash.map(PerceptualHash(_)),
      sequenceId = row.sequenceId,
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Asset =
    val assetType = AssetType(
      mediaType = rec(FieldConst.AssetType.MEDIA_TYPE).asInstanceOf[String],
      mediaSubtype = rec(FieldConst.AssetType.MEDIA_SUBTYPE).asInstanceOf[String],
      mime = rec(FieldConst.AssetType.MIME_TYPE).asInstanceOf[String]
    )

    Asset(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      userId = rec(FieldConst.USER_ID).asInstanceOf[String],
      fileName = rec(FieldConst.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(FieldConst.Asset.CHECKSUM).asInstanceOf[String],
      assetType = assetType,
      width = rec(FieldConst.Asset.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Asset.HEIGHT).asInstanceOf[Int],
      durationMs = getLongField(rec(FieldConst.Asset.DURATION_MS)),
      sizeBytes = getLongField(rec(FieldConst.Asset.SIZE_BYTES)).get,
      extractedMetadata = getJsonFromColumn(rec(FieldConst.Asset.EXTRACTED_METADATA)): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(FieldConst.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)): UserMetadata,
      folderId = rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String].trim,
      isRecycled = getBooleanField(rec(FieldConst.Asset.IS_RECYCLED)),
      isTriaged = getBooleanField(rec(FieldConst.Asset.IS_TRIAGED)),
      isPipelineProcessed = getBooleanField(rec(FieldConst.Asset.IS_PIPELINE_PROCESSED)),
      originalCreatedAt = getDateTimeField(rec.get(FieldConst.Asset.ORIGINAL_CREATED_AT)),
      originalCreatedAtSource =
        Option(rec(FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE)).flatMap(value => CaptureDateSource.fromDbValue(value.toString)),
      latitude = getDoubleField(rec(FieldConst.Asset.LATITUDE)),
      longitude = getDoubleField(rec(FieldConst.Asset.LONGITUDE)),
      imageQuality = ImageQuality.fromColumns(
        sharpness = getDoubleField(rec(FieldConst.Asset.SHARPNESS)),
        luminance = getDoubleField(rec(FieldConst.Asset.LUMINANCE)),
        clippedHighlights = getDoubleField(rec(FieldConst.Asset.CLIPPED_HIGHLIGHTS)),
        crushedShadows = getDoubleField(rec(FieldConst.Asset.CRUSHED_SHADOWS)),
        score = getLongField(rec(FieldConst.Asset.IMAGE_QUALITY)).map(_.toInt)
      ),
      perceptualHash = getLongField(rec(FieldConst.Asset.PERCEPTUAL_HASH)).map(PerceptualHash(_)),
      sequenceId = Option(rec(FieldConst.Asset.SEQUENCE_ID)).map(_.toString),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def queryNotRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> false).withRepository())

  override def queryRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> true).withRepository())

  override def queryAll(q: Query): QueryResult[Asset] =
    queryRecords(q.withRepository())

  override def getUserMetadata(assetId: String): Option[UserMetadata] =
    val sql = s"""
      SELECT ${FieldConst.Asset.USER_METADATA}
         FROM asset
       WHERE ${FieldConst.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val userMetadataJson = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA))
    val userMetadata = UserMetadata.fromJson(userMetadataJson)
    Some(userMetadata)

  override def add(asset: Asset): Asset =
    val sql = s"""
        INSERT INTO asset (
             ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.USER_ID}, ${FieldConst.Asset.CHECKSUM},
             ${FieldConst.Asset.FILENAME}, ${FieldConst.Asset.SIZE_BYTES},
             ${FieldConst.AssetType.MEDIA_TYPE}, ${FieldConst.AssetType.MEDIA_SUBTYPE}, ${FieldConst.AssetType.MIME_TYPE},
             ${FieldConst.Asset.FOLDER_ID}, ${FieldConst.Asset.IS_TRIAGED}, ${FieldConst.Asset.ORIGINAL_CREATED_AT}, ${FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE},
             ${FieldConst.Asset.LATITUDE}, ${FieldConst.Asset.LONGITUDE},
             ${FieldConst.CREATED_AT},
             ${FieldConst.Asset.WIDTH}, ${FieldConst.Asset.HEIGHT}, ${FieldConst.Asset.AREA_SIZE}, ${FieldConst.Asset.DURATION_MS},
             ${FieldConst.Asset.USER_METADATA}, ${FieldConst.Asset.EXTRACTED_METADATA}, ${FieldConst.Asset.PUBLIC_METADATA})
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match
      case Some(id) => id
      case None => BaseDao.genId

    // Resolution belongs to the pipeline. Unknown capture times, their provenance and coordinates are bound as SQL NULL.
    val capture: Any = asset.originalCreatedAt.map(nativeLocalDateTime).orNull
    val captureSource: Any = asset.originalCreatedAtSource.map(_.dbValue).orNull
    val latitude: Any = asset.latitude.map(Double.box).orNull
    val longitude: Any = asset.longitude.map(Double.box).orNull
    val durationMs: Any = asset.durationMs.map(Long.box).orNull

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      asset.userId,
      asset.checksum,
      asset.fileName,
      asset.sizeBytes.asInstanceOf[Object],
      asset.assetType.mediaType,
      asset.assetType.mediaSubtype,
      asset.assetType.mime,
      asset.folderId,
      asset.isTriaged,
      capture,
      captureSource,
      latitude,
      longitude,
      // Bound explicitly in UTC rather than left to the engine default, whose value depends on the server zone, and to the
      // microsecond, the precision PostgreSQL keeps, so SQLite's text stores the same value
      nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)),
      asset.width,
      asset.height,
      asset.width * asset.height,
      durationMs,
      UserMetadata.withIds(asset.userMetadata).toJson.toString,
      asset.extractedMetadata.toJson.toString,
      asset.publicMetadata.toJson.toString
    )

    addRecord(sql, sqlVals)
    asset.copy(id = Some(id))

  override def setUserMetadata(assetId: String, userMetadata: UserMetadata): Unit =
    BaseDao.incrWriteQueryCount()

    val metadataWithIds = UserMetadata.withIds(userMetadata)

    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.USER_METADATA} = $jsonFunc
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} = ?
      """

    val updateValues = List(metadataWithIds.toJson.toString, RequestContext.getRepository.persistedId, assetId)
    logger.trace(s"Update SQL: [$sql] with values: $updateValues")
    val runner: QueryRunner = new QueryRunner()

    runner.update(RequestContext.getConn, sql, updateValues*)

  /** The columns of a read that does not need the bulky metadata JSON */
  private val assetWithoutBulkyMetadata = s"""asset.*,
             NULL AS ${FieldConst.Asset.USER_METADATA},
             NULL AS ${FieldConst.Asset.EXTRACTED_METADATA}"""

  override def getAssetsToRecycle(assetIds: Set[String]): List[Asset] =
    lockForTransition(Some(assetIds), isRecycled = Some(false))

  override def getAssetsToMove(assetIds: Set[String]): List[Asset] =
    lockForTransition(Some(assetIds), isRecycled = None)

  override def getAssetsToRestore(assetIds: Set[String]): List[Asset] =
    lockForTransition(Some(assetIds), isRecycled = Some(true))

  override def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset] =
    lockForTransition(assetIds, isRecycled = Some(true))

  /**
   * The context repository's completed assets that are not marked for purging, among the IDs when given, recycled or live when
   * asked, locked in ID order until the transaction ends. The flags are literals, which each engine reads as its own boolean, so
   * no bind value differs by engine.
   */
  private def lockForTransition(assetIds: Option[Set[String]], isRecycled: Option[Boolean]): List[Asset] =
    if assetIds.exists(_.isEmpty) then return List.empty[Asset]

    val idFilter = assetIds.fold("")(_ => s"AND id $inIdSet")
    val recycledFilter = isRecycled.fold("")(flag => s"AND ${FieldConst.Asset.IS_RECYCLED} = ${if flag then "TRUE" else "FALSE"}")

    val sql = s"""
      SELECT $assetWithoutBulkyMetadata
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
         $idFilter
         $recycledFilter
    ORDER BY ${FieldConst.ID}
         $forUpdate
    """

    val values = RequestContext.getRepository.persistedId :: assetIds.map(idSet).toList
    manyBySqlQuery(sql, values).map(makeModel)

  override def getSequenceCandidates(asset: Asset, gapMs: Int): List[Asset] =
    sequenceCandidatesStatement(asset, gapMs).fold(List.empty[Asset])((sql, values) => manyBySqlQuery(sql, values).map(makeModel))

  /**
   * The statement behind [[getSequenceCandidates]] with its bind values, which the plan tests explain; nothing for an asset
   * without a capture time. The window's calendar days, every one from the first to the last, lead the triage capture-day index
   * and the timestamp then seeks within it. The flags are literals, which each engine reads as its own boolean, so the partial
   * index on the triaged assets is read; a candidate may be in the trash (an asset recycled from triage keeps its flag and its
   * Sequence, so a frame of its burst imported meanwhile joins it), so the index's `is_recycled` column is probed for both
   * values, and only a purge-pending row is out. The rows are locked (`FOR UPDATE` on PostgreSQL): a move sorting one of them
   * meanwhile holds it, and once the move commits the row is re-evaluated and left out. The predicates on the media type, the
   * duration, the hash, the capture time's source and the triage flag are a derived copy of `SequenceRule.isEligible`, which
   * stays the authority (every candidate goes through it again in Scala); they are here to spare reading full rows, and a change
   * to the rule is made in both places. The metadata JSON a candidate is compared by (the device model) is the public one.
   */
  def sequenceCandidatesStatement(asset: Asset, gapMs: Int): Option[(String, List[Any])] =
    asset.originalCreatedAt.map {
      capturedAt =>
        val from = capturedAt.minus(gapMs, ChronoUnit.MILLIS)
        val to = capturedAt.plus(gapMs, ChronoUnit.MILLIS)
        val days = Iterator.iterate(from.toLocalDate)(_.plusDays(1)).takeWhile(!_.isAfter(to.toLocalDate)).toList
        val sources = SequenceRule.CameraClockSources.map(_.dbValue).toList.sorted
        def placeholders(values: List[?]): String = values.map(_ => "?").mkString(", ")

        val sql = s"""
          SELECT $assetWithoutBulkyMetadata
            FROM asset
           WHERE ${FieldConst.REPO_ID} = ?
             AND ${FieldConst.Asset.IS_RECYCLED} IN (FALSE, TRUE)
             AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
             AND ${FieldConst.Asset.IS_TRIAGED} = TRUE
             AND ${dayOf(FieldConst.Asset.ORIGINAL_CREATED_AT)} IN (${placeholders(days)})
             AND ${FieldConst.Asset.ORIGINAL_CREATED_AT} BETWEEN ? AND ?
             AND ${FieldConst.Asset.IS_PURGED} = FALSE
             AND ${FieldConst.ID} <> ?
             AND ${FieldConst.AssetType.MEDIA_TYPE} = 'image'
             AND ${FieldConst.Asset.DURATION_MS} IS NULL
             AND ${FieldConst.Asset.PERCEPTUAL_HASH} IS NOT NULL
             AND ${FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE} IN (${placeholders(sources)})
             $forUpdate
        """

        val values = RequestContext.getRepository.persistedId :: days.map(nativeLocalDate) :::
          List(nativeLocalDateTime(from), nativeLocalDateTime(to), asset.persistedId) ::: sources
        (sql, values)
    }

  override def countSequenceMembers(sequenceIds: Set[String]): List[SequenceMembers] =
    if sequenceIds.isEmpty then return List.empty[SequenceMembers]

    val sql = s"""
      SELECT ${FieldConst.Asset.SEQUENCE_ID}, COUNT(*) AS members, MIN(${FieldConst.Asset.ORIGINAL_CREATED_AT}) AS first_captured_at
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.SEQUENCE_ID} $inIdSet
       GROUP BY ${FieldConst.Asset.SEQUENCE_ID}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(sequenceIds))).map(toSequenceMembers)

  private def toSequenceMembers(rec: Map[String, AnyRef]): SequenceMembers =
    SequenceMembers(
      sequenceId = rec(FieldConst.Asset.SEQUENCE_ID).toString,
      members = getLongField(rec("members")).get.toInt,
      firstCapturedAt = getDateTimeField(rec.get("first_captured_at"))
    )

  override def getSequencesToCurate(): List[SequenceMembers] =
    val (sql, values) = sequencesToCurateStatement
    manyBySqlQuery(sql, values).map(toSequenceMembers)

  override def countSequencesToCurate(): Int =
    val (sql, values) = countSequencesToCurateStatement
    getLongField(executeAndGetOne(sql, values)("sequences")).get.toInt

  override def getSequenceFrames(sequenceId: String): List[Asset] =
    val (sql, values) = sequenceFramesStatement(sequenceId)
    manyBySqlQuery(sql, values).map(makeModel)

  /**
   * The context repository's Sequences with at least two frames completed and out of the trash, counted over those frames alone.
   * `sequence_id IS NOT NULL` is what lets `asset_sequence` answer it: that index holds only the rows that carry a Sequence, so
   * the cost is theirs and not the library's.
   */
  private def sequencesToCurate: String = s"""
      SELECT ${FieldConst.Asset.SEQUENCE_ID},
             COUNT(*) AS members,
             MIN(${FieldConst.Asset.ORIGINAL_CREATED_AT}) AS first_captured_at
        FROM asset
       WHERE ${FieldConst.Asset.SEQUENCE_ID} IS NOT NULL
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_RECYCLED} = FALSE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
       GROUP BY ${FieldConst.Asset.SEQUENCE_ID}
      HAVING COUNT(*) >= ${C.Curation.MIN_FRAMES}
    """

  /** The statement behind [[getSequencesToCurate]] with its bind values, which the plan tests explain */
  def sequencesToCurateStatement: (String, List[Any]) =
    val sql = s"$sequencesToCurate ORDER BY first_captured_at, ${FieldConst.Asset.SEQUENCE_ID}"
    (sql, List(RequestContext.getRepository.persistedId))

  /** The statement behind [[countSequencesToCurate]] with its bind values, which the plan tests explain */
  def countSequencesToCurateStatement: (String, List[Any]) =
    (s"SELECT COUNT(*) AS sequences FROM ($sequencesToCurate) AS to_curate", List(RequestContext.getRepository.persistedId))

  /** The frames completed and out of the trash whose Sequence passes the test, whose one placeholder binds before the repository */
  private def liveFramesOfSequences(sequenceTest: String): String = s"""
      SELECT $assetWithoutBulkyMetadata
        FROM asset
       WHERE ${FieldConst.Asset.SEQUENCE_ID} $sequenceTest
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_RECYCLED} = FALSE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
    """

  /** The statement behind [[getSequenceFrames]] with its bind values, which the plan tests explain */
  def sequenceFramesStatement(sequenceId: String): (String, List[Any]) =
    (liveFramesOfSequences("= ?"), List(sequenceId, RequestContext.getRepository.persistedId))

  // SQLite's one write connection serializes the writers
  override def lockSequences(): Unit = ()

  override def getLiveSequenceMembers(sequenceIds: Set[String]): List[Asset] =
    if sequenceIds.isEmpty then return List.empty[Asset]

    val (sql, values) = liveSequenceMembersStatement(sequenceIds)
    manyBySqlQuery(sql, values).map(makeModel)

  /**
   * The statement behind [[getLiveSequenceMembers]] with its bind values, which the plan tests explain. The rows are locked in ID
   * order, the order of the library operations' locking select, so the two do not wait on each other in a cycle.
   */
  def liveSequenceMembersStatement(sequenceIds: Set[String]): (String, List[Any]) =
    val sql = s"${liveFramesOfSequences(inIdSet)} ORDER BY ${FieldConst.ID} $forUpdate"
    (sql, List(idSet(sequenceIds), RequestContext.getRepository.persistedId))

  override def clearSequence(assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.SEQUENCE_ID} = NULL
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} $inIdSet
    """
    updateByBySql(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))

  override def setSequence(assetIds: Set[String], sequenceId: String): Int =
    if assetIds.isEmpty then return 0

    // Only a row still in triage takes a Sequence: a candidate a move sorted meanwhile is left as it is
    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.SEQUENCE_ID} = ?
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} $inIdSet AND ${FieldConst.Asset.IS_TRIAGED} = TRUE
    """
    updateByBySql(sql, List(sequenceId, RequestContext.getRepository.persistedId, idSet(assetIds)))

  override def repointSequences(fromIds: Set[String], toId: String): Int =
    if fromIds.isEmpty then return 0

    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.SEQUENCE_ID} = ?
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.Asset.SEQUENCE_ID} $inIdSet
    """
    updateByBySql(sql, List(toId, RequestContext.getRepository.persistedId, idSet(fromIds)))

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Unit =
    val existingMetadata = getUserMetadata(assetId) match
      case Some(m) => m
      case None => UserMetadata()

    logger.trace(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new UserMetadata(newData)
    logger.trace(s"New metadata -> $newMetadata")

    setUserMetadata(assetId, newMetadata)
