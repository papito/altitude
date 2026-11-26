package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

import altitude.core.Altitude
import altitude.core.dao.FaceDao
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.DetectedFaces
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.FaceMatch
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
   * A second pass: a cluster whose centroid is within the threshold of an earlier one's is absorbed by it. The first pass
   * compares with one leader, so it splits a face whose detections drift, as a turning head does; the halves' centroids are
   * closer.
   */
  def mergeClusters[A](clusters: List[Cluster[A]], features: A => Array[Float], threshold: Double): List[Cluster[A]] =
    def centroid(c: Cluster[A]): Array[Float] = meanNormalized(c.members.map(features))
    clusters.foldLeft(List.empty[Cluster[A]]) {
      (merged, cluster) =>
        merged.indexWhere(kept => cosineDistance(centroid(kept), centroid(cluster)) < threshold) match
          case -1 => merged :+ cluster
          case i => merged.updated(i, merged(i).copy(members = merged(i).members ++ cluster.members))
    }

  /** The Settings one recognition reads: the nearest Faces that vote, and how near a Face must be to vote */
  case class Recognition(matchCount: Int, distanceThreshold: Double)

  /** Whose a detection is once voted: a stored Person's, or the `n`-th Person the asset's recognition starts */
  enum Owner:
    case Known(personId: String)
    case Started(n: Int)

  /** The owner most of the candidates, nearest first, are of; a tie goes to the owner of the nearest. None without a candidate. */
  def vote[A](candidates: List[(A, Double)]): Option[A] =
    Option.when(candidates.nonEmpty) {
      // The candidates are in distance order, so the first index of a group is its closest
      val votes = candidates.map(_._1).zipWithIndex.groupBy(_._1)
      votes.maxBy { case (_, owned) => (owned.size, -owned.head._2) }._1
    }

  /**
   * Who each detection goes to, in detection order. Its candidates are its matches among the stored Faces and the asset's own
   * enrolled detections given an owner before it, which a search would find once stored, so the second face of one person in an
   * image joins the first; the nearest `matchCount` vote. With no candidate an enrolled detection starts a Person and a
   * match-only one is nobody's (None); with `oneFacePerPerson` (a Video), a detection voted to an owner already given one is None
   * too. Detections are L2-normalized, so their distances to each other are exact cosine distances; a PostgreSQL search finds the
   * stored Faces through an approximate index first.
   */
  def assign(
      detections: List[Face],
      matches: List[List[FaceMatch]],
      recognition: Recognition,
      oneFacePerPerson: Boolean): List[Option[Owner]] =
    val (_, _, owners) =
      detections.zip(matches).foldLeft((List.empty[(Face, Owner)], 0, List.empty[Option[Owner]])) {
        case ((assigned, started, owners), (detection, found)) =>
          val own = assigned.collect {
            case (face, owner) if face.isEnrolled => owner -> cosineDistance(face.features, detection.features)
          }
          val stored = found.map(m => (Owner.Known(m.personId): Owner) -> m.distance)
          val candidates = (stored ++ own.filter(_._2 < recognition.distanceThreshold))
            .sortBy(_._2)
            .take(recognition.matchCount)

          vote(candidates) match
            case Some(owner) if oneFacePerPerson && assigned.exists(_._2 == owner) => (assigned, started, owners :+ None)
            case Some(owner) => (assigned :+ (detection -> owner), started, owners :+ Some(owner))
            case None if detection.isEnrolled =>
              val owner = Owner.Started(started)
              (assigned :+ (detection -> owner), started + 1, owners :+ Some(owner))
            case None => (assigned, started, owners :+ None)
      }
    owners

class FaceRecognitionService(val app: Altitude):
  import FaceRecognitionService.*

  final val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val txManager: TransactionManager = app.txManager
  private val faceDao: FaceDao = app.DAO.face

  /** The faces of an asset, found without touching the database. An animated image (one with a duration) is skipped. */
  def detect(dataAsset: AssetWithData): DetectedFaces =
    dataAsset.asset.assetType.mediaType match
      case "video" => detectInVideo(dataAsset)
      case _ if dataAsset.asset.durationMs.isDefined =>
        logger.debug(s"Not detecting faces in animated ${dataAsset.asset}")
        DetectedFaces(Nil)
      case _ => detectInImage(dataAsset)

  /**
   * Matches each detected face to a person and stores it, in one transaction, in detection order ([[assign]]). The people the
   * faces go to are locked before the first Face is stored ([[lockedOwners]]). A Person gets one Face per Video: of two faces
   * resolving to the same Person, the later, lower-quality one is dropped, so a pose change that splits a person in two does not
   * fail the import.
   */
  def recognizeAndStore(asset: Asset, detected: DetectedFaces): Unit =
    logger.trace(s"Face rec on asset $asset")
    val recognition = currentRecognition

    withFaceFiles {
      store =>
        val owners = lockedOwners(detected.faces.map(_._1), recognition, oneFacePerPerson = asset.assetType.mediaType == "video")
        val started = scala.collection.mutable.Map.empty[Int, String]

        detected.faces.zip(owners).foreach {
          case ((face, faceImages), Some(owner)) =>
            val personId = owner match
              case Owner.Known(personId) => personId
              case Owner.Started(n) => started.getOrElseUpdate(n, startPerson().persistedId)
            // Read again for each Face: `addFace` reads the count and the cover the Face before it left
            store(face, faceImages, asset, app.service.person.getPersonById(personId))
          case ((face, _), None) =>
            logger.trace(s"$face matches nobody, or a Person already given a Face of Video $asset; dropped")
        }
    }
    logger.trace(s"Face rec DONE: $asset")

  private def startPerson(): Person =
    logger.debug("No match. Adding new person")
    app.service.person.addPerson(Person())

  private def detectInImage(dataAsset: AssetWithData): DetectedFaces =
    val faceWithImages = app.service.faceDetection.extractFaces(dataAsset.bytes, Some(dataAsset.asset.fileName))
    logger.debug(s"Detected ${faceWithImages.size} faces")
    DetectedFaces(faceWithImages)

  /**
   * Faces in a Video: the detections of every Sampled frame, clustered by embedding. Each cluster becomes one Face with its best
   * detection's crop, box, Frame time and quality, but the centroid of all members as the vector, steadier than any one frame. A
   * cluster seen in fewer than `video.faces.min_cluster_frames` frames is match-only, so a passer-by cannot start a Person.
   */
  private def detectInVideo(dataAsset: AssetWithData): DetectedFaces =
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
    logger.debug(s"Detected ${detections.size} faces across ${times.size} Sampled frames of ${dataAsset.asset}")

    // Settings, read once per Video
    val settings = app.settings.current
    val distanceThreshold = settings.faceRecognitionCosineDistanceThreshold
    val minClusterFrames = settings.videoFacesMinClusterFrames

    val features = (detection: (Face, FaceImages)) => detection._1.features
    val clusters = mergeClusters(cluster(detections, features, _._1.quality, distanceThreshold), features, distanceThreshold)
    logger.trace(s"${clusters.size} distinct faces in ${dataAsset.asset}")

    DetectedFaces(clusters.map {
      cluster =>
        val (best, faceImages) = cluster.best
        val support = cluster.members.map(_._1.frameTimeMs).distinct.size
        val face = best.copy(
          features = meanNormalized(cluster.members.map(features)),
          isEnrolled = best.isEnrolled && support >= minClusterFrames)
        logger.trace(s"Cluster of ${cluster.members.size} detections in $support frames: $face")
        (face, faceImages)
    })

  /** Persists a recognized Face and writes its files */
  private type StoreFace = (Face, FaceImages, Asset, Person) => Unit

  /**
   * Stores an asset's Faces in one transaction, writing each Face's files as it is stored. On rollback the files written are
   * deleted, so a stored Face always has its files and an unstored one leaves none.
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
        logger.trace(s"Face rec rolled back; deleting the files of ${written.size} faces")
        written.foreach(app.service.fileStore.purgeFaceById)
        throw ex

  /** The recognition Settings, read once per asset */
  private def currentRecognition: Recognition =
    val settings = app.settings.current
    Recognition(settings.faceRecognitionMatchCount, settings.faceRecognitionCosineDistanceThreshold)

  /**
   * The Person one detection goes to, as [[recognizeAndStore]] decides it for a detection alone, storing no Face: an enrolled
   * detection that matches nobody starts a Person, and a match-only one gets `None`. The tests' way to the vote; an import stores
   * through `recognizeAndStore`.
   */
  def recognizeFace(detectedFace: Face): Option[Person] =
    txManager.withFaceVector {
      lockedOwners(List(detectedFace), currentRecognition, oneFacePerPerson = false).head.map {
        case Owner.Known(personId) => app.service.person.getPersonById(personId)
        case Owner.Started(_) => startPerson()
      }
    }

  /**
   * Each detection's owner ([[assign]]), with the stored people among them locked and live. Every detection is searched first,
   * and only the people the vote gives a Face to are locked, all at once and in ID order, before the asset's first Face is
   * stored: an import takes them in the order every other transaction locking people does (a recycle, a restore, a correction, a
   * merge, a purge) and cannot deadlock with one. The locks are taken under a savepoint. A Person found no longer live once
   * locked was merged away, discarded, deleted or marked a bad match while the search ran: the locks are given back, the
   * detections that matched them are searched again, by when a merge has given their Faces to the destination, and the vote and
   * the locks are taken again, in ID order, over the whole new set.
   */
  private def lockedOwners(faces: List[Face], recognition: Recognition, oneFacePerPerson: Boolean): List[Option[Owner]] =
    faces.foreach {
      face =>
        require(face.id.isEmpty, "Face object must not be persisted yet")
        require(face.personId.isEmpty, "Face object must not be associated with a person yet")
    }

    def search(face: Face): List[FaceMatch] =
      faceDao.searchClosestFaceMatches(face.features, recognition.matchCount, recognition.distanceThreshold)

    @tailrec
    def settled(matches: List[List[FaceMatch]]): List[Option[Owner]] =
      val owners = assign(faces, matches, recognition, oneFacePerPerson)
      val winners = owners.flatten.collect { case Owner.Known(personId) => personId }.toSet
      val gone = txManager.undoingIf(winners -- app.service.person.lockLive(winners))(_.nonEmpty)

      if gone.isEmpty then owners
      else
        logger.debug(s"People [${gone.mkString(",")}] voted for are no longer live once locked; searching again")
        settled(faces.zip(matches).map {
          case (face, found) => if found.exists(m => gone.contains(m.personId)) then search(face) else found
        })

    settled(faces.map(search))
