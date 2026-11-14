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
   * Stores an edited rendition of the asset (`kind` is `Const.DataStore.PREVIEW` or `DISPLAY`) under the revision `rev`, written
   * whole or not at all: a temporary file in the same directory, then a move. A revision already stored is refused (a
   * `StorageException`), so the bytes under a revision never change.
   */
  def addEditRendition(assetId: String, rev: Int, kind: String, data: Array[Byte]): Unit

  /** The highest revision of the asset's stored renditions, 0 for none: one a render that failed after storing it may leave */
  def highestEditRendition(assetId: String): Int

  /** The asset's edited rendition of `kind` at the revision `rev`; a `NotFoundException` when it is not stored */
  def getEditRendition(assetId: String, rev: Int, kind: String): MimedPreviewData

  /** Where the asset's edited rendition of `kind` at the revision `rev` is stored; it need not exist */
  def editRenditionPath(assetId: String, rev: Int, kind: String): Path

  /**
   * Deletes the asset's edited renditions of every revision below `below`, of both kinds. A render passes the revision it stored
   * (or, rendering the original, one past the last used), so a later render's files, which carry a higher revision, are never
   * deleted by an earlier one's purge, however their commits and purges interleave.
   */
  def purgeEditRenditions(assetId: String, below: Int): Unit

  def addFace(face: Face, faceImages: FaceImages): Unit
  def getDisplayFaceById(faceId: String): MimedFaceData
  def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData
  def getDetectedFaceById(faceId: String): MimedFaceData
  def getAlignedFaceById(faceId: String): MimedFaceData

  def purgeFaceById(id: String): Unit
