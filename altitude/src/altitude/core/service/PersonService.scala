package altitude.core.service

import java.sql.SQLException
import java.text.Normalizer

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.dao.FaceDao
import altitude.core.dao.PersonDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.ListedPeople
import altitude.core.models.ListedPerson
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager
import altitude.core.util.NameKey
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SearchQuery
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

object PersonService:
  val UNKNOWN_NAME_PREFIX = "Unknown"

  // An unnamed person's form of name ("Unknown 42"), in any case and spacing, Unicode spaces and digits included. The spacing
  // around it is in the pattern, as `String.strip` leaves the no-break spaces (U+00A0, U+2007, U+202F)
  private val RESERVED_NAME = s"(?iU)\\s*$UNKNOWN_NAME_PREFIX\\s*\\d+\\s*".r

  /** A name as stored: NFC, so a name typed with a combining accent and the same name typed precomposed are one name */
  def normalizedName(name: String): String = NameKey.normalized(name)

  /**
   * The name key ([[NameKey.of]]): the folded form of a name that `person_01` keeps unique, stored as a named person's
   * `name_for_sort`, so "Weiß" and "WEISS", or "σοφος" and "σοφοσ", are one name
   */
  def nameKey(name: String): String = NameKey.of(name)

  /**
   * Whether a name has an unnamed person's form, which no person may be given: an unnamed person's sort name (`unknown 0042`)
   * would otherwise equal a named person's key, or sit beside it under another. The form is matched in NFKC, as the key folds it,
   * so a full-width "Ｕnknown 42" or one with circled digits is reserved too.
   */
  def isReservedName(name: String): Boolean = RESERVED_NAME.matches(Normalizer.normalize(name, Normalizer.Form.NFKC))

  /** The most people the Change combobox's Suggested group holds ([[PersonService.suggestedPeople]]) */
  val SUGGESTED_PEOPLE = 3

  /**
   * The nearest Faces read to find them: the nearest people's Faces come several deep before the next Person's, so more than the
   * people wanted, as many as recognition takes from the vector index by default
   */
  private val SUGGESTION_CANDIDATES = 20

  /** Whom a Face is given to by [[PersonService.moveFace]]: a live Person by ID, or by name, which may create the Person */
  enum FaceTarget:
    case ExistingPerson(personId: String)
    case Named(name: String)

  /**
   * The options of the Change combobox ([[PersonService.assignablePeople]]): the Suggested group, closest first, and the other
   * people a Face can be given to, in the assignable order, neither holding the Face's own Person
   */
  case class AssignablePeople(suggested: List[Person], others: List[Person])

  /**
   * What [[PersonService.removeFace]] did: the Face as it was, its Person as they are after the removal (the cover repicked) or,
   * when the Face was their last, as they were before they were deleted
   */
  case class FaceRemoval(face: Face, person: Person, personDeleted: Boolean)

  /**
   * What [[PersonService.moveFace]] did: the Face as it was, its former Person as they are after the move (or as they were before
   * they were deleted, when the Face was their last) and the Person it was given to, as they are after
   */
  case class FaceMove(face: Face, source: Person, target: Person, sourceDeleted: Boolean)

  /**
   * What [[PersonService.nameFacePerson]] or [[PersonService.mergeFacePerson]] did to a Face's Person as a whole: the Face, its
   * Person as they were before, with the name they had, and the Person who has the Face now, as they are after: the same Person
   * named, or the Person they were merged into
   */
  case class FacePersonChange(face: Face, before: Person, after: Person)

class PersonService(val app: Altitude) extends BaseService[Person]:
  import PersonService.*

  protected val dao: PersonDao = app.DAO.person
  private val faceDao: FaceDao = app.DAO.face

  override protected val txManager: TransactionManager = app.txManager

  override def add(objIn: Person): Person =
    throw NotImplementedError("Use the alternate addPerson() method")

  def getPersonById(personId: String): Person =
    txManager.asReadOnly {
      dao.getById(personId)
    }

  def getFaceById(faceId: String): Face =
    txManager.asReadOnly {
      faceDao.getById(faceId)
    }

  /**
   * The Person by ID when they are live in the context repository, hidden or not: one merged away or marked as a bad match is not
   * found, as another repository's or none is
   */
  def getLiveInRepository(personId: String): Person =
    val live = new Query()
      .add(FieldConst.ID -> personId)
      .add(FieldConst.Person.IS_DELETED -> false)
      .add(FieldConst.Person.IS_BAD_MATCH -> false)
    query(live).records.headOption.getOrElse {
      throw NotFoundException(s"Live person [$personId] not found")
    }

  /**
   * The people of the IDs who are live in the context repository, hidden or not, in no order: an ID of a Person merged away or
   * marked as a bad match, of another repository's or of nobody has no entry
   */
  def getLive(personIds: Set[String]): List[Person] =
    if personIds.isEmpty then return Nil

    val live = new Query(params = Map(FieldConst.ID -> Query.IN(personIds.toSet[Any])))
      .add(FieldConst.Person.IS_DELETED -> false)
      .add(FieldConst.Person.IS_BAD_MATCH -> false)
    query(live).records

  /**
   * Stores the Face as the person's in the asset, sets it as the cover when the person has none, and counts the asset toward
   * `numOfFaces` when it is the person's first Face on it: the number counts the assets a person is found on, so a second Face of
   * theirs on the same asset (the same person twice in one image) adds nothing
   */
  def addFace(face: Face, asset: Asset, person: Person): Face =
    require(person.persistedId.nonEmpty, "Cannot add a face to an unsaved person object")
    require(asset.persistedId.nonEmpty, "Cannot add a face to an unsaved asset object")

    txManager.withFaceVector[Face] {
      val firstOnAsset = !faceDao.hasFaceOfPerson(asset.persistedId, person.persistedId)

      var persistedFace: Option[Face] = None
      try persistedFace = Some(faceDao.add(face, asset, person))
      catch
        case e: SQLException =>
          throw newDuplicateExceptionOrRethrow(
            e,
            Some(s"Face already exists for person ${person.persistedId} in asset ${asset.persistedId}"))

      // The Face is the person's own, on the asset being imported: the checks of `setFaceAsCover` hold by construction
      if person.numOfFaces == 0 then
        updateById(person.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> persistedFace.get.persistedId))

      if firstOnAsset then increment(person.persistedId, FieldConst.Person.NUM_OF_FACES)
      logger.trace(
        s"Added face [${persistedFace.get.persistedId}] to person [${person.persistedId}] in asset [${asset.persistedId}]")

      persistedFace.get
    }

  def addPerson(person: Person): Person =
    txManager.withTransaction {
      require(person.getFaces.size < 2, "Adding a new person with more than one face is currently not supported")

      val personForUpdate = person.copy(
        numOfFaces = person.getFaces.size
      )

      val added = dao.add(personForUpdate)
      logger.trace(s"Added person [${added.persistedId}]")
      added
    }

  /**
   * Moves the source's faces to the destination, soft-deletes the source and recounts the destination's faces. Both people are
   * locked first, so what this merge reads is what it acts on. The merged name is decided from the stored rows, not from the
   * models passed in, which may be stale: an unnamed destination takes the name of a named source that is still live (neither
   * merged away nor a bad match), with its name key as sort name and the named flag, and a named destination keeps its own. The
   * source is soft-deleted before the destination takes its name, so `person_01` never sees the name on two live people. On a
   * Video where both had a Face, one Face per Person per Video holds, as at import and after a move ([[moveFace]]): the better
   * Face stays and the other is deleted ([[keepOneFacePerVideo]]), its files going once committed. A destination with no cover
   * takes the source's cover, or their best Face when the source has none or it is on an asset marked for purging; it is written
   * after the source is soft-deleted, so `person_02` never sees the cover on two live people.
   */
  def merge(dest: Person, source: Person): Person =
    if source == dest then throw IllegalArgumentException("Cannot merge a person with itself. That's perverse!")

    val (merged, lost) = txManager.withTransaction {
      mergeLocked(dest, source)
    }
    purgeFaceFiles(lost)
    merged

  /**
   * [[merge]] in the caller's transaction: the destination as they are after it, and the Faces the merge deleted, for
   * `purgeFaceFiles` once the caller's transaction commits
   */
  private def mergeLocked(dest: Person, source: Person): (Person, List[Face]) =
    val liveIds: Set[String] = dao.lockAndGetLive(Set(dest.persistedId, source.persistedId))
    val persistedDest: Person = dao.getById(dest.persistedId)
    val persistedSource: Person = dao.getById(source.persistedId)

    logger.debug(
      s"Merging person [${persistedSource.persistedId}] ${persistedSource.name.get} " +
        s"into [${persistedDest.persistedId}] ${persistedDest.name.get}")

    // Read before the Faces move, while each still says whose it is, which a tie between two of them needs
    val sharedVideoFaces = faceDao.getFacesOnSharedVideos(persistedDest.persistedId, persistedSource.persistedId)

    val query = new Query().add(FieldConst.Face.PERSON_ID -> persistedSource.persistedId)

    // faces from source are moved to the new person
    faceDao.updateByQuery(query, Map(FieldConst.Face.PERSON_ID -> persistedDest.persistedId))

    updateById(
      persistedSource.persistedId,
      Map(
        FieldConst.Person.NUM_OF_FACES -> 0,
        FieldConst.Person.IS_DELETED -> true
      )
    )

    // After the source is soft-deleted: the destination may then take a cover of theirs, `person_02` keeping a cover to one live
    // person
    val lostFaces = keepOneFacePerVideo(sharedVideoFaces, persistedDest, persistedSource)

    // A source no longer live holds no name: another live person may hold it since
    val takenName: Option[String] =
      if !persistedDest.isNamed && persistedSource.isNamed && liveIds.contains(persistedSource.persistedId) then
        persistedSource.name
      else None

    val recountQuery = new SearchQuery(
      params = Map(FieldConst.Asset.IS_RECYCLED -> false),
      personIds = Set(persistedDest.persistedId)
    )

    val mergedFaceCount = app.service.library.count(recountQuery)

    val nameFields: Map[String, Any] = takenName match
      case Some(name) =>
        logger.debug(s"Person [${persistedDest.persistedId}] takes the name [$name] of the merged person")
        Map(
          FieldConst.Person.NAME -> name,
          FieldConst.Person.NAME_FOR_SORT -> PersonService.nameKey(name),
          FieldConst.Person.IS_NAMED -> true
        )
      case None => Map.empty

    val coverFields: Map[String, Any] =
      if persistedDest.coverFaceId.nonEmpty then Map.empty
      else
        // The source's cover as it is now: one lost on a Video has moved to the survivor, which the destination holds
        val sourceCover = dao
          .getById(persistedSource.persistedId)
          .coverFaceId
          .flatMap(faceId => faceDao.getByIds(Set(faceId)).headOption)
          .filter(face => face.personId.contains(persistedDest.persistedId) && !isOnAssetMarkedForPurging(face))
        val cover = sourceCover.orElse(faceDao.getBestCoverCandidate(persistedDest.persistedId, Set.empty))
        logger.debug(
          s"Person [${persistedDest.persistedId}], who has no cover, takes [${cover.map(_.persistedId).getOrElse("none")}]")
        Map(FieldConst.Person.COVER_FACE_ID -> cover.map(_.persistedId))

    updateById(persistedDest.persistedId, Map(FieldConst.Person.NUM_OF_FACES -> mergedFaceCount) ++ nameFields ++ coverFields)

    // Read again, as the name, the count and the cover may have changed
    (dao.getById(persistedDest.persistedId), lostFaces)

  /**
   * For a merge, given the two people's Faces on the Videos where both had one (read before the source's Faces moved): on each
   * Video the Face of the higher quality stays, the destination's own on a tie as in [[moveFace]], and the others are deleted, as
   * [[removeFace]] deletes a Face; a cover of either person that pointed at one of them moves to the survivor. The deleted Faces,
   * whose files are to go once the merge commits.
   */
  private def keepOneFacePerVideo(faces: List[Face], dest: Person, source: Person): List[Face] =
    faces.groupBy(_.assetId.get).toList.flatMap {
      case (videoId, videoFaces) =>
        val survivor = videoFaces.maxBy(face => (face.quality, face.personId.contains(dest.persistedId)))
        val lost = videoFaces.filterNot(_.persistedId == survivor.persistedId)

        lost.foreach {
          face =>
            logger.debug(
              s"Face [${face.persistedId}] loses to face [${survivor.persistedId}] in video [$videoId] on a merge; deleted")
            faceDao.deleteById(face.persistedId)
        }
        List(dest, source).filter(person => lost.exists(face => person.coverFaceId.contains(face.persistedId))).foreach {
          person => updateById(person.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> survivor.persistedId))
        }
        lost
    }

  def getPersonFaces(personId: String, limit: Int = 50): List[Face] =
    txManager.asReadOnly {
      faceDao.getTopFaces(personId, limit)
    }

  /**
   * When an asset gets recycled, each person with a Face on it loses that asset from `numOfFaces`, however many Faces of theirs
   * it holds: the number counts the assets a person is found on outside the trash. The people are locked first
   * (`PersonDao.lockAssetPeople`), after the caller's lock on the assets, so a correction holding one of them ([[removeFace]],
   * [[moveFace]]) ends before the count is read: read while it held, from a snapshot in which its Face still existed, the count
   * would take the asset from the Person twice.
   *
   * We don't do anything else, as we remove the actual faces during asset Purge.
   *
   * If an asset is restored, we just do the reverse of this and everyone is happy.
   */
  def recycleFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Recycling the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
      dao.lockAssetPeople(assetIds)
      dao.recycleFacesForAssets(assetIds)
    }

  def restoreFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Restoring the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
      dao.lockAssetPeople(assetIds)
      dao.restoreFacesForAssets(assetIds)
    }

  def getAssetFaces(assetId: String): List[Face] =
    txManager.asReadOnly {
      faceDao.getAssetFaces(assetId)
    }

  def getFacesOfAssets(assetIds: Set[String]): List[Face] =
    txManager.asReadOnly {
      faceDao.getFacesOfAssets(assetIds)
    }

  def getPeopleForAsset(assetId: String): List[Person] =
    txManager.asReadOnly {
      peopleOf(getAssetFaces(assetId))
    }

  /**
   * Every face of an asset, each with its person, whoever that is (hidden and bad-match people included), read in one snapshot so
   * a merge cannot come between the two
   */
  def getAssetFacesWithPeople(assetId: String): List[(Face, Person)] =
    txManager.asReadOnly {
      val faces = faceDao.getAllAssetFaces(assetId)
      val peopleById = peopleOf(faces).map(person => person.persistedId -> person).toMap
      faces.map(face => face -> peopleById(face.personId.get))
    }

  /**
   * Deletes those of the people who have no face left, and gives back their IDs. Recognition matches face rows, so a person is
   * left without any only by an import that started them being discarded, or by a correction ([[removeFace]], [[moveFace]])
   * taking their last Face.
   */
  def deletePeopleWithoutFaces(personIds: Set[String]): Set[String] =
    txManager.withTransaction {
      val deleted = dao.deleteFaceless(personIds)
      if deleted.nonEmpty then logger.debug(s"Deleted people left without faces [${deleted.mkString(",")}]")
      deleted
    }

  /**
   * Deletes the files of Faces whose rows are gone, or are going with their asset. No cover is left on one of them: every path
   * that deletes a Face moves the cover off it first (the corrections through [[settleAfterFaceLeft]], purge and the discard of
   * an import through [[moveCoversOff]]).
   */
  def purgeFaceFiles(faces: List[Face]): Unit =
    faces.foreach {
      face =>
        logger.trace(s"Removing the files of face [${face.persistedId}]")
        app.service.fileStore.purgeFaceById(face.persistedId)
    }

  /**
   * Moves every cover off the Faces of the assets, whose Faces are about to go (a purge, the discard of an import), in one
   * transaction. The people with a Face on them are locked first (`PersonDao.lockAssetPeople`, in ID order), so that a face
   * another import gives one of them meanwhile, a correction of one of their Faces, or a cover being set waits for it to end;
   * where the caller holds the assets' locks, they come first: assets, then people, in every transaction that locks both. Each
   * Person whose cover is one of those Faces, live or not, then takes their best Face on another asset not marked for purging
   * (`FaceDao.getBestCoverCandidate`), or no cover when none is left. Run before the Faces' rows go, so no cover is ever left on
   * a deleted Face.
   */
  def moveCoversOff(assetIds: Set[String]): Unit =
    if assetIds.isEmpty then return

    txManager.withTransaction {
      dao.lockAssetPeople(assetIds)

      val faceIds = faceDao
        .query(new Query().add(FieldConst.Face.ASSET_ID -> Query.IN(assetIds.toSet[Any])))
        .records
        .map(_.persistedId)
        .toSet[Any]

      if faceIds.nonEmpty then
        val coverHolders = dao.query(new Query().add(FieldConst.Person.COVER_FACE_ID -> Query.IN(faceIds))).records

        coverHolders.foreach {
          person =>
            val cover = faceDao.getBestCoverCandidate(person.persistedId, assetIds)
            logger.debug(
              s"Cover of person [${person.persistedId}] moves off face [${person.coverFaceId.get}] to " +
                s"[${cover.map(_.persistedId).getOrElse("none")}]")
            updateById(person.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> cover.map(_.persistedId)))
        }
    }

  /** Every Face of an asset, whoever its person is; see `FaceDao.getAllAssetFaces` */
  def getAllAssetFaces(assetId: String): List[Face] =
    txManager.asReadOnly {
      faceDao.getAllAssetFaces(assetId)
    }

  /**
   * The Faces of an asset for the Detail panel, each with its person: hidden people included, bad matches left out, in reading
   * order for an image (by `x1`) and by Frame time for a Video
   */
  def getAssetFacesForDetail(assetId: String): List[(Face, Person)] =
    txManager.asReadOnly {
      getAssetFacesWithPeople(assetId)
        .filterNot { case (_, person) => person.isBadMatch }
        .sortBy { case (face, _) => (face.frameTimeMs.getOrElse(0L), face.x1) }
    }

  /** The people a Face can be given to; see `PersonDao.getAssignable` */
  def getAssignable: List[Person] =
    txManager.asReadOnly {
      dao.getAssignable
    }

  /**
   * The people whose Faces are nearest the Face's own vector, closest first, the first `SUGGESTED_PEOPLE` distinct ones: the
   * Change combobox's Suggested group. The candidates are what recognition sees (enrolled Faces of people who are not a bad
   * match, within the cosine distance threshold, in this repository) less the Face's own Person, whom the SQL leaves out so that
   * a Person with many Faces cannot fill every candidate. Nothing is stored and nothing votes, so the search is a read
   * (`withFaceVectorReadOnly`). A Face gone, or of another repository, is not found.
   */
  def suggestedPeople(faceId: String): List[Person] =
    txManager.withFaceVectorReadOnly {
      suggestedFor(faceDao.getById(faceId))
    }

  /** [[suggestedPeople]] for a Face read in the caller's transaction, which is set up for searching face vectors */
  private def suggestedFor(face: Face): List[Person] =
    // How close a Face must be to count: recognition's Setting, read per call
    val distanceThreshold = app.settings.current.faceRecognitionCosineDistanceThreshold
    val faceId = face.persistedId

    val features = faceDao.getFeatures(faceId).getOrElse(throw NotFoundException(s"Face [$faceId] not found"))
    val nearest = faceDao.searchNearestFacesOfOthers(features, face.personId.get, SUGGESTION_CANDIDATES, distanceThreshold)
    val personIds = nearest.map(_.personId.get).distinct.take(SUGGESTED_PEOPLE)
    val people = peopleByIds(personIds.toSet).map(person => person.persistedId -> person).toMap
    logger.trace(s"Face [$faceId]: ${personIds.size} suggested people among ${nearest.size} nearest Faces")
    personIds.map(people)

  /**
   * The options of the Change combobox, read in one snapshot: given the Face, its Suggested group ([[suggestedPeople]]) and then
   * the assignable people ([[getAssignable]]) less the suggested ones and the Face's own Person, so each Person is one option and
   * the Face's own, who is no target, is none; without a Face, the assignable people alone. A Face gone, or of another
   * repository, is not found.
   */
  def assignablePeople(faceId: Option[String]): AssignablePeople =
    txManager.withFaceVectorReadOnly {
      val face = faceId.map(faceDao.getById)
      val suggested = face.fold(List.empty[Person])(suggestedFor)
      val leftOut = suggested.map(_.persistedId).toSet ++ face.map(_.personId.get)
      AssignablePeople(suggested, getAssignable.filterNot(person => leftOut.contains(person.persistedId)))
    }

  /**
   * Removes a Face of an asset outside the trash: the row goes, and its Person loses the asset from `numOfFaces` if they have no
   * other Face on it, has their cover repicked (their remaining Face with the highest detection score) if it was this Face, and
   * is deleted if no Face is left. One transaction, under the Person's row lock, so a concurrent correction of the same Face
   * waits and then finds it gone (`NotFoundException`); a Face of a recycled asset is an `IllegalOperationException`. Once
   * committed, the Face's files go.
   */
  def removeFace(faceId: String): FaceRemoval =
    val removal = txManager.withTransaction {
      // The Face as it is before anything is locked, to learn whom to lock
      val personId = faceDao.getById(faceId).personId.get
      dao.lockAndGetLive(Set(personId)): Unit
      val (face, _) = lockedFace(faceId, personId)
      // Read before the Face goes: the cover's key sets a cover that was this Face to NULL, and settling repicks it from this
      val before = dao.getById(personId)

      faceDao.deleteById(face.persistedId)
      val (person, deleted) = settleAfterFaceLeft(face, before)
      logger.debug(s"Removed face [$faceId] of person [$personId] from asset [${face.assetId.get}]; person deleted: $deleted")
      FaceRemoval(face, person, deleted)
    }

    purgeFaceFiles(List(removal.face))
    removal

  /**
   * Gives a Face of an asset outside the trash to another Person: an existing one by ID, which must be live (neither merged away
   * nor a bad match, hidden or not), or by name, which resolves to the live Person holding it in any case or Unicode form, or
   * creates a named Person. The vector moves with the row, so later imports match it to the new Person. Both people are locked
   * (in ID order, after the name's own lock, so creators of one name take turns and the loser finds the winner), the Face
   * re-read, and then: the target counts the asset if they had no Face on it, and takes the Face as cover if they have none; the
   * source loses the asset if no Face of theirs is left on it, has their cover repicked if it was this Face, and is deleted if no
   * Face is left. On a Video where the target already has a Face, one Face per Person per Video holds as at import: the
   * lower-quality of the two (the target's own on a tie) is deleted as [[removeFace]] deletes it, and a cover that pointed at it
   * moves to the survivor. A Face gone or moved meanwhile is a `NotFoundException`, as is a target that is not live; a Face of a
   * recycled asset an `IllegalOperationException`, and the Face's own Person a `ValidationException`.
   */
  def moveFace(faceId: String, target: FaceTarget): FaceMove =
    val (move, loser) = txManager.withTransaction {
      val sourceId = faceDao.getById(faceId).personId.get
      val targetId = target match
        case FaceTarget.ExistingPerson(personId) => personId
        case FaceTarget.Named(name) => personIdForName(name)
      if targetId == sourceId then throw ValidationException(C.Msg.Err.FACE_ALREADY_THEIRS)

      val liveIds = dao.lockAndGetLive(Set(sourceId, targetId))
      if !liveIds.contains(targetId) then throw NotFoundException(s"Person [$targetId] is not live")
      val (face, asset) = lockedFace(faceId, sourceId)
      val assetId = asset.persistedId
      val source = dao.getById(sourceId)
      val targetBefore = dao.getById(targetId)

      // The target's Face on the Video, if any: with it the move keeps one of the two Faces
      val rival: Option[Face] =
        if asset.assetType.mediaType != "video" then None
        else faceDao.getAllAssetFaces(assetId).filter(_.personId.contains(targetId)).sortBy(-_.quality).headOption

      rival match
        case Some(kept) if kept.quality >= face.quality =>
          // The target's own Face is the better one: the moved Face goes instead, as a removal would take it
          logger.debug(s"Face [$faceId] loses to face [${kept.persistedId}] of person [$targetId] in video [$assetId]; deleted")
          faceDao.deleteById(face.persistedId)
          val (sourceAfter, sourceDeleted) = settleAfterFaceLeft(face, source)
          (FaceMove(face, sourceAfter, dao.getById(targetId), sourceDeleted), Some(face))
        case _ =>
          val targetHadAsset = rival.isDefined || faceDao.hasFaceOfPerson(assetId, targetId)
          faceDao.updateById(face.persistedId, Map(FieldConst.Face.PERSON_ID -> targetId))

          // The source first: a cover of theirs that was this Face is repicked, or the source deleted, before the target may take
          // the Face as cover, which the unique index over live people's covers allows one person at a time
          val (sourceAfter, sourceDeleted) = settleAfterFaceLeft(face, source)

          // The target's worse Face goes, the moved Face taking its place as cover if it was the cover
          val lost = rival.map {
            worse =>
              logger.debug(
                s"Face [${worse.persistedId}] of person [$targetId] loses to face [$faceId] in video [$assetId]; deleted")
              faceDao.deleteById(worse.persistedId)
              settleAfterFaceLeft(worse, targetBefore, successor = Some(face))
              worse
          }

          if !targetHadAsset then increment(targetId, FieldConst.Person.NUM_OF_FACES)
          if targetBefore.coverFaceId.isEmpty then updateById(targetId, Map(FieldConst.Person.COVER_FACE_ID -> face.persistedId))
          logger.debug(s"Moved face [$faceId] from person [$sourceId] to [$targetId]; source deleted: $sourceDeleted")
          (FaceMove(face, sourceAfter, dao.getById(targetId), sourceDeleted), lost)
    }

    // The deleted Face's files, once committed: the target's lost Face (its cover now the moved Face) or the moved Face itself
    purgeFaceFiles(loser.toList)
    move

  /**
   * Names a Face's Person as a whole, as [[updateName]] names them: every asset of theirs follows. Only a Person with no name is
   * named this way. `personId` is the Person the client showed with the Face, which is checked rather than read from the Face:
   * the Face may have been given to someone else since the panel was rendered, and the name must not land on whoever has it now.
   * One transaction: the name's lock is taken first (`lockName`), as a move to a name takes it, so a move creating a Person of
   * that name meanwhile waits and then finds this one holding it rather than failing on `person_01`; then the Person is locked
   * (`lockAndGetLive`) and the Face re-read under the lock, so a Face gone or given away is a `NotFoundException`, as is a Person
   * no longer live, and a Face of a recycled asset an `IllegalOperationException`, as is a Person who has a name by now, since
   * the panel showing them is stale. A reserved name is a `ValidationException`; a name another live Person holds, a
   * `DuplicateException` (`person_01`).
   */
  def nameFacePerson(faceId: String, personId: String, name: String): FacePersonChange =
    txManager.withTransaction {
      // The name first and the people after, in the order a move to a name takes them
      dao.lockName(nameKey(name))
      val (face, person) = lockedNamelessFacePerson(faceId, personId, Set.empty)
      val named = updateName(person, name)
      logger.debug(s"Person [$personId] of face [$faceId] named [${named.name.get}], formerly [${person.name.get}]")
      FacePersonChange(face, person, named)
    }

  /**
   * Merges a Face's Person as a whole into a named Person (`destId`), as [[merge]] merges them: every Face of theirs becomes the
   * destination's, whose ID, name and Hidden state stay. Only a Person with no name is merged this way, and only into a named
   * Person. `personId` is the Person the client showed with the Face, which is checked rather than read from the Face: the Face
   * may have been given to someone else since the panel was rendered, and that someone must not be merged in their place. One
   * transaction: both people are locked (`lockAndGetLive`, in ID order) and the Face re-read under the lock, so a Face gone or
   * given away is a `NotFoundException`, as is either Person no longer live or of another repository, and a Face of a recycled
   * asset an `IllegalOperationException`, as is a Person who has a name by now, since the panel showing them is stale. A
   * destination with no name and the Person themselves are a `ValidationException`. The Faces the merge deleted on Videos lose
   * their files once the transaction commits.
   */
  def mergeFacePerson(faceId: String, personId: String, destId: String): FacePersonChange =
    if destId == personId then throw ValidationException(C.Msg.Err.FACE_ALREADY_THEIRS)

    val (change, lost) = txManager.withTransaction {
      val (face, source) = lockedNamelessFacePerson(faceId, personId, Set(destId))
      val dest = dao.getById(destId)
      if !dest.isNamed then throw ValidationException(C.Msg.Err.MERGE_INTO_NAMELESS)

      logger.debug(s"Merging person [$personId] of face [$faceId] into [$destId]")
      val (merged, lost) = mergeLocked(dest, source)
      (FacePersonChange(face, source, merged), lost)
    }
    purgeFaceFiles(lost)
    change

  /**
   * For a change of a Face's Person as a whole: locks the Person and the `others` (`lockAndGetLive`, in ID order), each of whom
   * must be live, re-reads the Face under the lock ([[lockedFace]]) and answers it with the Person, who must have no name still:
   * one named since is an `IllegalOperationException`, as the client's section is stale
   */
  private def lockedNamelessFacePerson(faceId: String, personId: String, others: Set[String]): (Face, Person) =
    val ids = others + personId
    val notLive = ids -- dao.lockAndGetLive(ids)
    if notLive.nonEmpty then throw NotFoundException(s"People [${notLive.mkString(",")}] are not live")

    val (face, _) = lockedFace(faceId, personId)
    val person = dao.getById(personId)
    if person.isNamed then
      logger.debug(s"Person [$personId] of face [$faceId] has a name by now: [${person.name.get}]")
      throw IllegalOperationException(C.Msg.Err.FACE_PERSON_NAMED_SINCE)
    (face, person)

  /**
   * The Face re-read under its Person's lock, with its asset: gone, or given to another Person meanwhile, it is not found; on an
   * asset in the trash, it is left alone
   */
  private def lockedFace(faceId: String, personId: String): (Face, Asset) =
    val face = faceDao.getById(faceId)
    if !face.personId.contains(personId) then throw NotFoundException(s"Face [$faceId] of person [$personId] not found")
    val asset: Asset = app.service.asset.getById(face.assetId.get)
    if asset.isRecycled then throw IllegalOperationException(C.Msg.Err.FACE_OF_RECYCLED_ASSET)
    (face, asset)

  /**
   * The live Person holding the name, or a Person created with it. The name's lock makes concurrent creators of one name take
   * turns, so the second finds the first's Person. A name the name rule refuses ([[validName]]) is refused as a rename to it is.
   */
  private def personIdForName(name: String): String =
    val normalized = validName(name)

    val key = nameKey(normalized)
    dao.lockName(key)
    dao.getLiveByNameKey(key) match
      case Some(holder) => holder.persistedId
      case None =>
        val created = addPerson(Person(name = Some(normalized)))
        logger.debug(s"Created person [${created.persistedId}] named [$normalized] for a moved face")
        created.persistedId

  /**
   * What becomes of a Person once `face`, a Face of theirs on an asset outside the trash, has left (deleted, or given away): the
   * asset leaves their count if no other Face of theirs is on it, the cover goes to `successor`, or to their remaining Face with
   * the highest detection score on an asset not marked for purging (none when there is no such Face), if it was this Face, and
   * they are deleted if no Face is left. The Person as they are after, or as they were when deleted, with whether they were.
   */
  private def settleAfterFaceLeft(face: Face, person: Person, successor: Option[Face] = None): (Person, Boolean) =
    val personId = person.persistedId
    if !faceDao.hasFaceOfPerson(face.assetId.get, personId) then increment(personId, FieldConst.Person.NUM_OF_FACES, -1)

    if person.coverFaceId.contains(face.persistedId) then
      val cover = successor.orElse(faceDao.getBestCoverCandidate(personId, Set.empty))
      updateById(personId, Map(FieldConst.Person.COVER_FACE_ID -> cover.map(_.persistedId)))

    val deleted = deletePeopleWithoutFaces(Set(personId)).nonEmpty
    (if deleted then person else dao.getById(personId), deleted)

  /** The people the faces belong to; to be called in the transaction the faces were read in */
  private def peopleOf(faces: List[Face]): List[Person] = peopleByIds(faces.map(_.personId.get).toSet)

  /** The people of the IDs, in no order */
  private def peopleByIds(personIds: Set[String]): List[Person] =
    if personIds.isEmpty then List()
    else
      val q = new Query(params = Map(FieldConst.ID -> Query.IN(personIds.toSet[Any])))
      val qRes: QueryResult[Person] = dao.query(q)
      qRes.records

  /**
   * Makes the Face the Person's cover, and answers the Person as they are after. Under the Person's lock, so a purge moving
   * covers off the Face's asset waits or is waited for ([[moveCoversOff]]): the Person must be of the context repository, and the
   * Face too (`NotFoundException`), the Face the Person's own and on an asset not marked for purging (`ValidationException`),
   * since a cover never rests on a Face about to go.
   */
  def setFaceAsCover(personId: String, faceId: String): Person =
    txManager.withTransaction {
      dao.lockAndGetLive(Set(personId)): Unit
      dao.getById(personId): Unit

      val stored = faceDao.getById(faceId)
      if !stored.personId.contains(personId) then
        logger.debug(s"Refusing face [$faceId] of person [${stored.personId.get}] as the cover of person [$personId]")
        throw ValidationException(C.Msg.Err.COVER_FACE_NOT_THEIRS)

      if isOnAssetMarkedForPurging(stored) then
        logger.debug(s"Refusing face [$faceId] as a cover: its asset [${stored.assetId.get}] is marked for purging")
        throw ValidationException(C.Msg.Err.COVER_FACE_PURGING)

      logger.debug(s"Setting the cover of person [$personId] to face [$faceId]")
      updateById(personId, Map(FieldConst.Person.COVER_FACE_ID -> faceId))
      dao.getById(personId)
    }

  /** Whether the Face's asset is marked for purging: a cover never rests on a Face about to go */
  private def isOnAssetMarkedForPurging(face: Face): Boolean =
    val purging = new Query().add(FieldConst.ID -> face.assetId.get).add(FieldConst.Asset.IS_PURGED -> true)
    app.service.asset.queryAll(purging).nonEmpty

  /**
   * Names the person, the name stored in NFC and its name key as sort name. A name the name rule refuses ([[validName]]) is a
   * `ValidationException`; a name another live person holds in any case or Unicode form, a `DuplicateException` (`person_01`).
   */
  def updateName(person: Person, newName: String): Person =
    val name = validName(newName)

    txManager.withTransaction {
      logger.debug(s"Renaming person [${person.persistedId}] to [$name]")
      updateById(
        person.persistedId,
        Map(
          FieldConst.Person.NAME -> name,
          FieldConst.Person.NAME_FOR_SORT -> PersonService.nameKey(name),
          FieldConst.Person.IS_NAMED -> true
        ))

      person.copy(name = Some(name), isNamed = true)
    }

  /**
   * A person's name as stored, once it passes the name rule: 2 to 80 characters once in NFC, with a name key that is not too long
   * (`Validators.validName`), and not of an unnamed person's form ([[PersonService.isReservedName]]). Otherwise a
   * `ValidationException` saying why.
   */
  private def validName(name: String): String =
    val (min, max) = (Api.Constraints.MIN_NAME_LENGTH, Api.Constraints.MAX_NAME_LENGTH)
    val normalized = Validators.validName(name, Api.Field.Person.NAME, maxLength = max, minLength = min)

    if isReservedName(normalized) then
      logger.debug(s"Refusing the reserved name [$normalized]")
      throw ValidationException(C.Msg.Err.RESERVED_PERSON_NAME)
    normalized

  /**
   * The live person holding a name in any case or Unicode form: the one `person_01` refuses the name for. A hidden person is
   * live; a merged-away or bad-match one is not, and holds no name.
   */
  def getLiveByNameKey(name: String): Option[Person] =
    txManager.asReadOnly {
      dao.getLiveByNameKey(PersonService.nameKey(name))
    }

  def setVisibility(person: Person, isHidden: Boolean): Person =
    txManager.withTransaction {
      logger.debug(s"Setting person [${person.persistedId}] hidden to [$isHidden]")
      updateById(person.persistedId, Map(FieldConst.Person.IS_HIDDEN -> isHidden))
      person.copy(isHidden = isHidden)
    }

  def markAsBadMatch(person: Person): Person =
    txManager.withTransaction {
      logger.debug(s"Marking person [${person.persistedId}] as a bad match")
      updateById(person.persistedId, Map(FieldConst.Person.IS_BAD_MATCH -> true))
      person.copy(isBadMatch = true)
    }

  def getAll: List[Person] =
    txManager.asReadOnly {
      dao.getAll.values.toList
    }

  /** The people in any of the given People buckets (`Const.PeopleBucket`); see `PersonDao.getInBuckets` */
  def getInBuckets(buckets: Set[String]): List[Person] =
    txManager.asReadOnly {
      dao.getInBuckets(buckets)
    }

  /**
   * The people of `getInBuckets`, in its order, each with the count the People tab shows: `numOfFaces`, less the Person's triaged
   * assets when triaged items are hidden, read in one snapshot. A Person with nothing left to find is left off, and the buckets
   * stay those of the whole count, so the switch moves no one between them. The difference can go below zero while assets are
   * still being imported: a merge recounts through a search, which skips them, and the triaged count holds them.
   *
   * `leftOffByTriage` is whether the list came out empty with someone in the buckets: `getInBuckets` reads only people with a
   * count above zero, so whoever is dropped here is dropped by the subtraction.
   */
  def getListedInBuckets(buckets: Set[String], showTriaged: Boolean): ListedPeople =
    txManager.asReadOnly {
      val people = dao.getInBuckets(buckets)
      val triagedCounts = if showTriaged then Map.empty[String, Int] else dao.getTriagedAssetCounts
      val listed = people
        .map(person => ListedPerson(person, person.numOfFaces - triagedCounts.getOrElse(person.persistedId, 0)))
        .filter(_.visibleCount > 0)
      ListedPeople(listed, leftOffByTriage = people.nonEmpty && listed.isEmpty)
    }

  /**
   * The bucket names of a comma-separated list, trimmed, empties dropped and nothing validated: `getInBuckets` refuses an unknown
   * one
   */
  def parseBuckets(csv: String): Set[String] =
    csv.split(",").map(_.trim).filter(_.nonEmpty).toSet
