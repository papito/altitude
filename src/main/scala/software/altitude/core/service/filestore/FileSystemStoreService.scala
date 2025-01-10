package software.altitude.core.service.filestore

import java.io._
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import software.altitude.core.{ Const => C }
import software.altitude.core.Altitude
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.StorageException
import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.FaceImages
import software.altitude.core.models.MimedAssetData
import software.altitude.core.models.MimedFaceData
import software.altitude.core.models.MimedPreviewData

class FileSystemStoreService(app: Altitude) extends FileStoreService {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  override def getAssetById(id: String): MimedAssetData = {
    val path = filePath(id)
    val srcFile: File = new File(path)

    var byteArray: Option[Array[Byte]] = None

    try {
      byteArray = Some(FileUtils.readFileToByteArray(srcFile))
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")
    }

    MimedAssetData(assetId = id, data = byteArray.get, mimeType = "application/octet-stream")
  }

  override def addAsset(dataAsset: AssetWithData): Unit = {
    val destFile = new File(filePath(dataAsset.asset.persistedId))
    logger.info(s"Creating asset [$dataAsset.asset] on file system at [$destFile]")

    try {
      FileUtils.writeByteArrayToFile(destFile, dataAsset.data)
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error creating [$dataAsset.asset] @ [$destFile]: $ex]")
    }
  }

  override def addPreview(preview: MimedPreviewData): Unit = {
    logger.info(s"Adding preview for asset ${preview.assetId}")

    // get the full path to our preview file
    val destFilePath = previewFilePath(preview.assetId)
    // parse out the dir path
    val dirPath = FilenameUtils.getFullPath(destFilePath)

//    FileUtils.forceMkdir(new File(dirPath))

    try {
      FileUtils.writeByteArrayToFile(new File(destFilePath), preview.data)
    } catch {
      case _: IOException => logger.error(s"Could not save preview data to [$destFilePath]")
    }
  }

  override def getPreviewById(assetId: String): MimedPreviewData = {
    val f: File = new File(previewFilePath(assetId))

    if (!f.isFile) {
      throw NotFoundException(s"Cannot find preview for asset '$assetId'")
    }

    val byteArray = FileUtils.readFileToByteArray(f)
    val is: InputStream = new ByteArrayInputStream(byteArray)

    try {
      MimedPreviewData(assetId = assetId, data = byteArray)
    } finally {
      if (is != null) is.close()
    }
  }

  private def previewFilePath(assetId: String): String = {
    val dirName = assetId.substring(0, 2)
    new File(new File(previewDataPath, dirName).getPath, s"$assetId.${MimedPreviewData.FILE_EXTENSION}").getPath
  }

  private def repositoryDataPath: String = {
    val reposDataPath = FilenameUtils.concat(app.dataPath, C.DataStore.REPOSITORIES)
    val repositoryDir = RequestContext.getRepository.persistedId
    FilenameUtils.concat(reposDataPath, repositoryDir)
  }

  private def previewDataPath: String =
    new File(repositoryDataPath, C.DataStore.PREVIEW).getPath

  private def filePath(assetId: String): String = {
    val filesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FILES)
    val partitionedFilesPath = FilenameUtils.concat(filesPath, assetId.substring(0, 2))
    FilenameUtils.concat(partitionedFilesPath, assetId)
  }

  private def displayFacePath(faceId: String): String = {
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-display.png")
  }

  private def detectedFacePath(faceId: String): String = {
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-detected.png")
  }

  private def alignedFacePath(faceId: String): String = {
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-aligned.png")
  }

  private def alignedGreyscaleFacePath(faceId: String): String = {
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-aligned-gs.png")
  }

  override def addFace(face: Face, faceImages: FaceImages): Unit = {
    logger.debug(s"Creating face [${face.persistedId}] on file system")

    val destDisplayFile = new File(displayFacePath(face.persistedId))
    val detectedFaceFile = new File(detectedFacePath(face.persistedId))
    val alignedGreyscaleFile = new File(alignedGreyscaleFacePath(face.persistedId))
    val alignedFile = new File(alignedFacePath(face.persistedId))

    try {
      FileUtils.writeByteArrayToFile(destDisplayFile, faceImages.displayImage)
      FileUtils.writeByteArrayToFile(detectedFaceFile, faceImages.image)
      FileUtils.writeByteArrayToFile(alignedFile, faceImages.alignedImage)
      FileUtils.writeByteArrayToFile(alignedGreyscaleFile, faceImages.alignedImageGs)
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error creating [$face] @ [$destDisplayFile]: $ex]")
    }
  }

  override def getDisplayFaceById(faceId: String): MimedFaceData = {
    val path = displayFacePath(faceId)
    val srcFile: File = new File(path)

    var byteArray: Option[Array[Byte]] = None

    try {
      byteArray = Some(FileUtils.readFileToByteArray(srcFile))
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")
    }

    MimedFaceData(data = byteArray.get)
  }

  override def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData = {
    val path = alignedGreyscaleFacePath(faceId)
    val srcFile: File = new File(path)

    var byteArray: Option[Array[Byte]] = None

    try {
      byteArray = Some(FileUtils.readFileToByteArray(srcFile))
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")
    }

    MimedFaceData(data = byteArray.get)
  }
}
