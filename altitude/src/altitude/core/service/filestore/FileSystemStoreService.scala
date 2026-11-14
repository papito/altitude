package altitude.core.service.filestore

import java.io._
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
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

  override def addEditRendition(assetId: String, rev: Int, kind: String, data: Array[Byte]): Unit =
    val destination = editRenditionPath(assetId, rev, kind)
    logger.trace(s"Storing the edited $kind of asset [$assetId] at revision [$rev] as $destination")
    try
      Files.createDirectories(destination.getParent)
      val temporary = Files.createTempFile(destination.getParent, s".$assetId-r$rev-", ".tmp")
      try
        Files.write(temporary, data)
        // A revision's bytes are never replaced: the writers of an asset's renditions take turns under its locks, so none stores
        // the file between this look and the move
        if Files.exists(destination) then throw FileAlreadyExistsException(destination.toString)
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
      finally Files.deleteIfExists(temporary): Unit
    catch
      case ex: IOException =>
        throw StorageException(s"Error storing the edited $kind of asset [$assetId] at revision [$rev]: $ex")

  override def highestEditRendition(assetId: String): Int = editRenditions(assetId).map(_._1).maxOption.getOrElse(0)

  override def getEditRendition(assetId: String, rev: Int, kind: String): MimedPreviewData =
    MimedPreviewData(assetId = assetId, data = getBinaryData(editRenditionPath(assetId, rev, kind).toFile).get)

  override def editRenditionPath(assetId: String, rev: Int, kind: String): Path =
    renditionDir(assetId, kind).resolve(s"$assetId-r$rev.${MimedPreviewData.FILE_EXTENSION}")

  override def purgeEditRenditions(assetId: String, below: Int): Unit =
    for (rev, file) <- editRenditions(assetId) if rev < below do deleteFile(file, assetId)

  /** The directory of the asset's edited renditions of `kind`: beside the Previews' or the Display images', by its ID's start */
  private def renditionDir(assetId: String, kind: String): Path =
    require(kind == C.DataStore.PREVIEW || kind == C.DataStore.DISPLAY, s"No edited rendition of kind [$kind]")
    Path.of(repositoryDataPath, kind, assetId.substring(0, 2))

  /** Every stored edited rendition of the asset, of both kinds, with its revision: the files named `<id>-r<rev>.webp` */
  private def editRenditions(assetId: String): List[(Int, Path)] =
    val name = s"${java.util.regex.Pattern.quote(assetId)}-r(\\d+)\\.${MimedPreviewData.FILE_EXTENSION}".r
    for
      kind <- List(C.DataStore.PREVIEW, C.DataStore.DISPLAY)
      dir = renditionDir(assetId, kind)
      if Files.isDirectory(dir)
      file <- Using.resource(Files.list(dir))(_.iterator.asScala.toList)
      rev <- file.getFileName.toString match
        case name(digits) => digits.toIntOption
        case _ => None
    yield (rev, file)

  /** The temporary files of the asset's rendition writes ([[addEditRendition]]) that a crash cut off before their move */
  private def renditionLeftovers(assetId: String): List[Path] =
    for
      kind <- List(C.DataStore.PREVIEW, C.DataStore.DISPLAY)
      dir = renditionDir(assetId, kind)
      if Files.isDirectory(dir)
      file <- Using.resource(Files.list(dir))(_.iterator.asScala.toList)
      name = file.getFileName.toString
      if name.startsWith(s".$assetId-r") && name.endsWith(".tmp")
    yield file

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

    // Its edited renditions, of every revision, and what a write of one cut off left
    editRenditions(id).foreach((_, file) => deleteFile(file, id))
    renditionLeftovers(id).foreach(deleteFile(_, id))

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
      // Deleted since it was looked for: a purge, or a rendition replaced by a later revision
      case _: NoSuchFileException | _: FileNotFoundException => throw NotFoundException(s"Cannot find file $srcFile")
      case ex: IOException =>
        throw StorageException(s"Error reading file [${srcFile.getPath}: $ex]")

  private def putBinaryData(destFile: File, data: Array[Byte]): Unit =
    try FileUtils.writeByteArrayToFile(destFile, data)
    catch
      case ex: IOException =>
        throw StorageException(s"Error writing $destFile: $ex]")
