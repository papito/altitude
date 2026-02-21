package altitude.core.service

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.actors.FaceRecManagerActor
import altitude.core.actors.FaceRecModelActor.FacePrediction
import altitude.core.actors.FaceRecModelActor.ModelSize
import altitude.core.dao.FaceDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Person
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.transactions.TransactionManager
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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
//    val result: Future[AltitudeActorSystem.EmptyResponse] =
//      app.actorSystem.ask(ref => FaceRecManagerActor.Initialize(RequestContext.getRepository.persistedId, ref))
//    Await.result(result, timeout.duration)
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

    // FIXME: to be rewritten with vectors
//    faceWithImages.foreach {
//      case (detectedFace: Face, faceImages: FaceImages) =>
//        val existingOrNewPerson = recognizeFace(detectedFace, dataAsset.asset)
//        val persistedFace = app.service.person.addFace(detectedFace, dataAsset.asset, existingOrNewPerson)
//        app.service.fileStore.addFace(persistedFace, faceImages)
//        indexFace(persistedFace, existingOrNewPerson.label)
//    }
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

    /**
     * If we have a match, we compare the match to the person's "best" face - the faces are sorted by detection score.
     *
     * This is called a "verified" match.
     *
     * We do NOT trust the ML model confidence score, as it will always return the closest "match", and the meaning of the score
     * is relative.
     */
    // No verified match, try brute-force comparisons on cached faces
    val bestPersonFaceMatch: Option[Face] = matchFaceBruteForce(detectedFace)

    val personModel = Person()
    val newPerson: Person = app.service.person.addPerson(personModel)
    newPerson
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
    None
  }

  private def indexFace(face: Face, personLabel: Int, repositoryId: String = RequestContext.getRepository.persistedId): Unit = {
    app.actorSystem ! FaceRecManagerActor.AddFace(repositoryId, face, personLabel)
  }

  def indexFaces(faces: Seq[Face], repositoryId: String = RequestContext.getRepository.persistedId): Unit = {
    app.actorSystem ! FaceRecManagerActor.AddFaces(repositoryId, faces)
  }
}
