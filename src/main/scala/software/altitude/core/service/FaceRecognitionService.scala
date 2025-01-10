package software.altitude.core.service
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import software.altitude.core.Altitude
import software.altitude.core.AltitudeActorSystem
import software.altitude.core.RequestContext
import software.altitude.core.actors.FaceRecManagerActor
import software.altitude.core.actors.FaceRecModelActor.FacePrediction
import software.altitude.core.actors.FaceRecModelActor.ModelSize
import software.altitude.core.dao.FaceDao
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.FaceImages
import software.altitude.core.models.Person
import software.altitude.core.pipeline.PipelineTypes.PipelineContext
import software.altitude.core.transactions.TransactionManager

object FaceRecognitionService {
  // Number of labels reserved for special cases, and not used for actual people instances
  // Labels start at this number + 1 but Unknown people start at 1 (so reserved label count must be known)
  val RESERVED_LABEL_COUNT = 10

  /**
   * If there is no machine learning model verified hit, we cycle through all people in the database, but only doing the matching
   * on THIS many best face detections that we have (1 to X)
   *
   * Higher number means more matches will be found, at the cost of performance.
   *
   * Lower number means faster matching but the same person may be detected as new. Technically, just 1 "top" face will work, and
   * the accuracy benefits get diminished the higher we go
   */
  val MAX_COMPARISONS_PER_PERSON = 12

  /** If the cosine distance between the facial features is below this threshold, we consider the face a match. */
  val PESSIMISTIC_COSINE_DISTANCE_THRESHOLD = .46
}

class FaceRecognitionService(val app: Altitude) {
  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = app.actorSystem.scheduler

  def initialize(): Unit = {
    val result: Future[AltitudeActorSystem.EmptyResponse] =
      app.actorSystem.ask(ref => FaceRecManagerActor.Initialize(RequestContext.getRepository.persistedId, ref))
    Await.result(result, timeout.duration)
  }

  def initializeAll(): Unit =
    app.service.library.forEachRepository(
      _ => {
        initialize()
      })

  def trainModelFromDb(): Unit = {
    txManager.asReadOnly {
      logger.info(s"Training model from DB for repo ${RequestContext.getRepository.name}")

      val facesForTraining: List[Face] = faceDao.getAllForTraining

      val pipelineContext = PipelineContext(RequestContext.getRepository, null)
      val source = Source.fromIterator(() => facesForTraining.iterator).map((_, pipelineContext))
      val pipelineResFuture: Future[Done] = app.service.bulkFaceRecTrainingPipelineService.run(source)
      Await.result(pipelineResFuture, Duration.Inf)

      val labelSizeFut: Future[ModelSize] =
        app.actorSystem ? (ref => FaceRecManagerActor.GetModelSize(RequestContext.getRepository.persistedId, ref))

      val labelCount = Await.result(labelSizeFut, timeout.duration).size
      logger.info(s"Trained model from DB. Labels: $labelCount")
    }
  }

  def trainModelsFromDbForAll(): Unit = {
    app.service.library.forEachRepository(
      _ => {
        trainModelFromDb()
      })
  }

  def processAsset(dataAsset: AssetWithData): Unit = {
    val faceWithImages = app.service.faceDetection.extractFaces(dataAsset.data)
    logger.info(s"Detected ${faceWithImages.size} faces")

    faceWithImages.foreach {
      case (detectedFace: Face, faceImages: FaceImages) =>
        val existingOrNewPerson = recognizeFace(detectedFace, dataAsset.asset)
        val persistedFace = app.service.person.addFace(detectedFace, dataAsset.asset, existingOrNewPerson)
        app.service.fileStore.addFace(persistedFace, faceImages)
        indexFace(persistedFace, existingOrNewPerson.label)
    }
  }

  /**
   * Returns an existing OR a new person, already persisted in the database.
   *
   * The person/faces are also added to the cache for this repository, as we may need to brute-force search for the person's face
   * in the future.
   */
  def recognizeFace(detectedFace: Face, asset: Asset): Person = {
    require(detectedFace.id.isEmpty, "Face object must not be persisted yet")
    require(detectedFace.personId.isEmpty, "Face object must not be associated with a person yet")

    val result: Future[FacePrediction] =
      app.actorSystem.ask(ref => FaceRecManagerActor.Predict(RequestContext.getRepository.persistedId, detectedFace, ref))
    val prediction = Await.result(result, timeout.duration)

    val personMlMatch: Option[Person] = app.service.faceCache.getPersonByLabel(prediction.label)
    if (personMlMatch.isDefined) {
      logger.info(s"ML face match: ${personMlMatch.get.label}, confidence: ${prediction.confidence}")
    }

    /**
     * If we have a match, we compare the match to the person's "best" face - the faces are sorted by detection score.
     *
     * This is called a "verified" match.
     *
     * We do NOT trust the ML model confidence score, as it will always return the closest "match", and the meaning of the score
     * is relative.
     */
    if (personMlMatch.isDefined) {
      logger.debug(f"Comparing ML match ${personMlMatch.get.persistedId})")
      val simScore =
        app.service.faceDetection.getFeatureSimilarityScore(detectedFace.featuresMat, personMlMatch.get.getFaces.head.featuresMat)
      logger.debug("Similarity score: " + simScore)

      if (simScore >= FaceRecognitionService.PESSIMISTIC_COSINE_DISTANCE_THRESHOLD) {
        logger.debug("Valid face match")
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
    val bestFaceMatch: Option[Face] = getBestFaceMatch(face)
    logger.debug("Best match: " + bestFaceMatch)

    bestFaceMatch match {
      case None => None
      case Some(matchedFace) => Some(matchedFace)
    }
  }

  private def getBestFaceMatch(thisFace: Face): Option[Face] = {
    logger.debug(s"Comparing $thisFace: ")
    val faceSimilarityScores: List[(Double, Face)] = app.service.faceCache.getAllMatchable.flatMap {
      person =>
        logger.debug(s"Comparing faces for person ${person.name.get}")
        // these are already sorted by detection score, best first
        val bestFaces = person.getFaces.toList.take(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON)
        val faceScores: List[(Double, Face)] = bestFaces.map {
          personFace =>
            val similarityScore =
              app.service.faceDetection.getFeatureSimilarityScore(thisFace.featuresMat, personFace.featuresMat)
            logger.debug(s"Comparing with $personFace -> " + similarityScore)
            (similarityScore, personFace)
        }

        faceScores
    }

    // get the top one
    val sortedMatchingSimilarityWeights = faceSimilarityScores.sortBy(_._1)
    val highestSimilarityWeights =
      sortedMatchingSimilarityWeights.filter(_._1 >= FaceRecognitionService.PESSIMISTIC_COSINE_DISTANCE_THRESHOLD)

    highestSimilarityWeights.headOption match {
      case None => None
      case Some(res) => Some(res._2)
    }
  }

  def indexFace(face: Face, personLabel: Int, repositoryId: String = RequestContext.getRepository.persistedId): Unit = {
    app.actorSystem ! FaceRecManagerActor.AddFace(repositoryId, face, personLabel)
  }

  def indexFaces(faces: Seq[Face], repositoryId: String = RequestContext.getRepository.persistedId): Unit = {
    app.actorSystem ! FaceRecManagerActor.AddFaces(repositoryId, faces)
  }

  def addFacesToPerson(faces: List[Face], person: Person): Unit = {
    faces.foreach(
      face => {
        person.addFace(face)
        indexFace(face, person.label)
      })
  }
}
