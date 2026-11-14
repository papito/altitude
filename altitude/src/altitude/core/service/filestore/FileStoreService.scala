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
   * Writes the asset's edited image of `kind` (`Const.DataStore.PREVIEW`, the edited Preview, or `DISPLAY`, the Display image) to
   * its temporary file beside the image, flushed to the disk, for [[publishEditedImage]] to move over the image. A render stages
   * its images before its transaction opens, so the writing holds no lock; one render of an asset runs at a time.
   */
  def stageEditedImage(assetId: String, kind: String, data: Array[Byte]): Unit

  /**
   * Moves the asset's staged edited image of `kind` over the one stored before, in one atomic rename, so a reader sees the old
   * image or the new one, whole. A render publishes under the asset's locks, before the commit that names the new revision.
   */
  def publishEditedImage(assetId: String, kind: String): Unit

  /** Deletes the asset's staged edited images, of both kinds, a render did not publish */
  def discardStagedEditedImages(assetId: String): Unit

  /** The asset's edited image of `kind`; a `NotFoundException` when it has none */
  def getEditedImage(assetId: String, kind: String): MimedPreviewData

  /** Where the asset's edited image of `kind` is stored; it need not exist */
  def editedImagePath(assetId: String, kind: String): Path

  /** Deletes the asset's edited images, of both kinds, and what a write of one cut off left */
  def deleteEditedImages(assetId: String): Unit

  def addFace(face: Face, faceImages: FaceImages): Unit
  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
  def getDetectedFaceById(faceId: String): MimedFaceData
  def getAlignedFaceById(faceId: String): MimedFaceData

  def purgeFaceById(id: String): Unit
