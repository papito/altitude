package software.altitude.core.service

import java.io.File
import java.nio.file.Paths
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
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
import org.opencv.dnn.Dnn.readNetFromCaffe
import org.opencv.dnn.Dnn.readNetFromTorch
import org.opencv.dnn.Net
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import software.altitude.core.Environment
import software.altitude.core.models.Face
import software.altitude.core.models.FaceImages
import software.altitude.core.util.ImageUtil.determineImageScale
import software.altitude.core.util.ImageUtil.makeImageThumbnail
import software.altitude.core.util.ImageUtil.matFromBytes
import software.altitude.core.util.MurmurHash

object FaceDetectionService {
  private val dnnInWidth = 300
  private val dnnInHeight = 300

  val faceDetectionBoxPx = 80

  private val dnnConfidenceThreshold = 0.37
  private val minFaceSize = 50 // minimum acceptable size of face region in pixels
  private val dnnInScaleFactor = 1.0
  private val dnnMeanVal = new Scalar(104.0, 177.0, 123.0, 128)
  private val yunetConfidenceThreshold = 0.855f

  private val cosineSimilarityThreshold = 0.363

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

  private val RESOURCE_FILE_NAMES: Map[String, String] = {
    Map(
      "SF_ONNX_MODEL" -> "face_recognition_sface_2021dec.onnx",
      "DNN_NET_PROTO_CONF" -> "deploy.prototxt",
      "DNN_NET_MODEL" -> "res10_300x300_ssd_iter_140000.caffemodel",
      "EMBEDDING_NET_MODEL" -> "openface_nn4.small2.v1.t7",
      "YUNET_MODEL" -> "face_detection_yunet_2022mar.onnx"
    )
  }

  checkAndCreateResourceFiles()

  private val SF_ONNX_MODEL_PATH =
    new File(Environment.OPENCV_RESOURCE_PATH, RESOURCE_FILE_NAMES("SF_ONNX_MODEL")).getAbsolutePath
  private val DNN_NET_PROTO_CONF_PATH =
    new File(Environment.OPENCV_RESOURCE_PATH, RESOURCE_FILE_NAMES("DNN_NET_PROTO_CONF")).getAbsolutePath
  private val DNN_NET_MODEL_PATH =
    new File(Environment.OPENCV_RESOURCE_PATH, RESOURCE_FILE_NAMES("DNN_NET_MODEL")).getAbsolutePath
  private val YUNET_MODEL_PATH = new File(Environment.OPENCV_RESOURCE_PATH, RESOURCE_FILE_NAMES("YUNET_MODEL")).getAbsolutePath
  private val EMBEDDING_NET_PATH =
    new File(Environment.OPENCV_RESOURCE_PATH, RESOURCE_FILE_NAMES("EMBEDDING_NET_MODEL")).getAbsolutePath

  private val sfaceRecognizer = FaceRecognizerSF.create(SF_ONNX_MODEL_PATH, "")

  private val dnnNet: Net = readNetFromCaffe(DNN_NET_PROTO_CONF_PATH, DNN_NET_MODEL_PATH)

  private val embedder = readNetFromTorch(EMBEDDING_NET_PATH, true)

  private val yuNet = FaceDetectorYN.create(YUNET_MODEL_PATH, "", new Size())
  yuNet.setScoreThreshold(FaceDetectionService.yunetConfidenceThreshold)
  yuNet.setNMSThreshold(0.2f)

  /** Create the required OPENCV resource files if they do not exist outside of the JAR itself. */
  private def checkAndCreateResourceFiles(): Unit = {
    val openCvResourcesDir = new File(Environment.OPENCV_RESOURCE_PATH)

    if (!openCvResourcesDir.exists()) {
      logger.info(s"Resources directory $openCvResourcesDir not found, creating it")
      FileUtils.forceMkdir(openCvResourcesDir)
    }

    RESOURCE_FILE_NAMES.values.foreach {
      fileName =>
        val filePath = Paths.get(openCvResourcesDir.getAbsolutePath, fileName).toFile

        if (!filePath.exists() || !filePath.isFile) {
          logger.info(s"Resource file $fileName not found, creating it from source")

          val resourceFilePath = s"/opencv/$fileName"

          logger.info("Resource path: " + resourceFilePath)
          val resourceUrl = getClass.getResource(resourceFilePath)
          logger.info("Copying resource file from " + resourceUrl + " to " + filePath)
          FileUtils.copyURLToFile(resourceUrl, filePath)
        }
    }
  }

  def detectFacesWithDnnNet(image: Mat): List[Rect] = {
    val inputBlob = blobFromImage(
      image,
      FaceDetectionService.dnnInScaleFactor,
      new Size(FaceDetectionService.dnnInWidth, FaceDetectionService.dnnInHeight),
      FaceDetectionService.dnnMeanVal,
      false,
      false,
      CvType.CV_32F
    )

    dnnNet.setInput(inputBlob)

    val detections = dnnNet.forward()

    // Decode detected face locations
    val di = detections.reshape(1, detections.total().asInstanceOf[Int] / 7)

    val faceRegions = {
      for (idx <- 0 until di.rows()) yield {
        val confidence = di.get(idx, 2)(0)

        if (confidence > FaceDetectionService.dnnConfidenceThreshold) {
          // logger.info("Found a face with confidence value of " + confidence)
          val x1 = (di.get(idx, 3)(0) * image.size().width).toInt
          val y1 = (di.get(idx, 4)(0) * image.size().height).toInt
          val x2 = (di.get(idx, 5)(0) * image.size().width).toInt
          val y2 = (di.get(idx, 6)(0) * image.size().height).toInt

          val rect = new Rect(new Point(x1, y1), new Point(x2, y2))

          if (rect.width < FaceDetectionService.minFaceSize || rect.height < FaceDetectionService.minFaceSize) {
            logger.warn("Face region too small")
            None
          } else {
            Option(rect)
          }
        } else {
          None
        }
      }
    }.flatten.toList

    logger.info(s"Number of face regions found: ${faceRegions.size}")

    faceRegions
  }

  def detectFacesWithYunet(image: Mat): List[Mat] = {
    if (image.empty) {
      logger.warn("No data in image")
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
        // LBPHFaceRecognizer requires grayscale images
        val alignedFaceImageGs = getHistEqualizedGrayScImage(alignedFaceImage)
        val features = getFacialFeatures(alignedFaceImage)

        val featuresArray = (0 to 127).map(col => features.get(0, col)(0).asInstanceOf[Float]).toArray
        val embedding = getEmbeddings(alignedFaceImage)

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
          embeddings = embedding,
          features = featuresArray,
          alignedImageGs = alignedFaceImageGsBytes.toArray,
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

  def getFacialFeatures(image: Mat): Mat = {
    val feature = new Mat
    sfaceRecognizer.feature(image, feature)
    feature
  }

  def getEmbeddings(trainingImage: Mat): Array[Float] = {
    val alignedFaceBlob = getAlignedFaceBlob(trainingImage)
    embedder.setInput(alignedFaceBlob)
    val embeddingsMat = embedder.forward
    val embeddings = new Array[Float](128)
    embeddingsMat.get(0, 0, embeddings)
    embeddings
  }

  def getHistEqualizedGrayScImage(cropAlignedFace: Mat): Mat = {
    val grayAlignedImage = new Mat()
    Imgproc.cvtColor(cropAlignedFace, grayAlignedImage, Imgproc.COLOR_BGR2GRAY)
    Imgproc.equalizeHist(grayAlignedImage, grayAlignedImage)
    grayAlignedImage
  }

  private def getAlignedFaceBlob(image: Mat): Mat = {
    val resized = new Mat
    Imgproc.resize(image, resized, new Size(96, 96))
    Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB)
    blobFromImage(resized, 1.0 / 255, new Size(96, 96), new Scalar(0, 0, 0), true, false)
  }

  def isFaceSimilar(image1: Mat, image2: Mat, detectMat1: Mat, detectMat2: Mat): Boolean = {
    // DEBUGGING:
    // val face1Rect = faceDetectToRect(detectMat1)
    // val face2Rect = faceDetectToRect(detectMat2)
    // writeDebugOpenCvMat(image1.submat(face1Rect), "face1-1.jpg")
    // writeDebugOpenCvMat(image2.submat(face2Rect), "face2-1.jpg")

    val alignedFace1 = alignCropFaceFromDetection(image1, detectMat1)
    val alignedFace2 = alignCropFaceFromDetection(image2, detectMat2)

    // DEBUGGING:
    // writeDebugOpenCvMat(alignedFace1, "face1-2.jpg")
    // writeDebugOpenCvMat(alignedFace2, "face2-2.jpg")

    val feature1 = getFacialFeatures(alignedFace1).clone()
    val feature2 = getFacialFeatures(alignedFace2).clone()

    getFeatureSimilarityScore(feature1, feature2) >= FaceDetectionService.cosineSimilarityThreshold
  }

  def getFeatureSimilarityScore(feature1: Mat, feature2: Mat): Double = {
    sfaceRecognizer.`match`(feature1, feature2, FaceRecognizerSF.FR_COSINE)
  }
}
