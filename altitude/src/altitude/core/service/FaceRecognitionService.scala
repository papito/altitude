package altitude.core.service

import altitude.core.Altitude
import altitude.core.dao.FaceDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt

object FaceRecognitionService {

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
  val COSINE_DISTANCE_THRESHOLD = .63
}

class FaceRecognitionService(val app: Altitude) {
  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = app.actorSystem.scheduler

  def processAsset(dataAsset: AssetWithData): Unit = {
    val faceWithImages = app.service.faceDetection.extractFaces(dataAsset.data)
    logger.info(s"Detected ${faceWithImages.size} faces")

    logger.info(s"Face rec on asset ${dataAsset.asset}")
    txManager.withFaceVector {
      faceWithImages.foreach {
        case (detectedFace: Face, faceImages: FaceImages) =>
          val existingOrNewPerson = recognizeFace(detectedFace, dataAsset.asset)
          val persistedFace = app.service.person.addFace(detectedFace, dataAsset.asset, existingOrNewPerson)
          app.service.fileStore.addFace(persistedFace, faceImages)
      }
    }
    logger.info(s"Face rec DONE: ${dataAsset.asset}")
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

    val matchedOrNewPerson: Person = txManager.withFaceVector {
      // will return just one for this
      val faceMatches: List[Face] = faceDao.searchClosestFaceMatches(detectedFace.features)

      // must have face matches, and they all have to be the same person
      if faceMatches.nonEmpty && faceMatches.map(_.personId.get).toSet.size <= 1 then {
        app.service.person.getPersonById(faceMatches.head.personId.get)
      } else {
        app.service.person.addPerson(Person())
      }
    }

    matchedOrNewPerson
  }
}
