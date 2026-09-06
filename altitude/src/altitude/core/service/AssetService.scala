package altitude.core.service

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.dao.AssetDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.MimedPreviewData
import altitude.core.util.ImageUtil.makeImageThumbnail
import altitude.core.util.Query
import altitude.core.util.QueryResult

class AssetService(val app: Altitude) extends BaseService[Asset]:
  override protected val dao: AssetDao = app.DAO.asset

  def setRecycledProp(asset: Asset, isRecycled: Boolean): Unit =
    if asset.isRecycled == isRecycled then return

    txManager.withTransaction {
      logger.info(s"Setting asset [${asset.persistedId}] recycled flag to [$isRecycled]")

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

      val data = Map(
        FieldConst.Asset.FILENAME -> newFilename
      )
      updateById(asset.persistedId, data)

      asset.copy(fileName = newFilename)
    }

  override def query(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryNotRecycled(q)
    }

  def queryTriaged(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryTriaged(q)
    }

  def queryRecycled(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryRecycled(q)
    }

  def queryAll(q: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      dao.queryAll(q)
    }

  def pruneDanglingAssets(): Unit =
    txManager.withTransaction {
      dao.deleteByQuery(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
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
        makeImageThumbnail(dataAsset.data, C.AssetView.PREVIEW_BOX_PIXELS)
      case _ => new Array[Byte](0)

  def getDimensions(dataAsset: AssetWithData): (Int, Int) /* width, height */ =
    dataAsset.asset.assetType.mediaType match
      case "image" =>
        val img: BufferedImage = ImageIO.read(new ByteArrayInputStream(dataAsset.data))
        (img.getWidth, img.getHeight)
      case _ =>
        // Default to 0, 0 for unsupported media types
        (0, 0)

  def addPreview(dataAsset: AssetWithData): Option[MimedPreviewData] =
    val previewData: Array[Byte] = genPreviewData(dataAsset)

    previewData.length match
      case size if size > 0 =>
        val preview: MimedPreviewData = MimedPreviewData(assetId = dataAsset.asset.persistedId, data = previewData)

        app.service.fileStore.addPreview(preview)

        Some(preview)
      case _ => None

  def getPreview(assetId: String): MimedPreviewData =
    app.service.fileStore.getPreviewById(assetId)

  def getAssetsToRecycle(assetIds: Set[String]): List[Asset] =
    txManager.asReadOnly {
      dao.getAssetsToRecycle(assetIds)
    }

  def getAssetsToMove(assetIds: Set[String], folderId: String): List[Asset] =
    txManager.asReadOnly {
      dao.getAssetsToMove(assetIds, folderId)
    }

  def countByFolder(): Map[String, Int] =
    txManager.asReadOnly {
      dao.countByFolder()
    }
