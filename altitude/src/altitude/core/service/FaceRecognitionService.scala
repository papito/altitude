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

object FaceRecognitionService:

  /** The detections of one face across the Sampled frames of a Video; `best` is the highest-quality member */
  case class Cluster[A](best: A, members: List[A])

  /** Embeddings are L2-normalized, so the cosine distance is one less the dot product */
  def cosineDistance(a: Array[Float], b: Array[Float]): Double =
    1.0 - a.zip(b).map { case (x, y) => x.toDouble * y }.sum

  /** The mean of the vectors, L2-normalized again so it compares like any embedding */
  def meanNormalized(vectors: Seq[Array[Float]]): Array[Float] =
    val sum = new Array[Double](vectors.head.length)
    vectors.foreach(v => v.indices.foreach(i => sum(i) += v(i)))
    val norm = Math.sqrt(sum.map(x => x * x).sum)
    if norm == 0 then vectors.head else sum.map(x => (x / norm).toFloat)

  /**
   * Greedy leader clustering in descending quality: an item joins the first cluster whose best member is within the threshold, or
   * starts one. Leading with quality makes the sharpest detection the reference the others are compared with.
   */
  def cluster[A](items: Seq[A], features: A => Array[Float], quality: A => Double, threshold: Double): List[Cluster[A]] =
    items.sortBy(item => -quality(item)).foldLeft(List.empty[Cluster[A]]) {
      (clusters, item) =>
        clusters.indexWhere(c => cosineDistance(features(c.best), features(item)) < threshold) match
          case -1 => clusters :+ Cluster(item, List(item))
          case i => clusters.updated(i, clusters(i).copy(members = clusters(i).members :+ item))
    }

  /**
   * A second pass over clusters in the order [[cluster]] returns them: a cluster whose centroid is within the threshold of an
   * earlier cluster's centroid is absorbed by it. The single-member comparison of the first pass splits a face whose detections
   * drift, as a turning head does; the centroids of the halves are closer than their leaders.
   */
  def mergeClusters[A](clusters: List[Cluster[A]], features: A => Array[Float], threshold: Double): List[Cluster[A]] =
    def centroid(c: Cluster[A]): Array[Float] = meanNormalized(c.members.map(features))
    clusters.foldLeft(List.empty[Cluster[A]]) {
      (merged, cluster) =>
        merged.indexWhere(kept => cosineDistance(centroid(kept), centroid(cluster)) < threshold) match
          case -1 => merged :+ cluster
          case i => merged.updated(i, merged(i).copy(members = merged(i).members ++ cluster.members))
    }

class FaceRecognitionService(val app: Altitude):
  import FaceRecognitionService.*

  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  /** Number of nearest-neighbor results to retrieve for majority-vote matching. */
  private val matchCount: Int = app.config.getInt(Const.Conf.FACE_RECOGNITION_MATCH_COUNT)

  /** Two detections closer than this are the same person, both in the vector index and within one Video */
  private val cosineDistanceThreshold: Double = app.config.getDouble(Const.Conf.FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD)

  /** A face seen in fewer Sampled frames of a Video is match-only there */
  private val minClusterFrames: Int = app.config.getInt(Const.Conf.VIDEO_FACES_MIN_CLUSTER_FRAMES)

  def processAsset(dataAsset: AssetWithData): Unit =
    dataAsset.asset.assetType.mediaType match
      case "video" => processVideo(dataAsset)
      case _ => processImage(dataAsset)

  private def processImage(dataAsset: AssetWithData): Unit =
    val faceWithImages = app.service.faceDetection.extractFaces(dataAsset.bytes, Some(dataAsset.asset.fileName))
    logger.info(s"Detected ${faceWithImages.size} faces")

    logger.info(s"Face rec on asset ${dataAsset.asset}")
    withFaceFiles {
      store =>
        faceWithImages.foreach {
          case (detectedFace: Face, faceImages: FaceImages) =>
            recognizeFace(detectedFace) match
              case Some(person) => store(detectedFace, faceImages, dataAsset.asset, person)
              case None => logger.info(s"Match-only $detectedFace matches nobody; dropped")
        }
    }
    logger.info(s"Face rec DONE: ${dataAsset.asset}")

  /**
   * Faces in a Video: every Sampled frame is detected, and the detections of the whole video are clustered by embedding
   * ([[FaceRecognitionService.cluster]], then [[FaceRecognitionService.mergeClusters]]). Each cluster becomes one Face: the crop,
   * box, Frame time and quality of its best-quality detection, but the normalized centroid of all its members as the vector,
   * which is steadier than any single frame. A cluster seen in fewer than `video.faces.min_cluster_frames` frames is match-only,
   * so a passer-by in one frame cannot start a Person. A Person gets one Face per Video: when two clusters resolve to the same
   * Person, the lower-quality one is dropped, so a pose change that splits a person in two does not fail the import.
   */
  private def processVideo(dataAsset: AssetWithData): Unit =
    val times = app.service.video.sampleTimes(app.service.asset.videoDuration(dataAsset))

    val detections: List[(Face, FaceImages)] = app.service.video.sampledFrames(dataAsset.path, times) {
      frames =>
        frames.flatMap {
          frame =>
            val faces =
              try app.service.faceDetection.extractFaces(frame.image, Some(s"${frame.timeMs}ms-${dataAsset.asset.fileName}"))
              finally frame.image.release()
            faces.map { case (face, images) => (face.copy(frameTimeMs = Some(frame.timeMs)), images) }
        }.toList
    }
    logger.info(s"Detected ${detections.size} faces across ${times.size} Sampled frames of ${dataAsset.asset}")

    val features = (detection: (Face, FaceImages)) => detection._1.features
    val clusters =
      mergeClusters(cluster(detections, features, _._1.quality, cosineDistanceThreshold), features, cosineDistanceThreshold)
    logger.info(s"${clusters.size} distinct faces in ${dataAsset.asset}")

    withFaceFiles {
      store =>
        clusters.foldLeft(Set.empty[String]) {
          case (peopleWithAFace, cluster) =>
            val (best, faceImages) = cluster.best
            val support = cluster.members.map(_._1.frameTimeMs).distinct.size
            val face = best.copy(
              features = meanNormalized(cluster.members.map(features)),
              isEnrolled = best.isEnrolled && support >= minClusterFrames)
            logger.debug(s"Cluster of ${cluster.members.size} detections in $support frames: $face")

            recognizeFace(face) match
              case Some(person) if peopleWithAFace.contains(person.persistedId) =>
                logger.info(s"Person ${person.persistedId} already has a Face in ${dataAsset.asset}; dropping $face")
                peopleWithAFace
              case Some(person) =>
                store(face, faceImages, dataAsset.asset, person)
                peopleWithAFace + person.persistedId
              case None =>
                logger.info(s"Match-only $face matches nobody in ${dataAsset.asset}; dropped")
                peopleWithAFace
        }
    }
    logger.info(s"Face rec DONE: ${dataAsset.asset}")

  /** Persists a recognized Face and writes its files */
  private type StoreFace = (Face, FaceImages, Asset, Person) => Unit

  /**
   * Runs the storing of an asset's Faces in one transaction, the files of each Face written as it is stored. When the transaction
   * rolls back, the files already written are deleted: a Face in the database always has its files, and one that is not leaves
   * none behind.
   */
  private def withFaceFiles[A](f: StoreFace => A): A =
    val written = scala.collection.mutable.ListBuffer.empty[String]

    val store: StoreFace = (face, faceImages, asset, person) =>
      val persistedFace = app.service.person.addFace(face, asset, person)
      written += persistedFace.persistedId
      app.service.fileStore.addFace(persistedFace, faceImages)

    try txManager.withFaceVector(f(store))
    catch
      case ex: Exception =>
        logger.info(s"Face rec rolled back; deleting the files of ${written.size} faces")
        written.foreach(app.service.fileStore.purgeFaceById)
        throw ex

  /**
   * The Person for a Face, already persisted: the top-K nearest enrolled Faces within the distance threshold vote, the Person
   * with the most votes winning and a tie going to the closest. Without a match, an enrolled Face starts a new Person; a
   * match-only Face is nobody's, `None`, and is not stored.
   */
  def recognizeFace(detectedFace: Face): Option[Person] =
    require(detectedFace.id.isEmpty, "Face object must not be persisted yet")
    require(detectedFace.personId.isEmpty, "Face object must not be associated with a person yet")

    txManager.withFaceVector {
      val faceMatches: List[Face] = faceDao.searchClosestFaceMatches(detectedFace.features)

      if faceMatches.nonEmpty then
        // The matches are in distance order, so the first index of a group is its closest face
        val personVotes = faceMatches.zipWithIndex.groupBy(_._1.personId.get)
        val (bestPersonId, votes) = personVotes.maxBy { case (_, votes) => (votes.size, -votes.head._2) }

        logger.debug(
          s"Face match: ${votes.size}/$matchCount votes for person $bestPersonId " +
            s"(${personVotes.size} distinct person(s) in top-${faceMatches.size})")

        Some(app.service.person.getPersonById(bestPersonId))
      else if detectedFace.isEnrolled then
        logger.info("No match. Adding new person")
        Some(app.service.person.addPerson(Person()))
      else None
    }
