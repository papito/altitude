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
import altitude.core.dao.sql.tables.BulkyMetadata
import altitude.core.models.AccountSnapshot
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata
import altitude.core.models.Histogram
import altitude.core.models.ImageQuality
import altitude.core.models.PerceptualHash
import altitude.core.models.PublicMetadata
import altitude.core.models.QueuedSequence
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.models.SequenceMembers
import altitude.core.models.UserMetadata
import altitude.core.util.JsonCodec
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
      ownerUserId = row.ownerUserId,
      originalOwner = JsonCodec.read[AccountSnapshot](row.originalOwner),
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
      folderId = row.folderId.fold("")(_.trim),
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
      editRev = row.editRev,
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
      ownerUserId = rec(FieldConst.Asset.OWNER_USER_ID).asInstanceOf[String],
      originalOwner = JsonCodec.read[AccountSnapshot](rec(FieldConst.Asset.ORIGINAL_OWNER).toString),
      fileName = rec(FieldConst.Asset.FILENAME).asInstanceOf[String],
      checksum = rec(FieldConst.Asset.CHECKSUM).asInstanceOf[String],
      assetType = assetType,
      width = rec(FieldConst.Asset.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Asset.HEIGHT).asInstanceOf[Int],
      durationMs = getLongField(rec(FieldConst.Asset.DURATION_MS)),
      sizeBytes = getLongField(rec(FieldConst.Asset.SIZE_BYTES)).get,
      // The two bulky JSON columns a hand-written read may leave out of its select (`assetWithoutBulkyMetadata`) read as empty
      extractedMetadata = getJsonFromColumn(rec.get(FieldConst.Asset.EXTRACTED_METADATA).orNull): ExtractedMetadata,
      publicMetadata = getJsonFromColumn(rec(FieldConst.Asset.PUBLIC_METADATA)): PublicMetadata,
      userMetadata = getJsonFromColumn(rec.get(FieldConst.Asset.USER_METADATA).orNull): UserMetadata,
      // NULL for a triaged asset (in no folder), empty in the model
      folderId = Option(rec(FieldConst.Asset.FOLDER_ID)).fold("")(_.toString.trim),
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
      // Absent unless the read selected `candidateColumns`
      sceneEmbeddings = SceneEmbeddings(
        dinov2 = rec.get(SceneModel.Dinov2.column).flatMap(getVectorField),
        siglip2 = rec.get(SceneModel.Siglip2.column).flatMap(getVectorField)),
      sequenceId = Option(rec(FieldConst.Asset.SEQUENCE_ID)).map(_.toString),
      editRev = getIntField(rec(FieldConst.Asset.EDIT_REV)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def queryNotRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> false))

  override def queryRecycled(q: Query): QueryResult[Asset] =
    queryRecords(q.add(FieldConst.Asset.IS_RECYCLED -> true))

  override def queryAll(q: Query): QueryResult[Asset] =
    queryRecords(q)

  override def getUserMetadata(assetId: String): UserMetadata =
    val sql = s"""
      SELECT ${FieldConst.Asset.USER_METADATA}
         FROM asset
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId, RequestContext.getRepository.persistedId))
    UserMetadata.fromJson(getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)))

  override def add(asset: Asset): Asset =
    val sql = s"""
        INSERT INTO asset (
             ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Asset.OWNER_USER_ID}, ${FieldConst.Asset.ORIGINAL_OWNER},
             ${FieldConst.Asset.CHECKSUM},
             ${FieldConst.Asset.FILENAME}, ${FieldConst.Asset.SIZE_BYTES},
             ${FieldConst.AssetType.MEDIA_TYPE}, ${FieldConst.AssetType.MEDIA_SUBTYPE}, ${FieldConst.AssetType.MIME_TYPE},
             ${FieldConst.Asset.FOLDER_ID}, ${FieldConst.Asset.IS_TRIAGED}, ${FieldConst.Asset.ORIGINAL_CREATED_AT}, ${FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE},
             ${FieldConst.Asset.LATITUDE}, ${FieldConst.Asset.LONGITUDE},
             ${FieldConst.CREATED_AT},
             ${FieldConst.Asset.WIDTH}, ${FieldConst.Asset.HEIGHT}, ${FieldConst.Asset.AREA_SIZE}, ${FieldConst.Asset.DURATION_MS},
             ${FieldConst.Asset.USER_METADATA}, ${FieldConst.Asset.EXTRACTED_METADATA}, ${FieldConst.Asset.PUBLIC_METADATA})
            VALUES(?, ?, ?, $jsonFunc, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $jsonFunc, $jsonFunc, $jsonFunc)
    """

    val id = asset.id match
      case Some(id) => id
      case None => BaseDao.genId

    // Unknown values bind as SQL NULL; resolving the capture date is the pipeline's job
    val capture: Any = asset.originalCreatedAt.map(nativeLocalDateTime).orNull
    val captureSource: Any = asset.originalCreatedAtSource.map(_.dbValue).orNull
    val latitude: Any = asset.latitude.map(Double.box).orNull
    val longitude: Any = asset.longitude.map(Double.box).orNull
    val durationMs: Any = asset.durationMs.map(Long.box).orNull

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      asset.ownerUserId,
      asset.originalOwner.toJson.toString,
      asset.checksum,
      asset.fileName,
      asset.sizeBytes.asInstanceOf[Object],
      asset.assetType.mediaType,
      asset.assetType.mediaSubtype,
      asset.assetType.mime,
      // A triaged asset is in no folder: NULL, which the folder key does not check
      Option(asset.folderId).filter(_.nonEmpty).orNull,
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

  override def setUserMetadata(assetId: String, userMetadata: UserMetadata): Int =
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

  /** The row's columns without the bulky metadata JSON; the Scene embeddings are not among them either */
  private val assetWithoutBulkyMetadata: String = AssetRow.columnList(BulkyMetadata.Omitted)

  /** For the Sequence candidates statements alone: the embeddings are a few KB a row, toasted out of line on PostgreSQL */
  private val candidateColumns: String =
    s"$assetWithoutBulkyMetadata, ${SceneModel.values.map(model => s"asset.${model.column}").mkString(", ")}"

  override def markAsCompleted(asset: Asset): Int =
    SceneModel.values.foreach {
      model =>
        asset.sceneEmbeddings.of(model).foreach {
          embedding =>
            require(
              embedding.values.length == model.dimensions,
              s"A ${model.name} embedding has ${model.dimensions} dimensions, not ${embedding.values.length}")
        }
    }

    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE,
             ${FieldConst.Asset.SHARPNESS} = ?,
             ${FieldConst.Asset.LUMINANCE} = ?,
             ${FieldConst.Asset.CLIPPED_HIGHLIGHTS} = ?,
             ${FieldConst.Asset.CRUSHED_SHADOWS} = ?,
             ${FieldConst.Asset.IMAGE_QUALITY} = ?,
             ${FieldConst.Asset.PERCEPTUAL_HASH} = ?,
             ${FieldConst.Asset.HISTOGRAM} = ?,
             ${SceneModel.Dinov2.column} = $vectorPlaceholder,
             ${SceneModel.Siglip2.column} = $vectorPlaceholder
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.ID} = ?
    """

    val quality = asset.imageQuality
    val values: List[Any] = List(
      quality.map(q => Double.box(q.sharpness)).orNull,
      quality.map(q => Double.box(q.luminance)).orNull,
      quality.map(q => Double.box(q.clippedHighlights)).orNull,
      quality.map(q => Double.box(q.crushedShadows)).orNull,
      quality.map(q => Int.box(q.score)).orNull,
      asset.perceptualHash.map(h => Long.box(h.bits)).orNull,
      asset.histogram.map(_.toBytes).orNull,
      vectorBind(asset.sceneEmbeddings.dinov2),
      vectorBind(asset.sceneEmbeddings.siglip2),
      RequestContext.getRepository.persistedId,
      asset.persistedId
    )
    updateByBySql(sql, values)

  override def getHistograms(assetIds: Set[String]): Map[String, Histogram] =
    if assetIds.isEmpty then return Map.empty

    val sql = s"""
      SELECT ${FieldConst.ID}, ${FieldConst.Asset.HISTOGRAM}
        FROM asset
       WHERE ${FieldConst.ID} $inIdSet
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.HISTOGRAM} IS NOT NULL
    """
    manyBySqlQuery(sql, List(idSet(assetIds), RequestContext.getRepository.persistedId)).map {
      rec =>
        rec(FieldConst.ID).asInstanceOf[String] -> Histogram.fromBytes(rec(FieldConst.Asset.HISTOGRAM).asInstanceOf[Array[Byte]])
    }.toMap

  override def getAssetsToRecycle(assetIds: Set[String]): List[Asset] =
    lockAssets(Some(assetIds), isRecycled = Some(false))

  override def getAssetsToMove(assetIds: Set[String]): List[Asset] =
    lockAssets(Some(assetIds), isRecycled = None)

  override def getAssetsToRestore(assetIds: Set[String]): List[Asset] =
    lockAssets(Some(assetIds), isRecycled = Some(true))

  override def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset] =
    lockAssets(assetIds, isRecycled = Some(true))

  override def getIdsInFolders(folderIds: Set[String]): Set[String] =
    if folderIds.isEmpty then return Set.empty

    val sql = s"""
      SELECT ${FieldConst.ID}
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.FOLDER_ID} $inIdSet
    """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(folderIds)))
      .map(_(FieldConst.ID).asInstanceOf[String])
      .toSet

  // Only a recycled asset is ever marked for purging, so the recycled flag adds nothing to the match, but it narrows the read to
  // the Trash through the indexes led by (repository_id, is_recycled)
  override def getPurgePending: List[Asset] =
    val sql = s"""
      SELECT $assetWithoutBulkyMetadata
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_RECYCLED} = TRUE
         AND ${FieldConst.Asset.IS_PURGED} = TRUE
    """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId)).map(makeModel)

  override def lockUnpurged(assetId: String): Boolean =
    val sql = s"""
      SELECT id
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ? AND id = ? AND ${FieldConst.Asset.IS_PURGED} = FALSE
      $forNoKeyUpdate
      """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId)).nonEmpty

  override def lockTriaged(assetIds: Set[String]): List[Asset] =
    lockAssets(Some(assetIds), isRecycled = None, triagedOnly = true)

  /**
   * Locks the repository's completed, unpurged assets in ID order. The flags are SQL literals, which each engine reads as its own
   * boolean, so no bind value differs by engine.
   */
  private def lockAssets(assetIds: Option[Set[String]], isRecycled: Option[Boolean], triagedOnly: Boolean = false): List[Asset] =
    if assetIds.exists(_.isEmpty) then return List.empty[Asset]

    val idFilter = assetIds.fold("")(_ => s"AND id $inIdSet")
    val recycledFilter = isRecycled.fold("")(flag => s"AND ${FieldConst.Asset.IS_RECYCLED} = ${if flag then "TRUE" else "FALSE"}")
    val triagedFilter = if triagedOnly then s"AND ${FieldConst.Asset.IS_TRIAGED} = TRUE" else ""

    val sql = s"""
      SELECT $assetWithoutBulkyMetadata
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
         $idFilter
         $recycledFilter
         $triagedFilter
    ORDER BY ${FieldConst.ID}
         $forUpdate
    """

    val values = RequestContext.getRepository.persistedId :: assetIds.map(idSet).toList
    manyBySqlQuery(sql, values).map(makeModel)

  override def getSequenceCandidates(asset: Asset, gapMs: Int): List[Asset] =
    sequenceCandidatesStatement(asset, gapMs).fold(List.empty[Asset])((sql, values) => manyBySqlQuery(sql, values).map(makeModel))

  /**
   * Binds the repository, then the asset's ID. A copy of what `SequenceRule.isDated` and `isUndated` share, to spare reading full
   * rows; the rule stays the authority, so change both together. The flags are literals so the partial triage index is read.
   * `is_recycled` is left to each statement, as only the dated one's index holds it.
   */
  private val sequenceCandidatesWhere: String = s"""
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_TRIAGED} = TRUE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
         AND ${FieldConst.ID} <> ?
         AND ${FieldConst.AssetType.MEDIA_TYPE} = 'image'
         AND ${FieldConst.Asset.DURATION_MS} IS NULL
         AND ${FieldConst.Asset.PERCEPTUAL_HASH} IS NOT NULL"""

  /**
   * Public so the query-plan tests can explain it. Each calendar day of the window leads the triage capture-day index and the
   * timestamp seeks within it. `is_recycled` is probed for both values: an asset recycled from triage keeps its Sequence, so a
   * burst frame imported meanwhile still joins it. The rows are locked, so a move sorting one out of triage meanwhile excludes it
   * once it commits. The source test completes the copy of `SequenceRule.isDated`.
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
          SELECT $candidateColumns
            FROM asset
      $sequenceCandidatesWhere
             AND ${FieldConst.Asset.IS_RECYCLED} IN (FALSE, TRUE)
             AND ${dayOf(FieldConst.Asset.ORIGINAL_CREATED_AT)} IN (${placeholders(days)})
             AND ${FieldConst.Asset.ORIGINAL_CREATED_AT} BETWEEN ? AND ?
             AND ${FieldConst.Asset.ORIGINAL_CREATED_AT_SOURCE} IN (${placeholders(sources)})
             $forUpdate
        """

        val values = RequestContext.getRepository.persistedId :: asset.persistedId :: days.map(nativeLocalDate) :::
          List(nativeLocalDateTime(from), nativeLocalDateTime(to)) ::: sources
        (sql, values)
    }

  override def getUndatedSequenceMatches(asset: Asset, limit: Int)(keep: Asset => Boolean): BaseDao.Collected[Asset] =
    val (sql, values) = undatedSequenceCandidatesStatement(asset, limit)
    collectBySqlQuery(sql, values)(rec => Some(makeModel(rec)).filter(keep))

  /**
   * Public so the query-plan tests can explain it. The asset's size is one run of the partial index `asset_triage_undated`,
   * walked from the newest and stopped at the limit, so an undated import's cost is bounded by the limit whatever else triage
   * holds (below the limit PostgreSQL may bitmap-scan and sort instead, at the same cost). The capture-time and size tests
   * complete the copy of `SequenceRule.isUndated`. Not locked: Sequence writers take turns through [[lockSequences]], and the
   * matches are re-read locked through [[lockTriaged]] before they are stamped. The caller streams the rows, so only the matches'
   * embeddings are held.
   */
  def undatedSequenceCandidatesStatement(asset: Asset, limit: Int): (String, List[Any]) =
    val sql = s"""
      SELECT $candidateColumns
        FROM asset
  $sequenceCandidatesWhere
         AND ${FieldConst.Asset.ORIGINAL_CREATED_AT} IS NULL
         AND ${FieldConst.Asset.WIDTH} = ?
         AND ${FieldConst.Asset.HEIGHT} = ?
    ORDER BY ${FieldConst.CREATED_AT} DESC, ${FieldConst.ID} DESC
       LIMIT ?
    """
    (sql, List(RequestContext.getRepository.persistedId, asset.persistedId, asset.width, asset.height, limit))

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

  override def getSequencesToCurate(owner: Option[String]): List[QueuedSequence] =
    val (sql, values) = sequencesToCurateStatement(owner)
    manyBySqlQuery(sql, values).map {
      rec =>
        val members = toSequenceMembers(rec)
        QueuedSequence(members.sequenceId, members.members, members.firstCapturedAt, rec("cover_id").toString)
    }

  override def countSequencesToCurate(owner: Option[String]): Int =
    val (sql, values) = countSequencesToCurateStatement(owner)
    getLongField(executeAndGetOne(sql, values)("sequences")).get.toInt

  override def getSequenceFrames(sequenceId: String, limit: Int, owner: Option[String]): List[Asset] =
    val (sql, values) = sequenceFramesStatement(sequenceId, limit, owner)
    manyBySqlQuery(sql, values).map(makeModel)

  override def countSequenceFrames(sequenceId: String, owner: Option[String]): Int =
    val (sql, values) = countSequenceFramesStatement(sequenceId, owner)
    getLongField(executeAndGetOne(sql, values)("frames")).get.toInt

  override def countOwnedSequenceFrames(sequenceIds: Set[String], owner: String): Map[String, Int] =
    if sequenceIds.isEmpty then return Map.empty

    val (frames, values) = liveFramesOfSequences(inIdSet, Some(owner))
    val sql = s"SELECT ${FieldConst.Asset.SEQUENCE_ID}, COUNT(*) AS frames $frames GROUP BY ${FieldConst.Asset.SEQUENCE_ID}"
    manyBySqlQuery(sql, idSet(sequenceIds) :: values).map {
      rec => rec(FieldConst.Asset.SEQUENCE_ID).toString.trim -> getLongField(rec("frames")).get.toInt
    }.toMap

  /** The first capture of a Sequence's frames, which the queue is ordered by; none for an undated Sequence */
  private val firstCapture = s"MIN(${FieldConst.Asset.ORIGINAL_CREATED_AT})"

  /** Keeps `owner`'s frames alone, for an account that curates only its own; `table` is the alias `asset` is read under */
  private def ownerTest(owner: Option[String], table: String = "asset"): (String, List[Any]) =
    owner.fold(("", Nil))(id => (s"AND $table.${FieldConst.Asset.OWNER_USER_ID} = ?", List(id)))

  /**
   * Counted over the live frames alone. `sequence_id IS NOT NULL` lets the partial index `asset_sequence` answer it, so the cost
   * scales with the rows that have a Sequence, not with the library.
   */
  private def sequencesToCurate(owner: Option[String]): (String, List[Any]) =
    val (byOwner, ownerValues) = ownerTest(owner)
    val sql = s"""
      SELECT ${FieldConst.Asset.SEQUENCE_ID},
             COUNT(*) AS members,
             $firstCapture AS first_captured_at
        FROM asset
       WHERE ${FieldConst.Asset.SEQUENCE_ID} IS NOT NULL
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_RECYCLED} = FALSE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
         $byOwner
       GROUP BY ${FieldConst.Asset.SEQUENCE_ID}
      HAVING COUNT(*) >= ${C.Curation.MIN_FRAMES}
    """
    (sql, RequestContext.getRepository.persistedId :: ownerValues)

  /**
   * Public so the query-plan tests can explain it. NULL sorts first on SQLite and last on PostgreSQL, so `IS NULL` puts undated
   * Sequences last on both. The cover, the first live frame in [[sequenceFramesStatement]]'s order, is a correlated subquery read
   * once per queued Sequence; the count statement leaves it out, as the toolbar reads the count after every mutation.
   */
  def sequencesToCurateStatement(owner: Option[String] = None): (String, List[Any]) =
    val capturedAt = FieldConst.Asset.ORIGINAL_CREATED_AT
    val (coverByOwner, coverOwnerValues) = ownerTest(owner, "cover")
    val cover = s"""
      (SELECT cover.${FieldConst.ID}
         FROM asset AS cover
        WHERE cover.${FieldConst.Asset.SEQUENCE_ID} = to_curate.${FieldConst.Asset.SEQUENCE_ID}
          AND cover.${FieldConst.REPO_ID} = ?
          AND cover.${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
          AND cover.${FieldConst.Asset.IS_RECYCLED} = FALSE
          AND cover.${FieldConst.Asset.IS_PURGED} = FALSE
          $coverByOwner
        ORDER BY cover.$capturedAt IS NULL, cover.$capturedAt, cover.${FieldConst.CREATED_AT}, cover.${FieldConst.ID}
        LIMIT 1) AS cover_id
    """
    val (toCurate, toCurateValues) = sequencesToCurate(owner)
    val sql = s"""
      SELECT to_curate.*, $cover
        FROM ($toCurate) AS to_curate
       ORDER BY first_captured_at IS NULL, first_captured_at, ${FieldConst.Asset.SEQUENCE_ID}
    """
    // The cover's placeholders come first in the text
    (sql, (RequestContext.getRepository.persistedId :: coverOwnerValues) ++ toCurateValues)

  /** Public so the query-plan tests can explain it */
  def countSequencesToCurateStatement(owner: Option[String] = None): (String, List[Any]) =
    val (toCurate, values) = sequencesToCurate(owner)
    (s"SELECT COUNT(*) AS sequences FROM ($toCurate) AS to_curate", values)

  /** The `FROM` of the live frames whose Sequence passes `sequenceTest`; its values follow the Sequence's */
  private def liveFramesOfSequences(sequenceTest: String, owner: Option[String] = None): (String, List[Any]) =
    val (byOwner, ownerValues) = ownerTest(owner)
    val sql = s"""
        FROM asset
       WHERE ${FieldConst.Asset.SEQUENCE_ID} $sequenceTest
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = TRUE
         AND ${FieldConst.Asset.IS_RECYCLED} = FALSE
         AND ${FieldConst.Asset.IS_PURGED} = FALSE
         $byOwner
    """
    (sql, RequestContext.getRepository.persistedId :: ownerValues)

  /**
   * Public so the query-plan tests can explain it. The order is `CurationRule.captureOrder`'s; the import time breaks ties ahead
   * of the random ID, so undated frames come in upload order.
   */
  def sequenceFramesStatement(sequenceId: String, limit: Int, owner: Option[String] = None): (String, List[Any]) =
    val capturedAt = FieldConst.Asset.ORIGINAL_CREATED_AT
    val (frames, values) = liveFramesOfSequences("= ?", owner)
    val sql = s"""
      SELECT $assetWithoutBulkyMetadata $frames
       ORDER BY $capturedAt IS NULL, $capturedAt, ${FieldConst.CREATED_AT}, ${FieldConst.ID}
       LIMIT $limit
    """
    (sql, sequenceId :: values)

  /** Public so the query-plan tests can explain it */
  def countSequenceFramesStatement(sequenceId: String, owner: Option[String] = None): (String, List[Any]) =
    val (frames, values) = liveFramesOfSequences("= ?", owner)
    (s"SELECT COUNT(*) AS frames $frames", sequenceId :: values)

  override def lockSequences(): Unit = advisoryLock(s"sequences:${RequestContext.getRepository.persistedId}")

  override def getLiveSequenceMembers(sequenceIds: Set[String]): List[Asset] =
    if sequenceIds.isEmpty then return List.empty[Asset]

    val (sql, values) = liveSequenceMembersStatement(sequenceIds)
    manyBySqlQuery(sql, values).map(makeModel)

  /**
   * Public so the query-plan tests can explain it. Locked in ID order, as the library operations' locking select is, so the two
   * cannot deadlock.
   */
  def liveSequenceMembersStatement(sequenceIds: Set[String]): (String, List[Any]) =
    val (frames, values) = liveFramesOfSequences(inIdSet)
    (s"SELECT $assetWithoutBulkyMetadata $frames ORDER BY ${FieldConst.ID} $forUpdate", idSet(sequenceIds) :: values)

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

  // The only asset statements without repository_id: these three act on an account across every Library
  override def countOwnedBy(accountId: String): Int =
    val sql = s"SELECT COUNT(*) AS assets FROM asset WHERE ${FieldConst.Asset.OWNER_USER_ID} = ?"
    getLongField(executeAndGetOne(sql, List(accountId))("assets")).get.toInt

  override def reassignOwner(fromAccountId: String, toAccountId: String): Int =
    val sql = s"UPDATE asset SET ${FieldConst.Asset.OWNER_USER_ID} = ? WHERE ${FieldConst.Asset.OWNER_USER_ID} = ?"
    updateByBySql(sql, List(toAccountId, fromAccountId))

  override def rewriteImporterRecords(snapshot: AccountSnapshot): Int =
    val sql = s"""
      UPDATE asset
         SET ${FieldConst.Asset.ORIGINAL_OWNER} = $jsonFunc
       WHERE ${jsonText(FieldConst.Asset.ORIGINAL_OWNER, "id")} = ?
    """
    updateByBySql(sql, List(ujson.write(snapshot.toJson), snapshot.id))

  def updateMetadata(assetId: String, metadata: UserMetadata, deletedFields: Set[String]): Int =
    val existingMetadata = getUserMetadata(assetId)
    logger.trace(s"Updating $existingMetadata with $metadata")
    val newData = (existingMetadata.data ++ metadata.data).filterNot(m => deletedFields.contains(m._1))
    val newMetadata = new UserMetadata(newData)
    logger.trace(s"New metadata -> $newMetadata")

    setUserMetadata(assetId, newMetadata)
