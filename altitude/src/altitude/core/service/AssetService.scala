package altitude.core.service

import com.drew.imaging.ImageProcessingException
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO

import scala.util.control.NonFatal

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.NotFoundException
import altitude.core.SceneEmbedderClosedException
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.DetailPanelData
import altitude.core.models.DetectedFaces
import altitude.core.models.MimedPreviewData
import altitude.core.models.SceneModel
import altitude.core.util.ImageMeasurement
import altitude.core.util.ImageUtil
import altitude.core.util.ImageUtil.makeImageThumbnail
import altitude.core.util.Query
import altitude.core.util.QueryResult

class AssetService(val app: Altitude) extends BaseService[Asset]:
  override protected val dao: AssetDao = app.DAO.asset

  def setRecycledProp(asset: Asset, isRecycled: Boolean): Unit =
    if asset.isRecycled == isRecycled then return

    txManager.withTransaction {
      logger.debug(s"Setting asset [${asset.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(asset.persistedId, Map(FieldConst.Asset.IS_RECYCLED -> isRecycled))
    }

  def getByChecksum(checksum: String): Option[Asset] =
    txManager.asReadOnly {
      val q = new Query(params = Map(FieldConst.Asset.CHECKSUM -> checksum))
      val existing = query(q)
      if existing.nonEmpty then Some(existing.records.head) else None
    }

  def rename(assetId: String, newFilename: String): Asset =
    txManager.withTransaction {
      val asset: Asset = getById(assetId)

      if asset.isRecycled then throw IllegalOperationException(s"Cannot rename a recycled asset: [$asset]")
      logger.debug(s"Renaming asset [${asset.persistedId}] from [${asset.fileName}] to [$newFilename]")

      val data = Map(
        FieldConst.Asset.FILENAME -> newFilename
      )
      updateById(asset.persistedId, data)

      // The Search document holds the words of the file name
      val renamed = asset.copy(fileName = newFilename)
      app.service.search.reindexAsset(renamed)
      renamed
    }

  override def query(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryNotRecycled(q)
    }

  def queryRecycled(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryRecycled(q)
    }

  def queryAll(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryAll(q)
    }

  def getDanglingAssets: List[Asset] =
    txManager.asReadOnly {
      val danglingAssets = dao.queryAll(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
      danglingAssets.records
    }

  /**
   * Stores the pipeline's measurements and embeddings in the same update that marks the import complete, so an asset is never
   * complete without them. Each stays NULL where the pipeline did not measure or embed.
   */
  def markAsCompleted(asset: Asset): Asset =
    txManager.withTransaction {
      dao.markAsCompleted(asset): Unit
      asset.copy(isPipelineProcessed = true)
    }

  /**
   * Measures and embeds a still image, and its detected faces' sharpness, over one upright decode of the staged file; any other
   * asset comes back as is. The metrics are derived and NULL is a state every reader handles, so a failed measurement is logged
   * and the asset imports unmeasured rather than being dropped. The embedder fails a model at a time the same way, so an image
   * can carry one embedding and not the other. Touches no database.
   *
   * A closed Scene embedder is no failed measurement: the server is stopping, and the image would be stored for good as one the
   * models could not embed. Its `SceneEmbedderClosedException` is thrown on, so the stage drops the asset and its file fails.
   */
  def measureImage(dataAsset: AssetWithData): AssetWithData =
    val asset = dataAsset.asset
    if !asset.isStillImage then
      logger.debug(s"Not measuring $asset")
      return dataAsset

    val detected = dataAsset.detectedFaces.toList.flatMap(_.faces)
    try
      val image = ImageUtil.matFromBytes(dataAsset.bytes)
      val (measurement, embeddings) =
        try (ImageMeasurement.measure(image, detected.map(_._1)), app.sceneEmbedder.embed(image, asset.fileName))
        finally image.release()
      val quality = measurement.quality
      logger.debug(
        s"Measured $asset: score ${quality.score}, sharpness ${quality.sharpness}, hash ${measurement.hash}, " +
          s"embedded by ${SceneModel.values.filter(embeddings.of(_).isDefined).map(_.name).mkString(", ")}")
      dataAsset.copy(
        asset = asset.copy(
          imageQuality = Some(quality),
          perceptualHash = Some(measurement.hash),
          histogram = Some(measurement.histogram),
          sceneEmbeddings = embeddings),
        detectedFaces = dataAsset.detectedFaces.map(_ => DetectedFaces(measurement.faces.zip(detected.map(_._2))))
      )
    catch
      case closed: SceneEmbedderClosedException => throw closed
      case NonFatal(e) =>
        logger.warn(s"Could not measure ${asset.fileName}, importing it unmeasured", e)
        dataAsset

  private def genPreviewData(dataAsset: AssetWithData): Array[Byte] =
    dataAsset.asset.assetType.mediaType match
      case "image" =>
        makeImageThumbnail(dataAsset.bytes, C.AssetView.PREVIEW_BOX_PIXELS)
      case "video" =>
        // One of the Video's Sampled frames, upright already
        val frame = app.service.video.previewFrame(dataAsset.path, videoDuration(dataAsset))
        try makeImageThumbnail(frame.image, orientation = 1, C.AssetView.PREVIEW_BOX_PIXELS)
        finally frame.image.release()
      case _ => new Array[Byte](0)

  /**
   * The display size and, for a Video or an animated GIF, the length. An image's are read from its header, without decoding it; a
   * Video's size has the container's rotation applied, so a portrait phone recording is portrait.
   */
  def getDimensionsAndDuration(dataAsset: AssetWithData): (Int, Int, Option[Long]) /* width, height, duration */ =
    dataAsset.asset.assetType.mediaType match
      case "image" =>
        val input = ImageIO.createImageInputStream(dataAsset.path.toFile)
        try
          val readers = ImageIO.getImageReaders(input)
          if !readers.hasNext then throw ImageException(s"No image reader takes ${dataAsset.path}")
          val reader = readers.next()
          try
            // Seekable and with metadata, so a GIF's frames can be counted and their delays read
            reader.setInput(input)
            // GIF alone: a multi-page TIFF also holds several images, and is no animation
            val durationMs = if reader.getFormatName == "gif" then ImageUtil.gifPlayingTimeMs(reader) else None
            (reader.getWidth(0), reader.getHeight(0), durationMs)
          catch case ex: IOException => throw ImageException(s"Cannot read the header of ${dataAsset.path}: ${ex.getMessage}")
          finally reader.dispose()
        finally input.close()
      case "video" =>
        val info = app.service.video.probe(dataAsset.path)
        (info.width, info.height, Some(info.durationMs))
      case _ =>
        (0, 0, None)

  /** The asset's once the pipeline has extracted it, else probed from the file */
  def videoDuration(dataAsset: AssetWithData): Long =
    dataAsset.asset.durationMs.getOrElse(app.service.video.probe(dataAsset.path).durationMs)

  def addPreview(dataAsset: AssetWithData): Option[MimedPreviewData] =
    val previewData: Array[Byte] = genPreviewData(dataAsset)

    previewData.length match
      case size if size > 0 =>
        logger.trace(s"Preview of asset [${dataAsset.asset.persistedId}]: $size bytes")
        val preview: MimedPreviewData = MimedPreviewData(assetId = dataAsset.asset.persistedId, data = previewData)

        app.service.fileStore.addPreview(preview)

        Some(preview)
      case _ =>
        logger.trace(
          s"No preview for asset [${dataAsset.asset.persistedId}] of media type [${dataAsset.asset.assetType.mediaType}]")
        None

  /**
   * The size of the original turned by its EXIF orientation, the frame an Edit's geometry is in: the stored size, swapped for
   * orientations 5 to 8, which turn it a quarter. Unreadable metadata is taken as upright.
   */
  def uprightSize(asset: Asset): (Int, Int) =
    val file = app.service.fileStore.assetFile(asset.persistedId)
    if !Files.isRegularFile(file) then throw IllegalOperationException(C.Msg.Err.ORIGINAL_MISSING)
    val orientation =
      try ImageUtil.exifOrientation(file)
      catch
        case ex @ (_: ImageProcessingException | _: IOException) =>
          logger.warn(s"Cannot read the metadata of the original of $asset, taking it as upright: ${ex.getMessage}")
          1
    if orientation >= 5 && orientation <= 8 then (asset.height, asset.width) else (asset.width, asset.height)

  def getPreview(assetId: String): MimedPreviewData =
    app.service.fileStore.getPreviewById(assetId)

  // The `getAssetsTo*` reads below lock the rows a library operation may change for the caller's transaction, so each is a write

  /** What the Detail panel shows of an asset, in the trash or not, read in one snapshot */
  def detailOf(assetId: String): DetailPanelData =
    txManager.asReadOnly {
      val asset: Asset = dao.queryAll(new Query().add(FieldConst.ID -> assetId)).records.headOption.getOrElse {
        throw NotFoundException(s"Asset [$assetId] not found")
      }
      val folders = app.service.folder
      val folderPath =
        if asset.folderId.isEmpty || folders.isRootFolder(asset.folderId) then Nil
        else folders.getAncestors(asset.folderId) :+ folders.getById(asset.folderId)

      DetailPanelData(
        asset = asset,
        faces = app.service.person.getAssetFacesForDetail(assetId),
        folderPath = folderPath,
        albums = app.service.album.getAlbumsOfAsset(assetId),
        locations = app.service.location.getLocationsOfAsset(assetId),
        curationSetSize = app.service.curation.setSizeOf(asset)
      )
    }

  def lockPurged(assetId: String): Boolean =
    txManager.withTransaction {
      dao.lockPurged(assetId)
    }

  def shareLockLive(assetIds: Set[String]): Unit =
    txManager.withTransaction {
      dao.shareLockLive(assetIds)
    }

  def getAssetsToRecycle(assetIds: Set[String]): List[Asset] =
    txManager.withTransaction {
      dao.getAssetsToRecycle(assetIds)
    }

  def getAssetsToMove(assetIds: Set[String]): List[Asset] =
    txManager.withTransaction {
      dao.getAssetsToMove(assetIds)
    }

  def getAssetsToRestore(assetIds: Set[String]): List[Asset] =
    txManager.withTransaction {
      dao.getAssetsToRestore(assetIds)
    }

  def getAssetsToPurge(assetIds: Option[Set[String]]): List[Asset] =
    txManager.withTransaction {
      dao.getAssetsToPurge(assetIds)
    }

  def getIdsInFolders(folderIds: Set[String]): Set[String] =
    txManager.asReadOnly {
      dao.getIdsInFolders(folderIds)
    }

  def getPurgePending: List[Asset] =
    txManager.asReadOnly {
      dao.getPurgePending
    }
