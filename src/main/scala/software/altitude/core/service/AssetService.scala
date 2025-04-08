package software.altitude.core.service
import software.altitude.core.{Altitude, FieldConst, IllegalOperationException, Const => C}
import software.altitude.core.dao.AssetDao
import software.altitude.core.models.{Asset, AssetWithData, MimedPreviewData}
import software.altitude.core.util.ImageUtil.makeImageThumbnail
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class AssetService(val app: Altitude) extends BaseService[Asset] {
  override protected val dao: AssetDao = app.DAO.asset

  def setRecycledProp(asset: Asset, isRecycled: Boolean): Unit = {
    if (asset.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction[Unit] {
      logger.info(s"Setting asset [${asset.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(asset.persistedId, Map(FieldConst.Asset.IS_RECYCLED -> isRecycled, FieldConst.Asset.IS_TRIAGED -> false))
    }
  }

  def getByChecksum(checksum: Int): Option[Asset] = {
    txManager.asReadOnly[Option[Asset]] {
      val q = new Query(params = Map(FieldConst.Asset.CHECKSUM -> checksum)).withRepository()
      val existing = query(q)
      if (existing.nonEmpty) Some(existing.records.head: Asset) else None
    }
  }

  def rename(assetId: String, newFilename: String): Asset = {
    txManager.withTransaction[Asset] {
      val asset: Asset = getById(assetId)

      if (asset.isRecycled) {
        throw IllegalOperationException(s"Cannot rename a recycled asset: [$asset]")
      }

      val data = Map(
        FieldConst.Asset.FILENAME -> newFilename
      )
      updateById(asset.persistedId, data)

      asset.copy(fileName = newFilename)
    }
  }

  override def query(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryNotRecycled(q.withRepository())
    }
  }

  def queryTriaged(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryTriaged(q.withRepository())
    }
  }

  def queryRecycled(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryRecycled(q.withRepository())
    }
  }

  def queryAll(q: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.queryAll(q.withRepository())
    }
  }

  def pruneDanglingAssets(): Unit = {
    txManager.withTransaction {
      dao.deleteByQuery(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
    }
  }

  def getDanglingAssets: List[Asset] = {
    txManager.asReadOnly {
      val danglingAssets = dao.queryAll(new Query(Map(FieldConst.Asset.IS_PIPELINE_PROCESSED -> false)))
      danglingAssets.records.map(Asset.fromJson(_))
    }
  }

  def markAsCompleted(asset: Asset): Asset = {
    txManager.withTransaction {
      val updateData = Map(
        FieldConst.Asset.IS_PIPELINE_PROCESSED -> true
      )

      updateById(asset.persistedId, updateData)
      asset.copy(isPipelineProcessed = true)
    }
  }

  private def genPreviewData(dataAsset: AssetWithData): Array[Byte] = {
    dataAsset.asset.assetType.mediaType match {
      case "image" =>
        makeImageThumbnail(dataAsset.data, C.AssetView.PREVIEW_BOX_PIXELS)
      case _ => new Array[Byte](0)
    }
  }

  def getDimensions(dataAsset: AssetWithData): (Int, Int) /* width, height */ = {
    dataAsset.asset.assetType.mediaType match {
      case "image" =>
        val img: BufferedImage = ImageIO.read(new ByteArrayInputStream(dataAsset.data))
        (img.getWidth, img.getHeight)
      case _ =>
        // Default to 0, 0 for unsupported media types
        (0, 0)
    }
  }

  def addPreview(dataAsset: AssetWithData): Option[MimedPreviewData] = {
    val previewData: Array[Byte] = genPreviewData(dataAsset)

    previewData.length match {
      case size if size > 0 =>
        val preview: MimedPreviewData = MimedPreviewData(assetId = dataAsset.asset.persistedId, data = previewData)

        app.service.fileStore.addPreview(preview)

        Some(preview)
      case _ => None
    }
  }

  def getPreview(assetId: String): MimedPreviewData = {
    app.service.fileStore.getPreviewById(assetId)
  }


}
