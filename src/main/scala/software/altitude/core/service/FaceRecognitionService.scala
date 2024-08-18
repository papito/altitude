package software.altitude.core.service

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.face.LBPHFaceRecognizer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Const
import software.altitude.core.Environment
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.util.ImageUtil.matFromBytes

import java.io.File
import java.util

object FaceRecognitionService {
  // Number of labels reserved for special cases, and not used for actual people instances
  // Labels start at this number + 1 but Unknown people start at 1 (so reserved label count must be known)
  val RESERVED_LABEL_COUNT = 10

  /**
   * If there is no machine learning model verified hit, we cycle through all people in the database,
   * but only doing the matching on THIS many best face detections that we have (1 to X)
   *
   * Higher number means more matches will be found, at the cost of performance.
   *
   * Lower number means faster matching but the same person may be detected as new.
   * Technically, just 1 "top" face will work, and the accuracy benefits get diminished the higher we go
   */
  val MAX_COMPARISONS_PER_PERSON = 12

  /**
   * If the cosine distance between the facial features is below this threshold, we consider the face a match.
   */
  val PESSIMISTIC_COSINE_DISTANCE_THRESHOLD = .46
}

class FaceRecognitionService(val app: Altitude) {
  final val logger: Logger = LoggerFactory.getLogger(getClass)

  private val MODELS_PATH = FilenameUtils.concat(app.dataPath, Const.DataStore.MODELS)
  private val FACE_RECOGNITION_MODEL_PATH = FilenameUtils.concat(MODELS_PATH, "lbphf_face_rec_model.xml")

  private val modelFile = new File(FACE_RECOGNITION_MODEL_PATH)

  val recognizer: LBPHFaceRecognizer = LBPHFaceRecognizer.create()
  recognizer.setGridX(10)
  recognizer.setGridY(10)
  recognizer.setRadius(2)

  def initialize(): Unit = {
    if (modelFile.exists()) {
      logger.info("Found facial recognition model, loading...")
      recognizer.read(FACE_RECOGNITION_MODEL_PATH)
      return
    }

    logger.warn("No facial recognition model found, training with initial data...")

    /**
     * Initial data is two random images, as we need the minimum of two.
     * Can't just add the first user image without an error.
     */
    val initialLabels = new Mat(2, 1, CvType.CV_32SC1)
    val InitialImages = new java.util.ArrayList[Mat]()

    for (idx <- 0 to 1) {
      val bytes = getClass.getResourceAsStream(s"/train/${idx}.png").readAllBytes()
      val image: Mat = matFromBytes(bytes)
      initialLabels.put(idx, 0, idx)
      InitialImages.add(image)
    }

    recognizer.clear()
    recognizer.train(InitialImages, initialLabels)
    saveModel()
  }

  def saveModel(): Unit = {
    /**
     * Unorthodox, but this whole file-writing thing is messy and we can't really mock this method
     * as it can be invoked during deep app init, before we can use Mockito.
     *
     * In test, the model is ephemeral.
     */
    if (Environment.CURRENT == Environment.Name.TEST) {
      return
    }

    logger.info("--> Saving model")
    FileUtils.forceMkdirParent(modelFile)
    recognizer.save(FACE_RECOGNITION_MODEL_PATH)
  }

  def processAsset(dataAsset: AssetWithData): Unit = {
    val detectedFaces = app.service.faceDetection.extractFaces(dataAsset.data)
    logger.info(s"Detected ${detectedFaces.size} faces")

    detectedFaces.foreach(detectedFace => {
      val existingOrNewPerson = recognizeFace(detectedFace, dataAsset.asset)
      val persistedFace = app.service.person.addFace(detectedFace, dataAsset.asset, existingOrNewPerson)
      logger.info(s"Saving face ${persistedFace.persistedId} for person ${existingOrNewPerson.name.get}")
      indexFace(persistedFace, existingOrNewPerson)
    })
  }

  /**
   * Returns an existing OR a new person, already persisted in the database.
   *
   * The person/faces are also added to the cache for this repository, as we may need to
   * brute-force search for the person's face in the future.
   */
  def recognizeFace(detectedFace: Face, asset: Asset): Person = {
    require(detectedFace.id.isEmpty, "Face object must not be persisted yet")
    require(detectedFace.personId.isEmpty, "Face object must not be associated with a person yet")

    val predLabelArr = new Array[Int](1)
    val confidenceArr = new Array[Double](1)
    recognizer.predict(detectedFace.alignedImageGsMat, predLabelArr, confidenceArr)

    val predLabel = predLabelArr.head
    val confidence = confidenceArr.head

    logger.debug("Predicted label: " + predLabel + " with confidence: " + confidence)
    val personMlMatch: Option[Person] = app.service.faceCache.getPersonByLabel(predLabel)

    /**
     * If we have a match, we compare the match to
     * the person's "best" face - the faces are sorted by detection score.
     *
     * This is called a "verified" match.
     *
     * We do NOT trust the ML model confidence score, as its meaning is relative
     */
    if (personMlMatch.isDefined) {
      logger.debug(f"Comparing ML match ${personMlMatch.get.persistedId})")
      val simScore = app.service.faceDetection.getFeatureSimilarityScore(
        detectedFace.featuresMat, personMlMatch.get.getFaces.head.featuresMat)
      logger.debug("Similarity score: " + simScore)

      if (simScore >= FaceRecognitionService.PESSIMISTIC_COSINE_DISTANCE_THRESHOLD) {
        logger.debug("MATCHED. Persisting face")
        return personMlMatch.get
      }
    }

    // No verified match, try brute-force comparisons on cached faces
    val bestPersonFaceMatch: Option[Face] = matchFaceBruteForce(detectedFace)

    if (bestPersonFaceMatch.isDefined) {
      require(bestPersonFaceMatch.get.personLabel.isDefined, "Face must have a person label")

      val personBruteForceMatch = app.service.faceCache.getPersonByLabel(bestPersonFaceMatch.get.personLabel.get)
      personBruteForceMatch.get
    } else {
      logger.info("Mo match. Adding new person")
      val personModel = Person()
      val newPerson: Person = app.service.person.addPerson(personModel, Some(asset))
      newPerson
    }
  }

  private def matchFaceBruteForce(face: Face): Option[Face] = {
    logger.debug("Brute-force matching face")
    val bestFaceMatch: Option[Face] = getBestFaceMatch(face)
    logger.debug("Best match: " + bestFaceMatch)

    bestFaceMatch match {
      case None => None
      case Some(matchedFace) => Some(matchedFace)
    }
  }

  private def getBestFaceMatch(thisFace: Face): Option[Face] = {
    logger.debug(s"Comparing $thisFace: ")
    val faceSimilarityScores: List[(Double, Face)] = app.service.faceCache.getAll.flatMap { person =>
      logger.debug(s"Comparing faces for person ${person.name.get}")
      // these are already sorted by detection score, best first
      val bestFaces = person.getFaces.toList.take(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON)
      val faceScores: List[(Double, Face)] = bestFaces.map { personFace =>
        val similarityScore = app.service.faceDetection.getFeatureSimilarityScore(
          thisFace.featuresMat, personFace.featuresMat)
        logger.debug(s"Comparing with $personFace -> " + similarityScore)
        (similarityScore, personFace)
      }

      faceScores
    }

    // get the top one
    val sortedMatchingSimilarityWeights = faceSimilarityScores.sortBy(_._1)
    val highestSimilarityWeights = sortedMatchingSimilarityWeights.filter(_._1 >= FaceRecognitionService.PESSIMISTIC_COSINE_DISTANCE_THRESHOLD)

    highestSimilarityWeights.headOption match {
      case None => None
      case Some(res) => Some(res._2)
    }
  }

  private def indexFace(face: Face, person: Person): Unit = {
    logger.info(s"Updating model for person ${person.label}")
    val labels = new Mat(1, 1, CvType.CV_32SC1)
    val images = new util.ArrayList[Mat]()
    labels.put(0, 0, person.label)
    images.add(face.alignedImageGsMat)
    recognizer.update(images, labels)
  }

  def addFacesToPerson(faces: List[Face], person: Person): Unit = {
    faces.foreach(face => {
      person.addFace(face)
      indexFace(face, person)
    })
  }
}
