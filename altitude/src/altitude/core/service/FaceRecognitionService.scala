package altitude.core.service

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.dao.FaceDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class FaceRecognitionService(val app: Altitude) {
  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  /** Number of nearest-neighbor results to retrieve for majority-vote matching. */
  private val matchCount: Int = app.config.getInt(Const.Conf.FACE_RECOGNITION_MATCH_COUNT)

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
   * Uses top-K nearest-neighbor search with majority voting: retrieves up to [[matchCount]] closest face matches from the vector
   * index, then picks the person ID that appears most frequently. If a clear majority exists, the face is associated with that
   * person; otherwise a new person is created.
   */
  def recognizeFace(detectedFace: Face, asset: Asset): Person = {
    require(detectedFace.id.isEmpty, "Face object must not be persisted yet")
    require(detectedFace.personId.isEmpty, "Face object must not be associated with a person yet")

    val matchedOrNewPerson: Person = txManager.withFaceVector {
      val faceMatches: List[Face] = faceDao.searchClosestFaceMatches(detectedFace.features)

      if (faceMatches.nonEmpty) {
        // Majority vote: group by person ID, pick the most frequent
        val personVotes = faceMatches.groupBy(_.personId.get)
        val (bestPersonId, votes) = personVotes.maxBy(_._2.size)

        logger.debug(s"Face match: ${votes.size}/$matchCount votes for person $bestPersonId " +
          s"(${personVotes.size} distinct person(s) in top-${faceMatches.size})")

        app.service.person.getPersonById(bestPersonId)
      } else {
        logger.info("No match. Adding new person")
        app.service.person.addPerson(Person())
      }
    }

    matchedOrNewPerson
  }
}
