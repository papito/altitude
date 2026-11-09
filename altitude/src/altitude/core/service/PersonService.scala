package altitude.core.service

import java.sql.SQLException
import java.text.Normalizer
import java.util.Locale

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.dao.FaceDao
import altitude.core.dao.PersonDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.transactions.TransactionManager
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
  def normalizedName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

  /**
   * The name key: the folded form of a name that `person_01` keeps unique, stored as a named person's `name_for_sort`. The fold
   * is done here and not in SQL, since SQLite's `lower()` folds ASCII only while PostgreSQL's folds by its collation.
   * `Locale.ROOT` keeps "I" folding to "i" whatever the JVM's default locale. This is lowercasing and not a full Unicode case
   * fold, so "Weiß" and "WEISS", or a Greek name with a final sigma and the same name with a plain one ("σοφος" and "σοφοσ"),
   * keep different keys.
   */
  def nameKey(name: String): String = normalizedName(name).toLowerCase(Locale.ROOT)

  /**
   * Whether a name has an unnamed person's form, which no person may be given: an unnamed person's sort name (`unknown 0042`)
   * would otherwise equal a named person's key, or sit beside it under another
   */
  def isReservedName(name: String): Boolean = RESERVED_NAME.matches(name)

  /** Whom a Face is given to by [[PersonService.moveFace]]: a live Person by ID, or by name, which may create the Person */
  enum FaceTarget:
    case ExistingPerson(personId: String)
    case Named(name: String)

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

      if person.numOfFaces == 0 then setFaceAsCover(person, persistedFace.get)

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
   * source is soft-deleted before the destination takes its name, so `person_01` never sees the name on two live people.
   */
  def merge(dest: Person, source: Person): Person =
    if source == dest then throw IllegalArgumentException("Cannot merge a person with itself. That's perverse!")

    txManager.withTransaction {
      val liveIds: Set[String] = dao.lockAndGetLive(Set(dest.persistedId, source.persistedId))
      val persistedDest: Person = dao.getById(dest.persistedId)
      val persistedSource: Person = dao.getById(source.persistedId)

      logger.debug(
        s"Merging person [${persistedSource.persistedId}] ${persistedSource.name.get} " +
          s"into [${persistedDest.persistedId}] ${persistedDest.name.get}")
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

      updateById(persistedDest.persistedId, Map(FieldConst.Person.NUM_OF_FACES -> mergedFaceCount) ++ nameFields)

      persistedDest.copy(
        numOfFaces = mergedFaceCount,
        name = takenName.orElse(persistedDest.name),
        isNamed = persistedDest.isNamed || takenName.isDefined
      )
    }

  def getPersonFaces(personId: String, limit: Int = 50): List[Face] =
    txManager.asReadOnly {
      faceDao.getTopFaces(personId, limit)
    }

  /**
   * When an asset gets recycled, each person with a Face on it loses that asset from `numOfFaces`, however many Faces of theirs
   * it holds: the number counts the assets a person is found on outside the trash.
   *
   * We don't do anything else, as we remove the actual faces during asset Purge.
   *
   * If an asset is restored, we just do the reverse of this and everyone is happy.
   */
  def recycleFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Recycling the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
      dao.recycleFacesForAssets(assetIds)
    }

  def restoreFacesForAssets(assetIds: Set[String]): Unit =
    logger.trace(s"Restoring the faces of assets [${assetIds.mkString(",")}]")

    txManager.withTransaction {
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
   * a merge cannot come between the two: what deleting the asset's faces takes with it
   */
  def getAssetFacesWithPeople(assetId: String): List[(Face, Person)] =
    txManager.asReadOnly {
      val faces = faceDao.getAllAssetFaces(assetId)
      val peopleById = peopleOf(faces).map(person => person.persistedId -> person).toMap
      faces.map(face => face -> peopleById(face.personId.get))
    }

  /**
   * Locks the people of an asset's faces for the caller's transaction, so that a face another import gives one of them meanwhile
   * waits for it to end
   */
  def lockAssetPeople(assetId: String): Unit =
    txManager.withTransaction {
      dao.lockAssetPeople(assetId)
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
   * Deletes the files of faces whose rows are gone. The cover face of a person who stays keeps its files, which the People tab
   * still shows; the faces of a deleted person keep none.
   */
  def purgeFaceFiles(facesWithPeople: List[(Face, Person)], deletedPeople: Set[String] = Set.empty): Unit =
    facesWithPeople.foreach {
      case (face, person) =>
        if deletedPeople.contains(person.persistedId) || !person.coverFaceId.contains(face.persistedId) then
          logger.trace(s"Removing the files of face [${face.persistedId}]")
          app.service.fileStore.purgeFaceById(face.persistedId)
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
   * Removes a Face of an asset outside the trash: the row goes, and its Person loses the asset from `numOfFaces` if they have no
   * other Face on it, has their cover repicked (their remaining Face with the highest detection score) if it was this Face, and
   * is deleted if no Face is left. One transaction, under the Person's row lock, so a concurrent correction of the same Face
   * waits and then finds it gone (`NotFoundException`); a Face of a recycled asset is an `IllegalOperationException`. Once
   * committed, the Face's files go: `purgeFaceFiles` is given the Person as they are after the repick, since a cover Face keeps
   * its files, and this one is no longer the cover.
   */
  def removeFace(faceId: String): FaceRemoval =
    val removal = txManager.withTransaction {
      val personId = readFace(faceId).personId.get
      dao.lockAndGetLive(Set(personId)): Unit
      val (face, _) = lockedFace(faceId, personId)

      faceDao.deleteById(face.persistedId)
      val (person, deleted) = settleAfterFaceLeft(face, dao.getById(personId))
      logger.debug(s"Removed face [$faceId] of person [$personId] from asset [${face.assetId.get}]; person deleted: $deleted")
      FaceRemoval(face, person, deleted)
    }

    purgeFaceFiles(List(removal.face -> removal.person), deletedPeopleOf(removal.person, removal.personDeleted))
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
      val sourceId = readFace(faceId).personId.get
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
          (FaceMove(face, sourceAfter, dao.getById(targetId), sourceDeleted), Some(face -> sourceAfter))
        case _ =>
          val targetHadAsset = rival.isDefined || faceDao.hasFaceOfPerson(assetId, targetId)
          faceDao.updateById(face.persistedId, Map(FieldConst.Face.PERSON_ID -> targetId))

          // The source first: a cover of theirs that was this Face is repicked, or the source deleted, before the target may take
          // the Face as cover, which `person_02` allows one person at a time
          val (sourceAfter, sourceDeleted) = settleAfterFaceLeft(face, source)

          // The target's worse Face goes, the moved Face taking its place as cover if it was the cover
          val lost = rival.map {
            worse =>
              logger.debug(
                s"Face [${worse.persistedId}] of person [$targetId] loses to face [$faceId] in video [$assetId]; deleted")
              faceDao.deleteById(worse.persistedId)
              worse -> settleAfterFaceLeft(worse, targetBefore, successor = Some(face))._1
          }

          if !targetHadAsset then increment(targetId, FieldConst.Person.NUM_OF_FACES)
          if targetBefore.coverFaceId.isEmpty then updateById(targetId, Map(FieldConst.Person.COVER_FACE_ID -> face.persistedId))
          logger.debug(s"Moved face [$faceId] from person [$sourceId] to [$targetId]; source deleted: $sourceDeleted")
          (FaceMove(face, sourceAfter, dao.getById(targetId), sourceDeleted), lost)
    }

    // The deleted Face's files, once committed: the target's lost Face (its cover now the moved Face) or the moved Face itself,
    // with the source as settled, deleted or not
    loser.foreach {
      case (face, person) => purgeFaceFiles(List(face -> person), deletedPeopleOf(move.source, move.sourceDeleted))
    }
    move

  /** The Face as it is before anything is locked, to learn whom to lock; gone, it is not found */
  private def readFace(faceId: String): Face =
    faceDao.getInRepository(faceId).getOrElse(throw NotFoundException(s"Face [$faceId] not found"))

  /**
   * The Face re-read under its Person's lock, with its asset: gone, or given to another Person meanwhile, it is not found; on an
   * asset in the trash, it is left alone
   */
  private def lockedFace(faceId: String, personId: String): (Face, Asset) =
    val face = faceDao.getInRepository(faceId).filter(_.personId.contains(personId)).getOrElse {
      throw NotFoundException(s"Face [$faceId] of person [$personId] not found")
    }
    val asset: Asset = app.service.asset.getById(face.assetId.get)
    if asset.isRecycled then throw IllegalOperationException(C.Msg.Err.FACE_OF_RECYCLED_ASSET)
    (face, asset)

  /**
   * The live Person holding the name, or a Person created with it. The name's lock makes concurrent creators of one name take
   * turns, so the second finds the first's Person. A name of an unnamed person's form is refused as a rename of it would be.
   */
  private def personIdForName(name: String): String =
    val normalized = normalizedName(name)
    if isReservedName(normalized) then throw ValidationException(C.Msg.Err.RESERVED_PERSON_NAME)

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
   * the highest detection score, if it was this Face, and they are deleted if no Face is left. The Person as they are after, or
   * as they were when deleted, with whether they were.
   */
  private def settleAfterFaceLeft(face: Face, person: Person, successor: Option[Face] = None): (Person, Boolean) =
    val personId = person.persistedId
    if !faceDao.hasFaceOfPerson(face.assetId.get, personId) then increment(personId, FieldConst.Person.NUM_OF_FACES, -1)

    if person.coverFaceId.contains(face.persistedId) then
      successor.orElse(faceDao.getTopFaces(personId, 1).headOption).foreach {
        cover => updateById(personId, Map(FieldConst.Person.COVER_FACE_ID -> cover.persistedId))
      }

    val deleted = deletePeopleWithoutFaces(Set(personId)).nonEmpty
    (if deleted then person else dao.getById(personId), deleted)

  /** The deleted people for `purgeFaceFiles`: the one person when deleted, so that even their cover's files go */
  private def deletedPeopleOf(person: Person, deleted: Boolean): Set[String] =
    if deleted then Set(person.persistedId) else Set.empty

  /** The people the faces belong to; to be called in the transaction the faces were read in */
  private def peopleOf(faces: List[Face]): List[Person] =
    val personIds = faces.map(_.personId.get)

    if personIds.isEmpty then List()
    else
      val q = new Query(params = Map(FieldConst.ID -> Query.IN(personIds.toSet)))
      val qRes: QueryResult[Person] = dao.query(q)
      qRes.records

  def setFaceAsCover(person: Person, face: Face): Person =
    txManager.withTransaction {
      logger.debug(s"Setting the cover of person [${person.persistedId}] to face [${face.persistedId}]")
      val personForUpdate = person.copy(coverFaceId = Some(face.persistedId))

      updateById(person.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> face.persistedId))

      personForUpdate
    }

  /**
   * Names the person, the name stored in NFC and its name key as sort name. A name of an unnamed person's form is a
   * `ValidationException`; a name another live person holds in any case or Unicode form, a `DuplicateException` (`person_01`).
   */
  def updateName(person: Person, newName: String): Person =
    val name = PersonService.normalizedName(newName)

    if PersonService.isReservedName(name) then
      logger.debug(s"Refusing to rename person [${person.persistedId}] to the reserved name [$name]")
      throw ValidationException(C.Msg.Err.RESERVED_PERSON_NAME)

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
   * The bucket names of a comma-separated list, trimmed, empties dropped and nothing validated: `getInBuckets` refuses an unknown
   * one
   */
  def parseBuckets(csv: String): Set[String] =
    csv.split(",").map(_.trim).filter(_.nonEmpty).toSet
