package software.altitude.core.service.filestore

import software.altitude.core.models.Asset
import software.altitude.core.models.Data
import software.altitude.core.models.Folder
import software.altitude.core.models.Preview

trait FileStoreService {
  val pathSeparator: String

  def getById(id: String)
             : Data

  def createPath(relPath: String): Unit

  def addAsset(asset: Asset): Unit

  def purgeAsset(asset: Asset): Unit

  def moveAsset(srcAsset: Asset, destAsset: Asset): Unit

  def recycleAsset(asset: Asset): Unit

  def restoreAsset(asset: Asset): Unit

  def addFolder(folder: Folder): Unit

  def deleteFolder(folder: Folder): Unit

  def renameFolder(folder: Folder, newName: String): Unit

  def moveFolder(folder: Folder, newParent: Folder): Unit

  def getFolderPath(name: String, parentId: String)
                         : String

  def calculateNextAvailableFilename(asset: Asset): String

  def getPathWithNewFilename(asset: Asset, newFilename: String)
                            : String

  def getAssetPath(asset: Asset): String

  def getRecycledAssetPath(asset: Asset): String

  def sortedFolderPath: String

  def triageFolderPath: String

  def trashFolderPath: String

  def landfillFolderPath: String

  def addPreview(preview: Preview): Unit

  def getPreviewById(assetId: String): Preview

  def assemblePath(pathComponents: List[String]): String
}
