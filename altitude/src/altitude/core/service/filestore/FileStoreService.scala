package altitude.core.service.filestore

import java.nio.file.Path

import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.MimedAssetData
import altitude.core.models.MimedFaceData
import altitude.core.models.MimedPreviewData

trait FileStoreService:
  /** Renames the asset's staged file into the store; the asset's data is at [[assetFile]] after */
  def addAsset(assetWithData: AssetWithData): Unit

  /** The stored original of an asset, for streaming; it need not exist */
  def assetFile(assetId: String): Path
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
