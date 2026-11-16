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

  /**
   * Writes the asset's edited image of `kind` (`Const.DataStore.PREVIEW` or `DISPLAY`) to a temporary file beside it, flushed to
   * disk, for [[publishEditedImage]]. Renders stage before their transaction opens, so the write holds no lock; one render of an
   * asset runs at a time, so one temporary file suffices.
   */
  def stageEditedImage(assetId: String, kind: String, data: Array[Byte]): Unit

  /**
   * Moves the staged edited image over the stored one in one atomic rename, so a reader sees the old image or the new one, whole.
   * Called under the asset's locks, before the commit that names the new revision.
   */
  def publishEditedImage(assetId: String, kind: String): Unit

  /** Deletes the staged edited images of both kinds that a render did not publish */
  def discardStagedEditedImages(assetId: String): Unit

  /** The asset's edited image of `kind`; a `NotFoundException` when it has none */
  def getEditedImage(assetId: String, kind: String): MimedPreviewData

  /** Where the asset's edited image of `kind` is stored; it need not exist */
  def editedImagePath(assetId: String, kind: String): Path

  /** Deletes the asset's edited images of both kinds, and any staged file an interrupted render left */
  def deleteEditedImages(assetId: String): Unit

  def addFace(face: Face, faceImages: FaceImages): Unit
  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
  def getDetectedFaceById(faceId: String): MimedFaceData
  def getAlignedFaceById(faceId: String): MimedFaceData

  def purgeFaceById(id: String): Unit
