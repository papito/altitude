package altitude.core.models

import java.nio.file.Files
import java.nio.file.Path

/**
 * An asset and its file on disk, as it moves through the import pipeline: a staged file until the file store renames it into
 * place, the stored file after. A video never sits in the heap; `bytes` reads the whole file for the image-only consumers. Its
 * detected faces ride along from face detection through the image measurement to face recognition. `origin` is the Import file it
 * is a file of, which the pipeline settles with what becomes of it; a file imported outside an Import (an `ImportAsset`) has
 * none.
 */
case class AssetWithData(
    asset: Asset,
    path: Path,
    detectedFaces: Option[DetectedFaces] = None,
    origin: Option[ImportOrigin] = None):
  def bytes: Array[Byte] = Files.readAllBytes(path)

  override def toString: String =
    s"Asset with data: [${asset.id}] at [$path]"
