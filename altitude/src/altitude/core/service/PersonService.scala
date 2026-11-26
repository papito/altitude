package altitude.core.service

import java.sql.SQLException
import java.text.Normalizer

import scala.annotation.tailrec

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
import altitude.core.models.CountedAssets
import altitude.core.models.Face
import altitude.core.models.ListedPeople
import altitude.core.models.ListedPerson
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager
import altitude.core.util.NameKey
import altitude.core.util.Query
import altitude.core.util.SearchQuery
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

object PersonService:
  val UNKNOWN_NAME_PREFIX = "Unknown"

  // An unnamed person's form of name ("Unknown 42") in any case, Unicode spaces and digits included. The pattern holds the
  // surrounding spaces itself, as `String.strip` leaves no-break spaces (U+00A0, U+2007, U+202F)
  private val RESERVED_NAME = s"(?iU)\\s*$UNKNOWN_NAME_PREFIX\\s*\\d+\\s*".r

  /** A name as stored: NFC, so a name typed with a combining accent and the same name typed precomposed are one name */
  def normalizedName(name: String): String = NameKey.normalized(name)

  /** The folded name that `person_01` keeps unique, stored as a named person's `name_for_sort`: "Weiß", "WEISS" are one name */
  def nameKey(name: String): String = NameKey.of(name)

  /**
   * Whether a name has an unnamed person's form, which no person may take, as their sort name (`unknown 0000000042`) would
   * collide with a named person's key. Matched in NFKC, as the key folds it, so a full-width "Ｕnknown 42" is reserved too.
   */
  def isReservedName(name: String): Boolean = RESERVED_NAME.matches(Normalizer.normalize(name, Normalizer.Form.NFKC))

  /** The most people the Change combobox's Suggested group holds */
  val SUGGESTED_PEOPLE = 3

  /** The nearest Faces read to find them: more than the people wanted, as one Person's Faces come several deep */
  private val SUGGESTION_CANDIDATES = 20

  /** A live Person by ID, or a name, which may create the Person */
  enum FaceTarget:
    case ExistingPerson(personId: String)
    case Named(name: String)

  /** The Change combobox options: the Suggested group, closest first, then the rest assignable, neither with the Face's Person */
  case class AssignablePeople(suggested: List[Person], others: List[Person])

  /** The removed Face, and its Person after the removal, or as they were when the Face was their last and they were deleted */
  case class FaceRemoval(face: Face, person: Person, personDeleted: Boolean)

  /** The moved Face, its former Person after the move (or as they were, when deleted) and the Person it was given to, after */
  case class FaceMove(face: Face, source: Person, target: Person, sourceDeleted: Boolean)

  /** The Face, its Person before the change, and the Person holding it after: the same Person named, or the merge destination */
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

  /** The Person when live (neither merged away nor a bad match; hidden or not) in the context repository, else not found */
  def getLiveInRepository(personId: String): Person =
    val live = new Query()
      .add(FieldConst.ID -> personId)
      .add(FieldConst.Person.IS_DELETED -> false)
      .add(FieldConst.Person.IS_BAD_MATCH -> false)
    query(live).records.headOption.getOrElse {
      throw NotFoundException(s"Live person [$personId] not found")
    }

  /** The live people among the IDs, as [[getLiveInRepository]] finds them, in no order; other IDs have no entry */
  def getLive(personIds: Set[String]): List[Person] =
    txManager.asReadOnly {
      peopleByIds(personIds, FieldConst.Person.IS_DELETED -> false, FieldConst.Person.IS_BAD_MATCH -> false)
    }

  /**
   * Stores the Face, makes it the cover of a person with none, and counts the asset toward `numOfFaces` on the person's first
   * Face on it: the number counts assets, so a second Face of theirs on the same asset adds nothing
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
   * Moves the source's Faces to the destination, soft-deletes the source and recounts the destination. Both people are locked and
   * re-read, as the models passed in may be stale. An unnamed destination takes the name of a named source that is still live.
   * The source is soft-deleted before the destination takes its name or cover, so `person_01` and `person_02` never see either on
   * two live people. On a Video where both had a Face, only the better one stays ([[keepOneFacePerVideo]]). A destination with no
   * cover takes the source's, or their best Face.
   */
  def merge(dest: Person, source: Person): Person =
    if source == dest then throw IllegalArgumentException("Cannot merge a person with itself. That's perverse!")

    val (merged, lost) = txManager.withTransaction {
      mergeLocked(dest, source)
    }
    purgeFaceFiles(lost)
    merged

  /**
   * [[merge]] in the caller's transaction; also returns the deleted Faces, whose files go once it commits. A destination no
   * longer live (merged away, a bad match) is not found, as a stale view may still offer it: its Faces would be nobody's to see.
   */
  private def mergeLocked(dest: Person, source: Person): (Person, List[Face]) =
    val liveIds: Set[String] = dao.lockAndGetLive(Set(dest.persistedId, source.persistedId))
    if !liveIds.contains(dest.persistedId) then throw NotFoundException(s"Person [${dest.persistedId}] is not live")
    val persistedDest: Person = dao.getById(dest.persistedId)
    val persistedSource: Person = dao.getById(source.persistedId)

    logger.debug(
      s"Merging person [${persistedSource.persistedId}] ${persistedSource.name.get} " +
        s"into [${persistedDest.persistedId}] ${persistedDest.name.get}")

    // Read before the Faces move, while each still says whose it is: a tie goes to the destination's
    val sharedVideoFaces = faceDao.getFacesOnSharedVideos(persistedDest.persistedId, persistedSource.persistedId)

    val query = new Query().add(FieldConst.Face.PERSON_ID -> persistedSource.persistedId)

    faceDao.updateByQuery(query, Map(FieldConst.Face.PERSON_ID -> persistedDest.persistedId))

    updateById(
      persistedSource.persistedId,
      Map(
        FieldConst.Person.NUM_OF_FACES -> 0,
        FieldConst.Person.IS_DELETED -> true
      )
    )

    // After the soft delete: the destination may take the source's cover, which `person_02` allows one live person at a time
    val lostFaces = keepOneFacePerVideo(sharedVideoFaces, persistedDest, persistedSource)

    // A source no longer live holds no name: another live person may hold it by now
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
   * On each Video where both people had a Face, keeps the higher-quality one (the destination's on a tie) and deletes the rest; a
   * cover on a deleted Face moves to the survivor. Returns the deleted Faces.
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
   * Each person with a Face on the assets loses them from `numOfFaces`, which counts assets outside the trash; the Faces go at
   * purge, and a restore reverses this. The people are locked after the caller's asset locks, so a correction in flight
   * ([[removeFace]], [[moveFace]]) ends first: read from a snapshot where its Face still existed, the count would drop twice.
   */
  def recycleFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Recycling the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
      lockAssetPeople(assetIds): Unit
      dao.recycleFacesForAssets(assetIds)
    }

  def restoreFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Restoring the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
      lockAssetPeople(assetIds): Unit
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

  /** Every Face of an asset with its person (hidden and bad-match ones too), in one snapshot so a merge cannot come between */
  def getAssetFacesWithPeople(assetId: String): List[(Face, Person)] =
    txManager.asReadOnly {
      val faces = faceDao.getAllAssetFaces(assetId)
      val peopleById = peopleOf(faces).map(person => person.persistedId -> person).toMap
      faces.map(face => face -> peopleById(face.personId.get))
    }

  /**
   * Deletes the people who have no Face left, named or not, and returns their IDs: a discarded import's people it started, and a
   * correction's Person whose last Face it took ([[deleteFaceless]])
   */
  def deletePeopleWithoutFaces(personIds: Set[String]): Set[String] = deleteFaceless(personIds, keepNamed = false)

  /**
   * Deletes the unnamed people who have no Face left and returns their IDs: a purge's, which leaves a named person their row and
   * name with no Face, still someone a Face can be given to ([[deleteFaceless]])
   */
  def deleteUnnamedPeopleWithoutFaces(personIds: Set[String]): Set[String] = deleteFaceless(personIds, keepNamed = true)

  /**
   * Deletes the faceless among the people, or the unnamed faceless with `keepNamed`. They are locked first (in ID order, as every
   * path locking people does), so a Face given to one of them meanwhile either has committed, and they keep it, or waits and then
   * finds them gone.
   */
  private def deleteFaceless(personIds: Set[String], keepNamed: Boolean): Set[String] =
    txManager.withTransaction {
      lockLive(personIds): Unit
      val deleted = dao.deleteFaceless(personIds, keepNamed)
      if deleted.nonEmpty then logger.debug(s"Deleted people left without faces [${deleted.mkString(",")}]")
      deleted
    }

  /**
   * Locks the people in ID order, as every transaction locking more than one does, and returns the live ones (neither merged away
   * nor a bad match). The dead are locked too, so the caller waits for whatever is changing them.
   */
  def lockLive(personIds: Set[String]): Set[String] =
    txManager.withTransaction(dao.lockAndGetLive(personIds))

  /**
   * Locks the people with a Face on the assets, in ID order, and returns their IDs. They are read again once locked: while a Face
   * of the assets has changed hands meanwhile (a merge gave it to the destination), the locks are given back and taken again over
   * whoever has the Faces now, so the caller holds every one of them, all taken in ID order, before it touches their Faces.
   */
  def lockAssetPeople(assetIds: Set[String]): Set[String] =
    txManager.withTransaction {
      @tailrec
      def locked(): Set[String] =
        val (held, now) = txManager.undoingIf {
          val held = dao.lockAssetPeople(assetIds)
          (held, dao.getAssetPeople(assetIds))
        } { case (held, now) => !now.subsetOf(held) }

        if now.subsetOf(held) then held
        else
          logger.debug(s"Faces of assets [${assetIds.mkString(",")}] changed hands while their people were locked; locking again")
          locked()

      locked()
    }

  /**
   * Deletes the files of Faces whose rows are gone or going with their asset. Every path that deletes a Face moves any cover off
   * it first ([[settleAfterFaceLeft]], [[moveCoversOff]]).
   */
  def purgeFaceFiles(faces: List[Face]): Unit =
    faces.foreach {
      face =>
        logger.trace(s"Removing the files of face [${face.persistedId}]")
        app.service.fileStore.purgeFaceById(face.persistedId)
    }

  /**
   * Moves every cover off the Faces of assets about to go (a purge, a discarded import); run before their rows go. Each cover
   * holder, live or not, takes their best Face on another asset not marked for purging, or none. The people are locked first, so
   * a concurrent import, correction or cover change waits; lock order is assets, then people, in every transaction locking both.
   */
  def moveCoversOff(assetIds: Set[String]): Unit =
    if assetIds.isEmpty then return

    txManager.withTransaction {
      lockAssetPeople(assetIds): Unit

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

  /** Every Face of an asset, hidden and bad-match people's too */
  def getAllAssetFaces(assetId: String): List[Face] =
    txManager.asReadOnly {
      faceDao.getAllAssetFaces(assetId)
    }

  /** The Faces of an asset with their people, bad matches left out: left to right on an image, by Frame time on a Video */
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
   * The first `SUGGESTED_PEOPLE` distinct people whose Faces are nearest the Face's vector, closest first. The candidates are
   * those recognition sees, less the Face's own Person, whom the SQL excludes so a Person with many Faces cannot fill every
   * candidate. Nothing is stored and nothing votes, so it is a read.
   */
  def suggestedPeople(faceId: String): List[Person] =
    txManager.withFaceVectorReadOnly {
      suggestedFor(faceDao.getById(faceId))
    }

  /** [[suggestedPeople]] for a Face read in the caller's transaction, which is set up for searching face vectors */
  private def suggestedFor(face: Face): List[Person] =
    // Recognition's Setting, read per call
    val distanceThreshold = app.settings.current.faceRecognitionCosineDistanceThreshold
    val faceId = face.persistedId

    val features = faceDao.getFeatures(faceId).getOrElse(throw NotFoundException(s"Face [$faceId] not found"))
    val nearest = faceDao.searchNearestFacesOfOthers(features, face.personId.get, SUGGESTION_CANDIDATES, distanceThreshold)
    val personIds = nearest.map(_.personId.get).distinct.take(SUGGESTED_PEOPLE)
    val people = peopleByIds(personIds.toSet).map(person => person.persistedId -> person).toMap
    logger.trace(s"Face [$faceId]: ${personIds.size} suggested people among ${nearest.size} nearest Faces")
    personIds.map(people)

  /**
   * The Change combobox options in one snapshot: the Face's Suggested group, then the assignable people less those and the Face's
   * own Person; without a Face, the assignable people alone
   */
  def assignablePeople(faceId: Option[String]): AssignablePeople =
    txManager.withFaceVectorReadOnly {
      val face = faceId.map(faceDao.getById)
      val suggested = face.fold(List.empty[Person])(suggestedFor)
      val leftOut = suggested.map(_.persistedId).toSet ++ face.map(_.personId.get)
      AssignablePeople(suggested, getAssignable.filterNot(person => leftOut.contains(person.persistedId)))
    }

  /**
   * Removes a Face of an asset outside the trash and settles its Person ([[settleAfterFaceLeft]]). Under the Person's lock, so a
   * concurrent correction of the same Face waits and then finds it gone. The Face's files go once committed.
   */
  def removeFace(faceId: String): FaceRemoval =
    val removal = txManager.withTransaction {
      // Read unlocked, to learn whom to lock
      val personId = faceDao.getById(faceId).personId.get
      dao.lockAndGetLive(Set(personId)): Unit
      val (face, _) = lockedFace(faceId, personId)
      // Read before the delete, which sets a cover on this Face to NULL: settling must see it was the cover
      val before = dao.getById(personId)

      faceDao.deleteById(face.persistedId)
      val (person, deleted) = settleAfterFaceLeft(face, before)
      logger.debug(s"Removed face [$faceId] of person [$personId] from asset [${face.assetId.get}]; person deleted: $deleted")
      FaceRemoval(face, person, deleted)
    }

    purgeFaceFiles(List(removal.face))
    removal

  /**
   * Gives a Face of an asset outside the trash to another live Person, by ID or by name (the name's live holder, or a new named
   * Person). The vector moves with the row, so later imports match the new Person. Both people are locked after the name's lock
   * and the Face re-read. The target counts the asset and takes the Face as cover if they had none; the source is settled
   * ([[settleAfterFaceLeft]]). On a Video where the target already has a Face, the lower-quality one (the target's on a tie) is
   * deleted, keeping one Face per Person per Video.
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

      // The target's Face on the Video, if any: the move keeps only one of the two
      val rival: Option[Face] =
        if asset.assetType.mediaType != "video" then None
        else faceDao.getAllAssetFaces(assetId).filter(_.personId.contains(targetId)).sortBy(-_.quality).headOption

      rival match
        case Some(kept) if kept.quality >= face.quality =>
          // The target's own Face is the better one: the moved Face is deleted instead
          logger.debug(s"Face [$faceId] loses to face [${kept.persistedId}] of person [$targetId] in video [$assetId]; deleted")
          faceDao.deleteById(face.persistedId)
          val (sourceAfter, sourceDeleted) = settleAfterFaceLeft(face, source)
          (FaceMove(face, sourceAfter, dao.getById(targetId), sourceDeleted), Some(face))
        case _ =>
          val targetHadAsset = rival.isDefined || faceDao.hasFaceOfPerson(assetId, targetId)
          faceDao.updateById(face.persistedId, Map(FieldConst.Face.PERSON_ID -> targetId))

          // The source first: their cover moves off this Face before the target may take it, as `person_02` allows a cover one
          // live person at a time
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
   * Names a nameless Face's Person as a whole ([[updateName]]). `personId` is the Person the client showed, checked against the
   * Face rather than read from it, so the name never lands on someone the Face was given to since.
   */
  def nameFacePerson(faceId: String, personId: String, name: String): FacePersonChange =
    txManager.withTransaction {
      // The name's lock first, as a move to a name takes it: a move creating this name meanwhile waits, then finds this Person
      // holding it rather than failing on `person_01`
      dao.lockName(nameKey(name))
      val (face, person) = lockedNamelessFacePerson(faceId, personId, Set.empty)
      val named = updateName(person, name)
      logger.debug(s"Person [$personId] of face [$faceId] named [${named.name.get}], formerly [${person.name.get}]")
      FacePersonChange(face, person, named)
    }

  /**
   * Merges a nameless Face's Person as a whole into the named Person `destId` ([[merge]]), whose ID, name and Hidden state stay.
   * `personId` is checked against the Face as in [[nameFacePerson]].
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
   * Locks the Person and `others`, all of whom must be live, and re-reads the Face under the lock. A Person named since is an
   * `IllegalOperationException`, as the client's view is stale.
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

  /** The Face re-read under its Person's lock, with its asset: not found if gone or given away, refused if in the trash */
  private def lockedFace(faceId: String, personId: String): (Face, Asset) =
    val face = faceDao.getById(faceId)
    if !face.personId.contains(personId) then throw NotFoundException(s"Face [$faceId] of person [$personId] not found")
    val asset: Asset = app.service.asset.getById(face.assetId.get)
    if asset.isRecycled then throw IllegalOperationException(C.Msg.Err.FACE_OF_RECYCLED_ASSET)
    (face, asset)

  /**
   * The live Person holding the name, or a new Person with it. The name's lock makes concurrent creators of one name take turns,
   * so the second finds the first's Person.
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
   * Settles a Person after `face` left them (deleted or given away): the asset leaves their count if no Face of theirs remains on
   * it, a cover on `face` goes to `successor` or their best cover candidate, and they are deleted if faceless. Returns the Person
   * after (or as they were, when deleted) and whether they were deleted.
   */
  private def settleAfterFaceLeft(face: Face, person: Person, successor: Option[Face] = None): (Person, Boolean) =
    val personId = person.persistedId
    if !faceDao.hasFaceOfPerson(face.assetId.get, personId) then increment(personId, FieldConst.Person.NUM_OF_FACES, -1)

    if person.coverFaceId.contains(face.persistedId) then
      val cover = successor.orElse(faceDao.getBestCoverCandidate(personId, Set.empty))
      updateById(personId, Map(FieldConst.Person.COVER_FACE_ID -> cover.map(_.persistedId)))

    val deleted = deletePeopleWithoutFaces(Set(personId)).nonEmpty
    (if deleted then person else dao.getById(personId), deleted)

  /** The people the faces belong to; call it in the transaction the faces were read in */
  private def peopleOf(faces: List[Face]): List[Person] = peopleByIds(faces.map(_.personId.get).toSet)

  /** The people of the IDs who meet every one of `conditions` (a column and its value), in no order */
  private def peopleByIds(personIds: Set[String], conditions: (String, Any)*): List[Person] =
    if personIds.isEmpty then Nil
    else dao.query(new Query(params = Map(FieldConst.ID -> Query.IN(personIds.toSet[Any]))).add(conditions*)).records

  /**
   * Makes the Face, which must be the Person's own and not on an asset marked for purging, the Person's cover. Under the Person's
   * lock, so a purge moving covers off the Face's asset ([[moveCoversOff]]) cannot interleave.
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
   * Names the person, stored in NFC with its name key as sort name. A name another live person holds in any case or Unicode form
   * is a `DuplicateException` (`person_01`). The name's lock comes first, as a move of a Face to a name takes it, so such a move
   * creating a Person of the name meanwhile waits, then finds this one holding it, instead of failing on `person_01`.
   */
  def updateName(person: Person, newName: String): Person =
    val name = validName(newName)

    txManager.withTransaction {
      dao.lockName(nameKey(name))
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
   * The name as stored, if it passes the name rule: 2 to 80 characters in NFC, a name key not too long, and not reserved
   * ([[PersonService.isReservedName]]); otherwise a `ValidationException`
   */
  private def validName(name: String): String =
    val (min, max) = (Api.Constraints.MIN_NAME_LENGTH, Api.Constraints.MAX_NAME_LENGTH)
    val normalized = Validators.validName(name, Api.Field.Person.NAME, maxLength = max, minLength = min)

    if isReservedName(normalized) then
      logger.debug(s"Refusing the reserved name [$normalized]")
      throw ValidationException(C.Msg.Err.RESERVED_PERSON_NAME)
    normalized

  /** The live person (hidden or not) holding a name in any case or Unicode form: the one `person_01` refuses the name for */
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

  /** The people in any of the given People buckets (`Const.PeopleBucket`) */
  def getInBuckets(buckets: Set[String]): List[Person] =
    txManager.asReadOnly {
      dao.getInBuckets(buckets)
    }

  /**
   * The people of `getInBuckets`, each with the count the People tab shows, read in one snapshot: `numOfFaces`, less their
   * triaged assets when those are hidden. A Person with nothing left is left off; buckets stay those of the whole count, so the
   * switch moves no one between them. The difference can go below zero mid-import: a merge recounts through a search, which skips
   * importing assets, while the triaged count holds them. `getInBuckets` reads only counts above zero, so `leftOffByTriage` (an
   * empty list from a non-empty read) means the subtraction dropped everyone.
   */
  def getListedInBuckets(buckets: Set[String], showTriaged: Boolean): ListedPeople =
    txManager.asReadOnly {
      val people = dao.getInBuckets(buckets)
      val listed = listedOf(people, if showTriaged then CountedAssets.All else CountedAssets.Sorted)
      ListedPeople(listed, leftOffByTriage = people.nonEmpty && listed.isEmpty)
    }

  /**
   * The people a Search text finds by name (as `SearchQueries.searchablePeople`), in no order, each with the number of `counted`
   * assets a search of them finds, read in one snapshot; those with none are left off
   */
  def getListedSearchable(counted: CountedAssets): List[ListedPerson] =
    txManager.asReadOnly {
      val searchable = new Query().add(
        FieldConst.Person.IS_NAMED -> true,
        FieldConst.Person.IS_HIDDEN -> false,
        FieldConst.Person.IS_DELETED -> false,
        FieldConst.Person.IS_BAD_MATCH -> false)
      listedOf(dao.query(searchable).records, counted)
    }

  /** The people, in their order, each with the number of `counted` assets opening them finds; those with none are left off */
  private def listedOf(people: List[Person], counted: CountedAssets): List[ListedPerson] =
    val triagedCounts = if counted == CountedAssets.All then Map.empty[String, Int] else dao.getTriagedAssetCounts
    def triagedOf(person: Person): Int = triagedCounts.getOrElse(person.persistedId, 0)
    people
      .map {
        person =>
          val count = counted match
            case CountedAssets.All => person.numOfFaces
            case CountedAssets.Sorted => person.numOfFaces - triagedOf(person)
            case CountedAssets.Triaged => triagedOf(person)
          ListedPerson(person, count)
      }
      .filter(_.visibleCount > 0)

  /** The bucket names of a comma-separated list, unvalidated: `getInBuckets` refuses an unknown one */
  def parseBuckets(csv: String): Set[String] =
    csv.split(",").map(_.trim).filter(_.nonEmpty).toSet
