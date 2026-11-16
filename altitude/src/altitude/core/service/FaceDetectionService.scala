package altitude.core.service

import java.io.File
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn.blobFromImage
import org.opencv.dnn.Dnn.readNetFromONNX
import org.opencv.dnn.Net
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.Environment
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Settings
import altitude.core.util.ImageUtil.determineImageScale
import altitude.core.util.ImageUtil.makeImageThumbnail
import altitude.core.util.ImageUtil.matFromBytes
import altitude.core.util.ImportParallelism
import altitude.core.util.ModelFile
import altitude.core.util.NetPool
import altitude.core.util.Sha256

object FaceDetectionService:

  val faceDetectionBoxPx = 80

  /** Dimensionality of the ArcFace (w600k_r50) embedding vector. */
  val EMBEDDING_DIMENSIONS = 512

  /**
   * An ArcFace embedding: the L2-normalized features, compared by cosine distance, and the raw output's norm. Under the ArcFace
   * loss the norm tracks how recognizable the face was (AdaFace, Kim et al. 2022, uses it as a quality proxy), so it is the
   * Face's quality.
   */
  case class Embedding(features: Array[Float], norm: Float)

  /**
   * The ArcFace 112x112 reference landmarks, the destination of the 5-point alignment warp. The base coordinates assume a 96-wide
   * region; the +8 x-offset centers it in the 112 canvas, as w600k_r50 was trained.
   */
  val ARCFACE_REF_LANDMARKS_112: Array[Point] = Array(
    new Point(38.2946 + 8.0, 51.6963), // left eye (in image)
    new Point(73.5318 + 8.0, 51.5014), // right eye (in image)
    new Point(56.0252 + 8.0, 71.7366), // nose tip
    new Point(41.5493 + 8.0, 92.3655), // left mouth corner (in image)
    new Point(70.7299 + 8.0, 92.2041) // right mouth corner (in image)
  )

  /**
   * The 5 landmarks of a YuNet detection row (columns 0-3 bbox, 4-13 landmarks, 14 confidence), in the reference's order. YuNet
   * names them from the subject's side (right eye first), which is left to right in the image.
   */
  def extractLandmarksFromDetection(detection: Mat): Array[Point] =
    Array(
      new Point(detection.get(0, 4)(0), detection.get(0, 5)(0)), // left eye (in image)
      new Point(detection.get(0, 6)(0), detection.get(0, 7)(0)), // right eye (in image)
      new Point(detection.get(0, 8)(0), detection.get(0, 9)(0)), // nose tip
      new Point(detection.get(0, 10)(0), detection.get(0, 11)(0)), // left mouth corner (in image)
      new Point(detection.get(0, 12)(0), detection.get(0, 13)(0)) // right mouth corner (in image)
    )

  /** The 2x3 similarity transform (rotation, uniform scale, translation) from `src` to `dst`, for [[Imgproc.warpAffine]] */
  def estimateSimilarityTransform(src: Array[Point], dst: Array[Point]): Mat =
    require(src.length == dst.length && src.length >= 2, "Need at least 2 matching point pairs")

    val srcMat = new MatOfPoint2f(src*)
    val dstMat = new MatOfPoint2f(dst*)

    try {
      val M = Calib3d.estimateAffinePartial2D(srcMat, dstMat)

      require(!M.empty(), "Could not estimate similarity transform from the provided points")

      M
    } finally {
      srcMat.release()
      dstMat.release()
    }

  /** A YuNet detection row as a [[Rect]], clamped to the image, since a detected face can extend past its edge */
  def faceDetectToRect(detectedFace: Mat, imageWidth: Int, imageHeight: Int): Rect =
    val origX = detectedFace.get(0, 0)(0).toInt
    val origY = detectedFace.get(0, 1)(0).toInt
    val origW = detectedFace.get(0, 2)(0).toInt
    val origH = detectedFace.get(0, 3)(0).toInt

    val x = Math.max(0, origX)
    val y = Math.max(0, origY)
    val w = Math.min(origW, imageWidth - x)
    val h = Math.min(origH, imageHeight - y)

    new Rect(x, y, Math.max(0, w), Math.max(0, h))

  /** Clamps the origin at 0 only, with no image bounds */
  def faceDetectToRect(detectedFace: Mat): Rect =
    faceDetectToRect(detectedFace, Int.MaxValue, Int.MaxValue)

  def faceDetectToMat(image: Mat, detectedFace: Mat): Mat =
    val faceRect = faceDetectToRect(detectedFace, image.cols(), image.rows())
    image.submat(faceRect)

  def writeDebugOpenCvMat(mat: Mat, fileName: String): Unit =
    val outputDir = System.getenv().get("OUTPUT")

    if outputDir == null then
      println("OUTPUT environment variable not set for debug image writing")
      return

    val outputPath = FilenameUtils.concat(outputDir, fileName)
    println(String.format("Writing %s", outputPath))
    Imgcodecs.imwrite(outputPath, mat)

  /**
   * Writes face detection debug images into the existing `debugDir`: `{baseName}-annotated.jpg`, the image with every detection's
   * box, landmarks, quality and score, and per face `{baseName}-{N}-q{quality}-aligned.png`, the exact ArcFace input, so a
   * quality can be read against its crop when calibrating the quality thresholds. `faceData` holds (Face, detection row, raw
   * crop, aligned crop) per face.
   */
  def dumpDebugArtifacts(
      imageMat: Mat,
      faceData: List[(Face, Mat, Mat, Mat)],
      baseName: String,
      debugDir: String
  ): Unit =
    val green = new Scalar(0, 255, 0)

    val annotated = imageMat.clone()
    faceData.zipWithIndex.foreach {
      case ((face, detectionRow, _, alignedColor), idx) =>
        val rect = new Rect(face.x1, face.y1, face.width, face.height)
        Imgproc.rectangle(annotated, rect, green, 2)
        extractLandmarksFromDetection(detectionRow).foreach(pt => Imgproc.circle(annotated, pt, 3, green, -1))
        val label = f"q=${face.quality}%.1f d=${face.detectionScore}%.2f"
        Imgproc.putText(
          annotated,
          label,
          new Point(face.x1, Math.max(12, face.y1 - 4)),
          Imgproc.FONT_HERSHEY_SIMPLEX,
          0.5,
          green,
          1)
        Imgcodecs.imwrite(FilenameUtils.concat(debugDir, f"$baseName-${idx + 1}-q${face.quality}%.1f-aligned.png"), alignedColor)
    }
    Imgcodecs.imwrite(FilenameUtils.concat(debugDir, s"$baseName-annotated.jpg"), annotated)
    annotated.release()

class FaceDetectionService(app: Altitude):

  /**
   * The org.opencv API is used, but its native library is loaded through org.bytedeco: the usual
   * `System.loadLibrary(Core.NATIVE_LIBRARY_NAME)` does not work here. https://stackoverflow.com/a/58064096/53687
   */
  Loader.load(classOf[opencv_java])

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val faceDebugEnabled: Boolean = app.config.getBoolean(Const.Conf.FACE_DEBUG_ENABLED)

  private val debugDir: String = FilenameUtils.concat(Environment.ROOT_PATH, "debug")
  if faceDebugEnabled then
    FileUtils.forceMkdir(new File(debugDir))
    FileUtils.cleanDirectory(new File(debugDir))
    logger.debug(s"Cleared debug directory: $debugDir")

  // Model files, in place before any service is built (`Altitude.models`); OpenCV reads them by their path
  private val YUNET_MODEL_PATH = app.models.path(ModelFile.YuNet).toString
  private val ARCFACE_MODEL_PATH = app.models.path(ModelFile.ArcFace).toString

  /**
   * ArcFace (InsightFace w600k_r50) nets, `ImportParallelism.ARCFACE_NETS` of them, shared by every thread that detects faces. A
   * Net's forward-pass buffers are not thread-safe, so a borrower has it to itself, and one per thread is too costly: about 370
   * MB resident with Winograd off (a convolution algorithm, not a model change), about 780 MB with it on.
   *
   * Measured on 24-megapixel JPEGs, 12 threads on 24 cores: a forward pass takes 40 to 52 ms; one net tops out at about 24
   * embeddings a second, twelve at about 38. Each face borrows again, so on 20-face photos with every thread busy a borrow waits
   * about 400 ms and a photo holds its thread and decoded image for about 9 s. A second net cuts import wall time about 3% on
   * photos of three faces and 7% on twenty, for roughly 350 MB. A borrow never nests another, so it cannot deadlock.
   */
  private val arcFaceNets: NetPool[Net] = new NetPool[Net](
    ImportParallelism.ARCFACE_NETS,
    () => {
      logger.debug("Loading an ArcFace net")
      val net = readNetFromONNX(ARCFACE_MODEL_PATH)
      net.enableWinograd(false)
      net
    })

  /**
   * One YuNet detector per thread: its native object holds mutable state and must not be shared, and at about 70 MB it is cheap
   * to copy. The score and NMS thresholds are Settings, set before each detection, so a saved value applies to the next one.
   */
  private val yuNetLocal: ThreadLocal[FaceDetectorYN] =
    ThreadLocal.withInitial(() => FaceDetectorYN.create(YUNET_MODEL_PATH, "", new Size()))

  /** The YuNet detections in an image, one row each, with the detection Settings as they stand now */
  def detectFacesWithYunet(image: Mat): List[Mat] = detectFacesWithYunet(image, app.settings.current)

  private def detectFacesWithYunet(image: Mat, settings: Settings): List[Mat] =
    if image.empty then
      logger.warn("No data in image")
      return List()

    val minFaceSize = settings.faceDetectionMinFaceSize
    val boundingBoxSize = settings.faceDetectionBoundingBoxSize

    if Math.min(image.size().width, image.size().height).toInt < minFaceSize then
      logger.warn(s"Image dimensions too small to contain a detectable face (${image.width()}x${image.height()} px)")
      return List()

    val detectionResults = new Mat()

    val scaleFactor = determineImageScale(image.width(), image.height(), boundingBoxSize, boundingBoxSize) match {
      case scale if scale < 1.0 => scale
      case _ => 1.0
    }

    val srcMat: Mat =
      if scaleFactor < 1.0 then
        val resized = new Mat()
        Imgproc.resize(image, resized, new Size(), scaleFactor, scaleFactor, Imgproc.INTER_LINEAR)
        resized
      else image.clone()

    val detector = yuNetLocal.get()
    detector.setScoreThreshold(settings.faceYunetConfidenceThreshold.toFloat)
    detector.setNMSThreshold(settings.faceYunetNmsThreshold.toFloat)
    detector.setInputSize(srcMat.size())
    detector.detect(srcMat, detectionResults)
    srcMat.release()

    val numOfFaces = detectionResults.rows()

    val ret: List[Option[Mat]] = (for (idx <- 0 until numOfFaces) yield {
      val detection = detectionResults.row(idx)

      // Rescale the bbox and landmark columns (0-13) to the original image; column 14 is the confidence
      if scaleFactor < 1.0 then
        for (col <- 0 until (detection.cols() - 1)) {
          val originalValue = detection.get(0, col)(0)
          detection.put(0, col, originalValue / scaleFactor)
        }

      val detectionRect = FaceDetectionService.faceDetectToRect(detection, image.cols(), image.rows())

      if detectionRect.height < minFaceSize || detectionRect.width < minFaceSize then
        logger.warn(s"Face region too small (${detectionRect.width}x${detectionRect.height} px)")
        None
      else
        // Clone the row so detectionResults can be released independently
        Option(detection.clone())
    }).toList

    detectionResults.release()
    ret.flatten

  /** Extracts faces from encoded image bytes; with a `fileName` and face debug on, debug images go to `debug/` under the root */
  def extractFaces(data: Array[Byte], fileName: Option[String] = None): List[(Face, FaceImages)] =
    val imageMat: Mat = matFromBytes(data)
    try extractFaces(imageMat, fileName)
    finally imageMat.release()

  /** Extract faces from a decoded BGR image, such as a Sampled frame of a Video. The caller keeps ownership of the image. */
  def extractFaces(imageMat: Mat, fileName: Option[String]): List[(Face, FaceImages)] =
    // One snapshot per extraction: the detection and the two quality tiers (on the embedding norm: at least enroll to start a
    // Person, at least keep to be stored at all) read the same Settings
    val settings = app.settings.current
    val results: List[Mat] = detectFacesWithYunet(imageMat, settings)

    val enrollThreshold = settings.faceQualityEnrollThreshold
    val keepThreshold = settings.faceQualityKeepThreshold

    // Accumulate both the public result and the intermediate Mats needed for debug output in one pass
    case class FaceEntry(face: Face, faceImages: FaceImages, detectionRow: Mat, rawCrop: Mat, alignedColor: Mat)

    val entries: List[FaceEntry] = results.map {
      res =>
        val alignedFaceImage = alignCropFaceFromDetection(imageMat, res)
        val alignedFaceImageGs = getHistEqualizedGrayScImage(alignedFaceImage)
        val embedding = getArcFaceEmbedding(alignedFaceImage)

        val rect = FaceDetectionService.faceDetectToRect(res, imageMat.cols(), imageMat.rows())
        val faceImage: Mat = imageMat.submat(rect)

        val imageBytes = new MatOfByte
        Imgcodecs.imencode(".png", faceImage, imageBytes)

        val alignedImageBytes = new MatOfByte
        Imgcodecs.imencode(".png", alignedFaceImage, alignedImageBytes)

        val alignedFaceImageGsBytes = new MatOfByte
        Imgcodecs.imencode(".png", alignedFaceImageGs, alignedFaceImageGsBytes)

        // The thumbnail, the checksum and the stored file share one copy, so the checksum is over the exact bytes stored
        val crop = imageBytes.toArray
        val displayImage = makeImageThumbnail(crop, FaceDetectionService.faceDetectionBoxPx)

        val face = Face(
          x1 = rect.x,
          y1 = rect.y,
          width = rect.width,
          height = rect.height,
          detectionScore = res.get(0, 14)(0).asInstanceOf[Float],
          features = embedding.features,
          quality = embedding.norm,
          isEnrolled = embedding.norm >= enrollThreshold,
          checksum = Sha256.hex(crop)
        )
        logger.trace(s"$face in ${fileName.getOrElse("image")}")

        val faceImages = FaceImages(
          image = crop,
          displayImage = displayImage,
          alignedImage = alignedImageBytes.toArray,
          alignedImageGs = alignedFaceImageGsBytes.toArray
        )

        imageBytes.release()
        alignedImageBytes.release()
        alignedFaceImageGsBytes.release()
        alignedFaceImageGs.release()

        FaceEntry(face, faceImages, res, faceImage, alignedFaceImage)
    }

    if faceDebugEnabled && fileName.isDefined then
      val baseName = FilenameUtils.getBaseName(fileName.get)
      val debugData = entries.map(e => (e.face, e.detectionRow, e.rawCrop, e.alignedColor))
      FaceDetectionService.dumpDebugArtifacts(imageMat, debugData, baseName, debugDir)

    entries.foreach {
      e =>
        e.alignedColor.release()
        e.detectionRow.release()
      // e.rawCrop is a submat view of imageMat, whose data is the caller's to free
    }

    // Dropped after the debug dump, so a face below the floor can still be looked at when calibrating the thresholds
    val (kept, dropped) = entries.partition(_.face.quality >= keepThreshold)
    dropped.foreach(e => logger.trace(s"Dropped below the quality floor $keepThreshold: ${e.face}"))
    kept.map(e => (e.face, e.faceImages))

  /** The face warped by its 5 landmarks onto the ArcFace 112x112 reference template, the crop the embedding model expects */
  def alignCropFaceFromDetection(image: Mat, detection: Mat): Mat =
    val srcLandmarks = FaceDetectionService.extractLandmarksFromDetection(detection)
    val dstLandmarks = FaceDetectionService.ARCFACE_REF_LANDMARKS_112

    val transformMatrix = FaceDetectionService.estimateSimilarityTransform(srcLandmarks, dstLandmarks)

    val aligned = new Mat
    Imgproc.warpAffine(
      image,
      aligned,
      transformMatrix,
      new Size(112, 112),
      Imgproc.INTER_LINEAR,
      org.opencv.core.Core.BORDER_REPLICATE,
      new Scalar(0, 0, 0)
    )
    transformMatrix.release()
    aligned

  /** The [[FaceDetectionService.Embedding]] of a 112x112 BGR aligned face ([[alignCropFaceFromDetection]]); no resize is done */
  def getArcFaceEmbedding(alignedFaceImage: Mat): FaceDetectionService.Embedding =
    val blob = blobFromImage(
      alignedFaceImage,
      1.0 / 255.0,
      new Size(112, 112),
      new Scalar(0, 0, 0),
      true, // swapRB: BGR -> RGB
      false
    )

    val embedding = new Array[Float](FaceDetectionService.EMBEDDING_DIMENSIONS)
    try
      arcFaceNets.borrow {
        net =>
          net.setInput(blob)
          val output = net.forward()
          // Copied in the borrow: the output shares its data with the net, and the next borrower's forward pass overwrites it
          output.get(0, 0, embedding)
          output.release()
      }
    finally blob.release()

    val norm = Math.sqrt(embedding.map(x => x.toDouble * x.toDouble).sum).toFloat
    if norm > 0 then
      for (i <- embedding.indices) {
        embedding(i) = embedding(i) / norm
      }

    FaceDetectionService.Embedding(embedding, norm)

  def getHistEqualizedGrayScImage(cropAlignedFace: Mat): Mat =
    val grayAlignedImage = new Mat()
    Imgproc.cvtColor(cropAlignedFace, grayAlignedImage, Imgproc.COLOR_BGR2GRAY)
    Imgproc.equalizeHist(grayAlignedImage, grayAlignedImage)
    grayAlignedImage
