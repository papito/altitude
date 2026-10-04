package altitude.core.models

import java.nio.file.Files
import java.nio.file.Path

/**
 * An asset and its file on disk, as it moves through the import pipeline: a staged file until the file store renames it into
 * place, the stored file after. A video never sits in the heap; `bytes` reads the whole file for the image-only consumers. Its
 * detected faces ride along from face detection to face recognition.
 */
case class AssetWithData(asset: Asset, path: Path, detectedFaces: Option[DetectedFaces] = None):
  def bytes: Array[Byte] = Files.readAllBytes(path)

  override def toString: String =
    s"Asset with data: [${asset.id}] at [$path]"
