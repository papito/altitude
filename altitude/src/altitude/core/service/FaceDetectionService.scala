package altitude.core.service

import altitude.core.Environment
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.service.FaceDetectionService.minFaceSize
import altitude.core.util.ImageUtil.determineImageScale
import altitude.core.util.ImageUtil.makeImageThumbnail
import altitude.core.util.ImageUtil.matFromBytes
import altitude.core.util.MurmurHash
import org.apache.commons.io.FilenameUtils
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn.blobFromImage
import org.opencv.dnn.Dnn.readNetFromONNX
import org.opencv.dnn.Net
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object FaceDetectionService {

  val faceDetectionBoxPx = 80

  private val minFaceSize = 50 // minimum acceptable size of face region in pixels
  private val yunetConfidenceThreshold = 0.855f

  /** Dimensionality of the ArcFace (w600k_r50) embedding vector. */
  val EMBEDDING_DIMENSIONS = 512

  def faceDetectToRect(detectedFace: Mat): Rect = {
    val origX = detectedFace.get(0, 0)(0).asInstanceOf[Int]
    val origY = detectedFace.get(0, 1)(0).asInstanceOf[Int]

    val x = Math.max(0, origX)
    val y = Math.max(0, origY)

    val w = detectedFace.get(0, 2)(0).asInstanceOf[Int]
    val h = detectedFace.get(0, 3)(0).asInstanceOf[Int]
    new Rect(x, y, w, h)
  }

  def faceDetectToMat(image: Mat, detectedFace: Mat): Mat = {
    val faceRect = faceDetectToRect(detectedFace)
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

class FaceDetectionService() {

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

  private val SF_ONNX_MODEL_PATH = Environment.resolveResourcePath("/opencv/face_recognition_sface_2021dec.onnx")
  private val YUNET_MODEL_PATH = Environment.resolveResourcePath("/opencv/face_detection_yunet_2022mar.onnx")
  private val ARCFACE_MODEL_PATH = Environment.resolveResourcePath("/opencv/w600k_r50.onnx")

  /** SFace recognizer – used only for face alignment (alignCrop). */
  private val sfaceRecognizer = FaceRecognizerSF.create(SF_ONNX_MODEL_PATH, "")

  /** ArcFace (InsightFace w600k_r50) – produces 512-d L2-normalized embeddings. */
  private val arcFaceNet: Net = readNetFromONNX(ARCFACE_MODEL_PATH)

  private val yuNet = FaceDetectorYN.create(YUNET_MODEL_PATH, "", new Size())
  yuNet.setScoreThreshold(FaceDetectionService.yunetConfidenceThreshold)
  yuNet.setNMSThreshold(0.2f)



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

    val boundingBoxSize = 600

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

      val detectionRect = FaceDetectionService.faceDetectToRect(detection)

      if (detectionRect.height < FaceDetectionService.minFaceSize || detectionRect.width < FaceDetectionService.minFaceSize) {
        logger.warn("Face region too small")
        None
      } else {
        Option(detection)
      }
    }).toList

    ret.flatten
  }

  def extractFaces(data: Array[Byte]): List[(Face, FaceImages)] = {
    val imageMat: Mat = matFromBytes(data)
    val results: List[Mat] = detectFacesWithYunet(imageMat)

    val facesAndImages: List[(Face, FaceImages)] = results.map {
      res =>
        val alignedFaceImage = alignCropFaceFromDetection(imageMat, res)
        val alignedFaceImageGs = getHistEqualizedGrayScImage(alignedFaceImage)
        val features = getArcFaceEmbedding(alignedFaceImage)

        val rect = FaceDetectionService.faceDetectToRect(res)
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

  def alignCropFaceFromDetection(image: Mat, detection: Mat): Mat = {
    val alignedFace = new Mat
    sfaceRecognizer.alignCrop(image, detection, alignedFace)
    alignedFace
  }

  /**
   * Compute a 512-d ArcFace (InsightFace w600k_r50) embedding for an aligned face image.
   *
   * The model expects a 112x112 BGR image normalized to [0,1]. The output is L2-normalized so that
   * cosine distance can be used directly for comparison.
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
