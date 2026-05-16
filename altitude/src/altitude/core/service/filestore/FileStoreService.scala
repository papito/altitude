package altitude.core.service.filestore

import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.MimedAssetData
import altitude.core.models.MimedFaceData
import altitude.core.models.MimedPreviewData

trait FileStoreService:
  def addAsset(assetWithData: AssetWithData): Unit
  def getAssetById(id: String): MimedAssetData
  def purgeAssetById(id: String): Unit

  def addPreview(preview: MimedPreviewData): Unit
  def getPreviewById(assetId: String): MimedPreviewData

  def addFace(face: Face, faceImages: FaceImages): Unit
  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
  def getDetectedFaceById(faceId: String): MimedFaceData
  def getAlignedFaceById(faceId: String): MimedFaceData

  def purgeFaceById(id: String): Unit
