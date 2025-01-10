package software.altitude.core.service.filestore

import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.FaceImages
import software.altitude.core.models.MimedAssetData
import software.altitude.core.models.MimedFaceData
import software.altitude.core.models.MimedPreviewData

trait FileStoreService {
  def addAsset(assetWithData: AssetWithData): Unit
  def getAssetById(id: String): MimedAssetData

  def addPreview(preview: MimedPreviewData): Unit
  def getPreviewById(assetId: String): MimedPreviewData

  def addFace(face: Face, faceImages: FaceImages): Unit

  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
}
