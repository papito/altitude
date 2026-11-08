package altitude.core.service

import java.sql.SQLException
import java.text.Normalizer
import java.util.Locale

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
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

class PersonService(val app: Altitude) extends BaseService[Person]:
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
   * Deletes those of the people who have no face left, and gives back their IDs. Recognition matches face rows, so only an import
   * that started a person can leave it without any.
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
