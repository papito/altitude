package software.altitude.core.service.filestore

import software.altitude.core.models.{AssetWithData, Face, MimedAssetData, MimedFaceData, MimedPreviewData}

trait FileStoreService {
  def addAsset(assetWithData: AssetWithData): Unit
  def getAssetById(id: String): MimedAssetData

  def addPreview(preview: MimedPreviewData): Unit
  def getPreviewById(assetId: String): MimedPreviewData

  def addFace(face: Face): Unit
  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
}
