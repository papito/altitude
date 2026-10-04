package altitude.core.service

import java.io.IOException
import javax.imageio.ImageIO
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.MimedPreviewData
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

  def getByChecksum(checksum: Int): Option[Asset] =
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

  def markAsCompleted(asset: Asset): Asset =
    txManager.withTransaction {
      val updateData = Map(
        FieldConst.Asset.IS_PIPELINE_PROCESSED -> true
      )

      updateById(asset.persistedId, updateData)
      asset.copy(isPipelineProcessed = true)
    }

  private def genPreviewData(dataAsset: AssetWithData): Array[Byte] =
    dataAsset.asset.assetType.mediaType match
      case "image" =>
        makeImageThumbnail(dataAsset.bytes, C.AssetView.PREVIEW_BOX_PIXELS)
      case "video" =>
        // The Preview of a Video is one of its Sampled frames, thumbnailed like an image
        val frame = app.service.video.previewFrame(dataAsset.path, videoDuration(dataAsset))
        val png = new MatOfByte()
        Imgcodecs.imencode(".png", frame.image, png)
        frame.image.release()
        val bytes = png.toArray
        png.release()
        makeImageThumbnail(bytes, C.AssetView.PREVIEW_BOX_PIXELS)
      case _ => new Array[Byte](0)

  /**
   * The display size and, for a Video or an animated GIF, the length. An image's size is read from its header, without decoding
   * it, which face detection and the preview each do, and so is an animated GIF's playing time; a Video's size has the
   * container's rotation applied, so a portrait phone recording is portrait. An image no reader takes, or whose header it cannot
   * read, is an [[ImageException]], a Video FFmpeg cannot open a [[altitude.core.VideoException]].
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
            // Seekable and with its metadata, so a GIF's frames can be counted and their delays read
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
        // Default to 0, 0 for unsupported media types
        (0, 0, None)

  /** A Video's length: the asset's once the pipeline has extracted it, else probed from the file */
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

  def getPreview(assetId: String): MimedPreviewData =
    app.service.fileStore.getPreviewById(assetId)

  // The rows a library operation may change, locked for the caller's transaction, so each is a write: see `AssetDao`

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
