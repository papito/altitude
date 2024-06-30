package software.altitude.core.service.filestore

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.StorageException
import software.altitude.core.models.Asset
import software.altitude.core.models.Data
import software.altitude.core.models.Preview
import software.altitude.core.{Const => C}

import java.io._

class FileSystemStoreService(app: Altitude) extends FileStoreService {
  private final val log = LoggerFactory.getLogger(getClass)

  override def getById(id: String): Data = {
    val asset: Asset = app.service.library.getById(id)
    val path = getAssetPath(asset)
    val srcFile: File = fileFromRelPath(path)

    var byteArray: Option[Array[Byte]] = None

    try {
      byteArray = Some(FileUtils.readFileToByteArray(srcFile))
    }
    catch {
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")
    }

    Data(
      assetId = id,
      data = byteArray.get,
      mimeType = "application/octet-stream")
  }

  override def addAsset(asset: Asset): Unit = {
    val destFile = fileFromAsset(asset)
    log.debug(s"Creating asset [$asset] on file system at [$destFile]")

    try {
      FileUtils.writeByteArrayToFile(destFile, asset.data)
    }
    catch {
      case ex: IOException =>
        throw StorageException(s"Error creating [$asset] @ [$destFile]: $ex]")
    }
  }

  override def getAssetPath(asset: Asset): String = {
    FilenameUtils.concat(asset.persistedId, asset.fileName)
  }

  override def addPreview(preview: Preview): Unit = {
    log.info(s"Adding preview for asset ${preview.assetId}")

    // get the full path to our preview file
    val destFilePath = previewFilePath(preview.assetId)
    // parse out the dir path
    val dirPath = FilenameUtils.getFullPath(destFilePath)

    FileUtils.forceMkdir(new File(dirPath))

    try {
      FileUtils.writeByteArrayToFile(new File(destFilePath), preview.data)
    }
    catch {
      case _: IOException => log.error(s"Could not save preview data to [$destFilePath]")
    }
  }

  override def getPreviewById(assetId: String): Preview = {
    val f: File = new File(previewFilePath(assetId))

    if (!f.isFile) {
      throw NotFoundException(s"Cannot find preview for asset '$assetId'")
    }

    val byteArray = FileUtils.readFileToByteArray(f)
    val is: InputStream = new ByteArrayInputStream(byteArray)

    try {
      Preview(
        assetId = assetId,
        data = byteArray,
        mimeType = "application/octet-stream")
    }
    finally {
      if (is != null) is.close()
    }
  }

  private def previewFilePath(assetId: String): String = {
    val dirName = assetId.substring(0, 2)
    new File(new File(previewDirPath, dirName).getPath, assetId + ".png").getPath
  }

  private def previewDirPath: String =
    new File(RequestContext.repository.value.get.fileStoreConfig(C.Repository.Config.PATH), "preview").getPath

  /**
   * Get the absolute path to the asset on file system,
   * given path relative to repository root
   */
  private def fileFromRelPath(relativePath: String): File = {
    val repositoryRoot = RequestContext.repository.value.get.fileStoreConfig(C.Repository.Config.PATH)
    new File(repositoryRoot, relativePath)
  }

  private def fileFromAsset(asset: Asset): File = {
    val repositoryRoot = RequestContext.repository.value.get.fileStoreConfig(C.Repository.Config.PATH)
    val absoluteFilesPath = FilenameUtils.concat(repositoryRoot, "files")
    val absoluteFilePartitionPath = FilenameUtils.concat(absoluteFilesPath, asset.persistedId.substring(0, 2))
    new File(absoluteFilePartitionPath, asset.persistedId)
  }
}
