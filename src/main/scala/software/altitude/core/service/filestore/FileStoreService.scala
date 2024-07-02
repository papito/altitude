package software.altitude.core.service.filestore

import software.altitude.core.models.Asset
import software.altitude.core.models.Data
import software.altitude.core.models.Preview

trait FileStoreService {
  def addAsset(asset: Asset): Unit
  def getById(id: String): Data

  def addPreview(preview: Preview): Unit
  def getPreviewById(assetId: String): Preview
}
