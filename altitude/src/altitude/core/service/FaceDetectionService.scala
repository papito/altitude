package altitude.core.service

import altitude.core.{Altitude, Const, Environment}
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.util.ImageUtil.determineImageScale
import altitude.core.util.ImageUtil.makeImageThumbnail
import altitude.core.util.ImageUtil.matFromBytes
import altitude.core.util.MurmurHash
import com.typesafe.config.Config
import org.apache.commons.io.FilenameUtils
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
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

object FaceDetectionService {

  val faceDetectionBoxPx = 80

  /** Dimensionality of the ArcFace (w600k_r50) embedding vector. */
  val EMBEDDING_DIMENSIONS = 512

  /**
   * Standard ArcFace / InsightFace 112x112 reference landmark coordinates.
   *
   * These are the canonical destination positions for a 5-point alignment warp. The base coordinates assume a 96-wide active
   * region; the +8 x-offset centers the face in the full 112x112 canvas. The w600k_r50 model was trained with this offset.
   *
   * Order: left-eye-in-image, right-eye-in-image, nose, mouth-left-in-image, mouth-right-in-image.
   */
  val ARCFACE_REF_LANDMARKS_112: Array[Point] = Array(
    new Point(38.2946 + 8.0, 51.6963), // left eye (in image)
    new Point(73.5318 + 8.0, 51.5014), // right eye (in image)
    new Point(56.0252 + 8.0, 71.7366), // nose tip
    new Point(41.5493 + 8.0, 92.3655), // left mouth corner (in image)
    new Point(70.7299 + 8.0, 92.2041) // right mouth corner (in image)
  )

  /**
   * Extract the 5 facial landmarks from a YuNet detection row.
   *
   * YuNet detection Mat columns: 0-3 = bbox (x,y,w,h), 4-13 = landmarks, 14 = confidence. Landmark column order (subject
   * perspective): right_eye, left_eye, nose, mouth_right, mouth_left. In image coordinates this maps to: left-eye-in-image (4,5),
   * right-eye-in-image (6,7), nose (8,9), mouth-left-in-image (10,11), mouth-right-in-image (12,13).
   */
  def extractLandmarksFromDetection(detection: Mat): Array[Point] = {
    Array(
      new Point(detection.get(0, 4)(0), detection.get(0, 5)(0)), // left eye (in image)
      new Point(detection.get(0, 6)(0), detection.get(0, 7)(0)), // right eye (in image)
      new Point(detection.get(0, 8)(0), detection.get(0, 9)(0)), // nose tip
      new Point(detection.get(0, 10)(0), detection.get(0, 11)(0)), // left mouth corner (in image)
      new Point(detection.get(0, 12)(0), detection.get(0, 13)(0)) // right mouth corner (in image)
    )
  }

  /**
   * Estimate a 2D similarity transform (rotation + uniform scale + translation) from source to destination point sets using a
   * least-squares fit over all provided point pairs.
   *
   * Returns a 2x3 affine matrix suitable for [[Imgproc.warpAffine]].
   */
  def estimateSimilarityTransform(src: Array[Point], dst: Array[Point]): Mat = {
    require(src.length == dst.length && src.length >= 2, "Need at least 2 matching point pairs")
    val n = src.length

    // Build the linear system  A * [a, b, tx, ty]^T = B
    // where the similarity transform is:  x' = a*x - b*y + tx,  y' = b*x + a*y + ty
    val A = Mat.zeros(2 * n, 4, CvType.CV_64F)
    val B = Mat.zeros(2 * n, 1, CvType.CV_64F)

    for (i <- 0 until n) {
      val sx = src(i).x
      val sy = src(i).y
      // row for x'
      A.put(2 * i, 0, sx)
      A.put(2 * i, 1, -sy)
      A.put(2 * i, 2, 1.0)
      A.put(2 * i, 3, 0.0)
      B.put(2 * i, 0, dst(i).x)
      // row for y'
      A.put(2 * i + 1, 0, sy)
      A.put(2 * i + 1, 1, sx)
      A.put(2 * i + 1, 2, 0.0)
      A.put(2 * i + 1, 3, 1.0)
      B.put(2 * i + 1, 0, dst(i).y)
    }

    // Solve via least squares: params = (A^T A)^-1 A^T B
    val At = new Mat
    org.opencv.core.Core.transpose(A, At)
    val AtA = new Mat
    org.opencv.core.Core.gemm(At, A, 1.0, new Mat(), 0.0, AtA)
    val AtB = new Mat
    org.opencv.core.Core.gemm(At, B, 1.0, new Mat(), 0.0, AtB)
    val params = new Mat
    org.opencv.core.Core.solve(AtA, AtB, params)

    val a = params.get(0, 0)(0)
    val b = params.get(1, 0)(0)
    val tx = params.get(2, 0)(0)
    val ty = params.get(3, 0)(0)

    // Build 2x3 affine matrix
    val M = Mat.zeros(2, 3, CvType.CV_64F)
    M.put(0, 0, a)
    M.put(0, 1, -b)
    M.put(0, 2, tx)
    M.put(1, 0, b)
    M.put(1, 1, a)
    M.put(1, 2, ty)
    M
  }

  /**
   * Convert a YuNet detection row to a [[Rect]], clamping coordinates to the given image dimensions to prevent out-of-bounds
   * errors when the detected face region extends past the image edge.
   */
  def faceDetectToRect(detectedFace: Mat, imageWidth: Int, imageHeight: Int): Rect = {
    val origX = detectedFace.get(0, 0)(0).toInt
    val origY = detectedFace.get(0, 1)(0).toInt
    val origW = detectedFace.get(0, 2)(0).toInt
    val origH = detectedFace.get(0, 3)(0).toInt

    val x = Math.max(0, origX)
    val y = Math.max(0, origY)
    val w = Math.min(origW, imageWidth - x)
    val h = Math.min(origH, imageHeight - y)

    new Rect(x, y, Math.max(0, w), Math.max(0, h))
  }

  /** Overload without clamping — delegates with Int.MaxValue bounds (legacy-safe). */
  def faceDetectToRect(detectedFace: Mat): Rect =
    faceDetectToRect(detectedFace, Int.MaxValue, Int.MaxValue)

  def faceDetectToMat(image: Mat, detectedFace: Mat): Mat = {
    val faceRect = faceDetectToRect(detectedFace, image.cols(), image.rows())
    image.submat(faceRect)
  }

  def writeDebugOpenCvMat(mat: Mat, fileName: String): Unit = {
    val outputDir = System.getenv().get("OUTPUT")

    if (outputDir == null) {
      println("OUTPUT environment variable not set for debug image writing")
      return
    }

    val outputPath = FilenameUtils.concat(outputDir, fileName)
    println(String.format("Writing %s", outputPath))
    Imgcodecs.imwrite(outputPath, mat)
  }
}

class FaceDetectionService(app: Altitude) {

  /**
   * OpenCV for Java has two competing APIs, which is confusing enough (org.opencv, org.bytedeco), every example under the sun
   * directs to do this in order to have native lib linking errors go away: System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
   *
   * But it doesn't work here. While we are using the org.opencv API, the native lib is loaded by the org.bytedeco API.
   *
   * https://stackoverflow.com/a/58064096/53687
   */
  Loader.load(classOf[opencv_java])

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  // Read configurable thresholds from config, falling back to sensible defaults
  private val yunetConfidenceThreshold: Float = app.config.getDouble(Const.Conf.FACE_YUNET_CONFIDENCE_THRESHOLD).toFloat
  private val yunetNmsThreshold: Float = app.config.getDouble(Const.Conf.FACE_YUNET_NMS_THRESHOLD).toFloat
  private val boundingBoxSize: Int = app.config.getInt(Const.Conf.FACE_DETECTION_BOUNDING_BOX_SIZE)
  private val minFaceSize: Int = app.config.getInt(Const.Conf.FACE_DETECTION_MIN_FACE_SIZE)

  private val YUNET_MODEL_PATH = Environment.resolveResourcePath("/opencv/face_detection_yunet_2022mar.onnx")
  private val ARCFACE_MODEL_PATH = Environment.resolveResourcePath("/opencv/w600k_r50.onnx")

  /** ArcFace (InsightFace w600k_r50) – produces 512-d L2-normalized embeddings. */
  private val arcFaceNet: Net = readNetFromONNX(ARCFACE_MODEL_PATH)

  private val yuNet = FaceDetectorYN.create(YUNET_MODEL_PATH, "", new Size())
  yuNet.setScoreThreshold(yunetConfidenceThreshold)
  yuNet.setNMSThreshold(yunetNmsThreshold)

  def detectFacesWithYunet(image: Mat): List[Mat] = {
    if (image.empty) {
      logger.warn("No data in image")
      return List()
    }

    if (Math.min(image.size().width, image.size().height).toInt < minFaceSize) {
      logger.warn("Image too small")
      return List()
    }

    val detectionResults = new Mat()

    val scaleFactor = determineImageScale(image.width(), image.height(), boundingBoxSize, boundingBoxSize) match {
      case scale if scale < 1.0 => scale
      case _ => 1.0
    }

    val srcMat: Mat = if (scaleFactor < 1.0) {
      val resized = new Mat()
      Imgproc.resize(image, resized, new Size(), scaleFactor, scaleFactor, Imgproc.INTER_LINEAR)
      resized
    } else {
      image.clone()
    }

    yuNet.setInputSize(srcMat.size())
    yuNet.detect(srcMat, detectionResults)
    val numOfFaces = detectionResults.rows()

    val ret: List[Option[Mat]] = (for (idx <- 0 until numOfFaces) yield {
      val detection = detectionResults.row(idx)

      // update the original detection matrix to account for the scaling factor
      if (scaleFactor < 1.0) {
        for (col <- 0 until detection.cols()) {
          val originalValue = detection.get(0, col)(0)
          detection.put(0, col, originalValue / scaleFactor)
        }
      }

      val detectionRect = FaceDetectionService.faceDetectToRect(detection, image.cols(), image.rows())

      if (detectionRect.height < minFaceSize || detectionRect.width < minFaceSize) {
        logger.warn("Face region too small")
        None
      } else {
        Option(detection)
      }
    }).toList

    ret.flatten
  }

  /**
   * Extract faces from raw image bytes.
   */
  def extractFaces(data: Array[Byte]): List[(Face, FaceImages)] = {
    val imageMat: Mat = matFromBytes(data)

    val results: List[Mat] = detectFacesWithYunet(imageMat)

    val facesAndImages: List[(Face, FaceImages)] = results.map {
      res =>
        val alignedFaceImage = alignCropFaceFromDetection(imageMat, res)
        val alignedFaceImageGs = getHistEqualizedGrayScImage(alignedFaceImage)
        val features = getArcFaceEmbedding(alignedFaceImage)

        val rect = FaceDetectionService.faceDetectToRect(res, imageMat.cols(), imageMat.rows())
        val faceImage: Mat = imageMat.submat(rect)

        val imageBytes = new MatOfByte
        Imgcodecs.imencode(".png", faceImage, imageBytes)

        val alignedImageBytes = new MatOfByte
        Imgcodecs.imencode(".png", alignedFaceImage, alignedImageBytes)

        val alignedFaceImageGsBytes = new MatOfByte
        Imgcodecs.imencode(".png", alignedFaceImageGs, alignedFaceImageGsBytes)

        val displayImage = makeImageThumbnail(imageBytes.toArray, FaceDetectionService.faceDetectionBoxPx)

        val face = Face(
          x1 = rect.x,
          y1 = rect.y,
          width = rect.width,
          height = rect.height,
          detectionScore = res.get(0, 14)(0).asInstanceOf[Float],
          features = features,
          checksum = MurmurHash.hash32(imageBytes.toArray)
        )

        val faceImages = FaceImages(
          image = imageBytes.toArray,
          displayImage = displayImage,
          alignedImage = alignedFaceImageGsBytes.toArray,
          alignedImageGs = alignedFaceImageGsBytes.toArray
        )

        (face, faceImages)
    }

    facesAndImages
  }

  /**
   * Align and crop a face from the source image using a 5-point similarity transform.
   *
   * Uses the 5 facial landmarks from the YuNet detection to warp the source image so the face is aligned to the standard ArcFace
   * 112x112 reference template. This produces a correctly normalized crop for the w600k_r50 embedding model.
   */
  def alignCropFaceFromDetection(image: Mat, detection: Mat): Mat = {
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
    aligned
  }

  /**
   * Compute a 512-d ArcFace (InsightFace w600k_r50) embedding for an aligned face image.
   *
   * The model expects a 112x112 BGR image normalized to [0,1]. The output is L2-normalized so that cosine distance can be used
   * directly for comparison.
   */
  def getArcFaceEmbedding(alignedFaceImage: Mat): Array[Float] = {
    val resized = new Mat
    Imgproc.resize(alignedFaceImage, resized, new Size(112, 112))

    val blob = blobFromImage(
      resized,
      1.0 / 255.0,
      new Size(112, 112),
      new Scalar(0, 0, 0),
      true, // swapRB: BGR -> RGB
      false
    )

    arcFaceNet.setInput(blob)
    val output = arcFaceNet.forward()

    val embedding = new Array[Float](FaceDetectionService.EMBEDDING_DIMENSIONS)
    output.get(0, 0, embedding)

    // L2-normalize the embedding
    val norm = Math.sqrt(embedding.map(x => x.toDouble * x.toDouble).sum).toFloat
    if (norm > 0) {
      for (i <- embedding.indices) {
        embedding(i) = embedding(i) / norm
      }
    }

    embedding
  }

  def getHistEqualizedGrayScImage(cropAlignedFace: Mat): Mat = {
    val grayAlignedImage = new Mat()
    Imgproc.cvtColor(cropAlignedFace, grayAlignedImage, Imgproc.COLOR_BGR2GRAY)
    Imgproc.equalizeHist(grayAlignedImage, grayAlignedImage)
    grayAlignedImage
  }
}
