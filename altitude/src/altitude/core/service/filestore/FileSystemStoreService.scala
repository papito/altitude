package altitude.core.service.filestore

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.util.Using

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.StorageException
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.MimedAssetData
import altitude.core.models.MimedFaceData
import altitude.core.models.MimedPreviewData

class FileSystemStoreService(app: Altitude) extends FileStoreService:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  override def getAssetById(id: String): MimedAssetData =
    val path = filePath(id)
    val srcFile: File = new File(path)

    val byteArray = getBinaryData(srcFile)

    MimedAssetData(assetId = id, data = byteArray.get, mimeType = "application/octet-stream")

  override def getPreviewById(assetId: String): MimedPreviewData =
    val f: File = new File(previewFilePath(assetId))
    val byteArray = getBinaryData(f)
    MimedPreviewData(assetId = assetId, data = byteArray.get)

  override def addAsset(dataAsset: AssetWithData): Unit =
    val destFile = assetFile(dataAsset.asset.persistedId)
    try
      Files.createDirectories(destFile.getParent)
      // Staging is on this filesystem, so this is a rename
      Files.move(dataAsset.path, destFile, StandardCopyOption.REPLACE_EXISTING)
      logger.trace(s"Stored ${dataAsset.path} as $destFile")
    catch
      case ex: IOException =>
        throw StorageException(s"Error storing ${dataAsset.path} as $destFile: $ex")

  override def assetFile(assetId: String): Path = Path.of(filePath(assetId))

  override def addFace(face: Face, faceImages: FaceImages): Unit =
    logger.trace(s"Creating face [${face.persistedId}] on file system")

    val destDisplayFile = new File(displayFacePath(face.persistedId))
    val detectedFaceFile = new File(detectedFacePath(face.persistedId))
    val alignedGreyscaleFile = new File(alignedGreyscaleFacePath(face.persistedId))
    val alignedFile = new File(alignedFacePath(face.persistedId))

    putBinaryData(destDisplayFile, faceImages.displayImage)
    putBinaryData(detectedFaceFile, faceImages.image)
    putBinaryData(alignedFile, faceImages.alignedImage)
    putBinaryData(alignedGreyscaleFile, faceImages.alignedImageGs)

  override def addPreview(preview: MimedPreviewData): Unit =
    val destFilePath = previewFilePath(preview.assetId)
    logger.trace(s"Storing the preview of asset [${preview.assetId}] as $destFilePath")

    putBinaryData(new File(destFilePath), preview.data)

  override def stageEditedImage(assetId: String, kind: String, data: Array[Byte]): Unit =
    val staged = temporaryPathOf(editedImagePath(assetId, kind))
    logger.trace(s"Staging the edited $kind of asset [$assetId] as $staged")
    try
      Files.createDirectories(staged.getParent)
      Using.resource(
        FileChannel.open(staged, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        channel =>
          val buffer = ByteBuffer.wrap(data)
          while buffer.hasRemaining do channel.write(buffer): Unit
          // On the disk before the rename that publishes it, so a crash after the commit never leaves a truncated image
          channel.force(true)
      }
    catch
      case ex: IOException =>
        throw StorageException(s"Error staging the edited $kind of asset [$assetId]: $ex")

  override def publishEditedImage(assetId: String, kind: String): Unit =
    val destination = editedImagePath(assetId, kind)
    try Files.move(temporaryPathOf(destination), destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case ex: IOException =>
        throw StorageException(s"Error publishing the edited $kind of asset [$assetId]: $ex")

  override def discardStagedEditedImages(assetId: String): Unit =
    for kind <- List(C.DataStore.PREVIEW, C.DataStore.DISPLAY) do
      deleteFile(temporaryPathOf(editedImagePath(assetId, kind)), assetId)

  override def getEditedImage(assetId: String, kind: String): MimedPreviewData =
    MimedPreviewData(assetId = assetId, data = getBinaryData(editedImagePath(assetId, kind).toFile).get)

  /**
   * The edited Preview beside the original's Preview, `<repo>/preview/<id[0:2]>/<id>-edited.webp`, and the Display image in a
   * directory of its own, `<repo>/display/<id[0:2]>/<id>.webp`
   */
  override def editedImagePath(assetId: String, kind: String): Path =
    val name = kind match
      case C.DataStore.PREVIEW => s"$assetId-edited"
      case C.DataStore.DISPLAY => assetId
      case _ => throw IllegalArgumentException(s"No edited image of kind [$kind]")
    Path.of(repositoryDataPath, kind, assetId.substring(0, 2), s"$name.${MimedPreviewData.FILE_EXTENSION}")

  override def deleteEditedImages(assetId: String): Unit =
    for kind <- List(C.DataStore.PREVIEW, C.DataStore.DISPLAY) do
      val path = editedImagePath(assetId, kind)
      deleteFile(path, assetId)
      deleteFile(temporaryPathOf(path), assetId)

  /** The temporary file an edited image is staged in before its move to `destination` ([[stageEditedImage]]): hidden, beside it */
  private def temporaryPathOf(destination: Path): Path = destination.resolveSibling(s".${destination.getFileName}.tmp")

  private def deleteFile(file: Path, assetId: String): Unit =
    try Files.deleteIfExists(file): Unit
    catch
      case ex: IOException =>
        logger.error(s"Error deleting file $file of asset [$assetId]: $ex")

  private def previewFilePath(assetId: String): String =
    val dirName = assetId.substring(0, 2)
    new File(new File(previewDataPath, dirName).getPath, s"$assetId.${MimedPreviewData.FILE_EXTENSION}").getPath

  private def repositoryDataPath: String =
    val reposDataPath = FilenameUtils.concat(app.dataPath, C.DataStore.REPOSITORIES)
    val repositoryDir = RequestContext.getRepository.persistedId
    FilenameUtils.concat(reposDataPath, repositoryDir)

  private def previewDataPath: String =
    new File(repositoryDataPath, C.DataStore.PREVIEW).getPath

  private def filePath(assetId: String): String =
    val filesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FILES)
    val partitionedFilesPath = FilenameUtils.concat(filesPath, assetId.substring(0, 2))
    FilenameUtils.concat(partitionedFilesPath, assetId)

  private def displayFacePath(faceId: String): String =
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-display.png")

  private def detectedFacePath(faceId: String): String =
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-detected.png")

  private def alignedFacePath(faceId: String): String =
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-aligned.png")

  private def alignedGreyscaleFacePath(faceId: String): String =
    val facesPath = FilenameUtils.concat(repositoryDataPath, C.DataStore.FACES)
    val partitionedFacesPath = FilenameUtils.concat(facesPath, faceId.substring(0, 2))
    FilenameUtils.concat(partitionedFacesPath, s"$faceId-aligned-gs.png")

  override def getDisplayFaceById(faceId: String): MimedFaceData =
    getMimedFaceData(displayFacePath(faceId))

  override def getAlignedGreyscaleFaceById(faceId: String): MimedFaceData =
    getMimedFaceData(alignedGreyscaleFacePath(faceId))

  override def getDetectedFaceById(faceId: String): MimedFaceData =
    getMimedFaceData(detectedFacePath(faceId))

  override def getAlignedFaceById(faceId: String): MimedFaceData =
    getMimedFaceData(alignedFacePath(faceId))

  private def getMimedFaceData(path: String): MimedFaceData =
    val srcFile: File = new File(path)
    val byteArray = getBinaryData(srcFile)
    MimedFaceData(data = byteArray.get)

  override def purgeAssetById(id: String): Unit =
    val paths = List(
      filePath(id),
      previewFilePath(id)
    )

    for path <- paths do
      val srcFile = new File(path)

      try if srcFile.isFile then srcFile.delete()
      catch
        case ex: IOException =>
          logger.error(s"Error deleting file for asset id [$id]: $ex")

    // Its edited images, and what a write of one cut off left
    deleteEditedImages(id)

  override def purgeFaceById(id: String): Unit =
    val paths = List(
      detectedFacePath(id),
      displayFacePath(id),
      alignedFacePath(id),
      alignedGreyscaleFacePath(id)
    )

    for path <- paths do
      val srcFile = new File(path)

      try if srcFile.isFile then srcFile.delete()
      catch
        case ex: IOException =>
          logger.error(s"Error deleting file for face id [$id]: $ex")

  private def getBinaryData(srcFile: File): Option[Array[Byte]] =
    if !srcFile.isFile then throw NotFoundException(s"Cannot find file $srcFile")

    try Some(FileUtils.readFileToByteArray(srcFile))
    catch
      // Deleted since it was looked for: a purge, or an Edit reverted to the original
      case _: NoSuchFileException | _: FileNotFoundException => throw NotFoundException(s"Cannot find file $srcFile")
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")

  private def putBinaryData(destFile: File, data: Array[Byte]): Unit =
    try FileUtils.writeByteArrayToFile(destFile, data)
    catch
      case ex: IOException =>
        throw StorageException(s"Error writing $destFile: $ex]")
