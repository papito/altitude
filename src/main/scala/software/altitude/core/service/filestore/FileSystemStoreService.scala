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

    val byteArray = getBinaryData(srcFile)

    MimedAssetData(assetId = id, data = byteArray.get, mimeType = "application/octet-stream")
  }
  override def getPreviewById(assetId: String): MimedPreviewData = {
    val f: File = new File(previewFilePath(assetId))
    val byteArray = getBinaryData(f)
    MimedPreviewData(assetId = assetId, data = byteArray.get)
  }

  override def addAsset(dataAsset: AssetWithData): Unit = {
    val destFile = new File(filePath(dataAsset.asset.persistedId))
    putBinaryData(destFile, dataAsset.data)
  }

  override def addFace(face: Face, faceImages: FaceImages): Unit = {
    logger.debug(s"Creating face [${face.persistedId}] on file system")

    val destDisplayFile = new File(displayFacePath(face.persistedId))
    val detectedFaceFile = new File(detectedFacePath(face.persistedId))
    val alignedGreyscaleFile = new File(alignedGreyscaleFacePath(face.persistedId))
    val alignedFile = new File(alignedFacePath(face.persistedId))

    putBinaryData(destDisplayFile, faceImages.displayImage)
    putBinaryData(detectedFaceFile, faceImages.image)
    putBinaryData(alignedFile, faceImages.alignedImage)
    putBinaryData(alignedGreyscaleFile, faceImages.alignedImageGs)
  }

  override def addPreview(preview: MimedPreviewData): Unit = {
    val destFilePath = previewFilePath(preview.assetId)
    putBinaryData(new File(destFilePath), preview.data)
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

  override def getDisplayFaceById(faceId: String): MimedFaceData = {
    getMimedFaceData(displayFacePath(faceId))
  }

  override def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData = {
    getMimedFaceData(alignedGreyscaleFacePath(faceId))
  }

  override def getDetectedFaceById(faceId: String): MimedFaceData = {
    getMimedFaceData(detectedFacePath(faceId))
  }

  override def getAlignedFaceById(faceId: String): MimedFaceData ={
    getMimedFaceData(alignedFacePath(faceId))
  }

  private def getMimedFaceData(path: String): MimedFaceData = {
    val srcFile: File = new File(path)
    val byteArray = getBinaryData(srcFile)
    MimedFaceData(data = byteArray.get)
  }

  override def purgeAssetById(id: String): Unit = {
    val paths = List(
      filePath(id),
      previewFilePath(id)
    )

    for (path <- paths) {
      val srcFile = new File(path)

      try {
        if (srcFile.isFile) {
          srcFile.delete()
        }
      } catch {
        case ex: IOException =>
          logger.error(s"Error deleting file for asset id [$id]: $ex")
      }
    }
  }

  override def purgeFaceById(id: String): Unit = {
    val paths = List(
      detectedFacePath(id),
      displayFacePath(id),
      alignedFacePath(id),
      alignedGreyscaleFacePath(id)
    )

    for (path <- paths) {
      val srcFile = new File(path)

      try {
        if (srcFile.isFile) {
          srcFile.delete()
        }
      } catch {
        case ex: IOException =>
          logger.error(s"Error deleting file for face id [$id]: $ex")
      }
    }
  }

  private def getBinaryData(srcFile: File): Option[Array[Byte]] = {
    if (!srcFile.isFile) {
      throw NotFoundException(s"Cannot find file $srcFile")
    }

    try {
      Some(FileUtils.readFileToByteArray(srcFile))
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")
    }
  }

  private def putBinaryData(destFile: File, data: Array[Byte]): Unit = {
    try {
      FileUtils.writeByteArrayToFile(destFile, data)
    } catch {
      case ex: IOException =>
        throw StorageException(s"Error writing $destFile: $ex]")
    }

  }
}
