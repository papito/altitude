package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.dao.FaceDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager

class FaceRecognitionService(val app: Altitude):
  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  /** Number of nearest-neighbor results to retrieve for majority-vote matching. */
  private val matchCount: Int = app.config.getInt(Const.Conf.FACE_RECOGNITION_MATCH_COUNT)

  /** Two detections closer than this are the same person, both in the vector index and within one Video */
  private val cosineDistanceThreshold: Double = app.config.getDouble(Const.Conf.FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD)

  def processAsset(dataAsset: AssetWithData): Unit =
    dataAsset.asset.assetType.mediaType match
      case "video" => processVideo(dataAsset)
      case _ => processImage(dataAsset)

  private def processImage(dataAsset: AssetWithData): Unit =
    val faceWithImages = app.service.faceDetection.extractFaces(dataAsset.bytes, Some(dataAsset.asset.fileName))
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

  /**
   * Faces in a Video: every Sampled frame is detected, the detections of the whole video are clustered by embedding, and each
   * cluster's representative (its highest-scoring detection, whose crop, box and Frame time the Face keeps) is recognized. A
   * Person gets one Face per Video: when two clusters resolve to the same Person, the lower-scoring one is dropped, so a pose
   * change that splits a person in two does not fail the import.
   */
  private def processVideo(dataAsset: AssetWithData): Unit =
    val durationMs = dataAsset.asset.durationMs.getOrElse(app.service.video.probe(dataAsset.path).durationMs)
    val times = app.service.video.sampleTimes(durationMs)

    val detections: List[(Face, FaceImages)] = app.service.video.sampledFrames(dataAsset.path, times) {
      frames =>
        frames.flatMap {
          frame =>
            val faces = app.service.faceDetection.extractFaces(frame.image, Some(dataAsset.asset.fileName))
            frame.image.release()
            faces.map { case (face, images) => (face.copy(frameTimeMs = Some(frame.timeMs)), images) }
        }.toList
    }
    logger.info(s"Detected ${detections.size} faces across ${times.size} Sampled frames of ${dataAsset.asset}")

    val representatives = clusterRepresentatives(detections)
    logger.info(s"${representatives.size} distinct faces in ${dataAsset.asset}")

    txManager.withFaceVector {
      representatives.foldLeft(Set.empty[String]) {
        case (peopleWithAFace, (detectedFace, faceImages)) =>
          val person = recognizeFace(detectedFace, dataAsset.asset)
          if peopleWithAFace.contains(person.persistedId) then
            logger.info(s"Person ${person.persistedId} already has a Face in ${dataAsset.asset}; dropping $detectedFace")
            peopleWithAFace
          else
            val persistedFace = app.service.person.addFace(detectedFace, dataAsset.asset, person)
            app.service.fileStore.addFace(persistedFace, faceImages)
            peopleWithAFace + person.persistedId
      }
    }
    logger.info(s"Face rec DONE: ${dataAsset.asset}")

  /**
   * In descending detection score, a detection joins the first cluster whose representative is within the cosine distance
   * threshold, or starts a new cluster; the representative is the cluster's first detection. Embeddings are L2-normalized, so the
   * cosine distance is one less the dot product.
   */
  private def clusterRepresentatives(detections: List[(Face, FaceImages)]): List[(Face, FaceImages)] =
    detections.sortBy(-_._1.detectionScore).foldLeft(List.empty[(Face, FaceImages)]) {
      case (representatives, detection) =>
        val known = representatives.exists {
          case (representative, _) => cosineDistance(representative.features, detection._1.features) < cosineDistanceThreshold
        }
        if known then representatives else representatives :+ detection
    }

  private def cosineDistance(a: Array[Float], b: Array[Float]): Double =
    1.0 - a.zip(b).map { case (x, y) => x.toDouble * y }.sum

  /**
   * Returns an existing OR a new person, already persisted in the database.
   *
   * Uses top-K nearest-neighbor search with majority voting: retrieves up to [[matchCount]] closest face matches from the vector
   * index, then picks the person ID that appears most frequently. If a clear majority exists, the face is associated with that
   * person; otherwise a new person is created.
   */
  def recognizeFace(detectedFace: Face, asset: Asset): Person =
    require(detectedFace.id.isEmpty, "Face object must not be persisted yet")
    require(detectedFace.personId.isEmpty, "Face object must not be associated with a person yet")

    val matchedOrNewPerson: Person = txManager.withFaceVector {
      val faceMatches: List[Face] = faceDao.searchClosestFaceMatches(detectedFace.features)

      if faceMatches.nonEmpty then
        // Majority vote: group by person ID, pick the most frequent
        val personVotes = faceMatches.groupBy(_.personId.get)
        val (bestPersonId, votes) = personVotes.maxBy(_._2.size)

        logger.debug(
          s"Face match: ${votes.size}/$matchCount votes for person $bestPersonId " +
            s"(${personVotes.size} distinct person(s) in top-${faceMatches.size})")

        app.service.person.getPersonById(bestPersonId)
      else
        logger.info("No match. Adding new person")
        app.service.person.addPerson(Person())
    }

    matchedOrNewPerson
