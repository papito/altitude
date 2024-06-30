package software.altitude.core.service.filestore

import software.altitude.core.models.Asset
import software.altitude.core.models.Data
import software.altitude.core.models.Preview

trait FileStoreService {
  def getById(id: String): Data
  def addAsset(asset: Asset): Unit
  def addPreview(preview: Preview): Unit
  def getPreviewById(assetId: String): Preview
  protected def getAssetPath(asset: Asset): String

}
