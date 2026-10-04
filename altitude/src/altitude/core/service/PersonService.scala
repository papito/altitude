package altitude.core.service

import java.sql.SQLException

import altitude.core.Altitude
import altitude.core.FieldConst
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

  def addFace(face: Face, asset: Asset, person: Person): Face =
    require(person.persistedId.nonEmpty, "Cannot add a face to an unsaved person object")
    require(asset.persistedId.nonEmpty, "Cannot add a face to an unsaved asset object")

    txManager.withFaceVector[Face] {
      var persistedFace: Option[Face] = None
      try persistedFace = Some(faceDao.add(face, asset, person))
      catch
        case e: SQLException =>
          throw newDuplicateExceptionOrRethrow(
            e,
            Some(s"Face already exists for person ${person.persistedId} in asset ${asset.persistedId}"))

      if person.numOfFaces == 0 then setFaceAsCover(person, persistedFace.get)

      increment(person.persistedId, FieldConst.Person.NUM_OF_FACES)
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

  def merge(dest: Person, source: Person): Person =
    if source == dest then throw IllegalArgumentException("Cannot merge a person with itself. That's perverse!")

    logger.debug(s"Merging person ${source.name} into ${dest.name}")

    txManager.withTransaction {
      val persistedDest: Person = dao.getById(dest.persistedId)
      val persistedSource: Person = dao.getById(source.persistedId)

      logger.debug(s"Moving faces from ${source.name.get} to ${dest.name.get}")
      val query = new Query().add(FieldConst.Face.PERSON_ID -> source.persistedId)

      // faces from source are moved to the new person
      faceDao.updateByQuery(query, Map(FieldConst.Face.PERSON_ID -> dest.persistedId))

      updateById(
        persistedSource.persistedId,
        Map(
          FieldConst.Person.NUM_OF_FACES -> 0,
          FieldConst.Person.IS_DELETED -> true
        )
      )

      // if destination is NOT named and the source IS named, use the source name
      val mergedPersonName =
        if !dest.isNamed && source.isNamed then source.name.get
        else dest.name.get

      val recountQuery = new SearchQuery(
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        personIds = Set(persistedDest.persistedId)
      )

      val mergedFaceCount = app.service.library.count(recountQuery)

      val updatedDest = persistedDest.copy(
        numOfFaces = mergedFaceCount,
        name = Some(mergedPersonName)
      )

      updateById(
        persistedDest.persistedId,
        Map(
          FieldConst.Person.NUM_OF_FACES -> updatedDest.numOfFaces,
          FieldConst.Person.NAME -> mergedPersonName
        ))

      updatedDest
    }

  def getPersonFaces(personId: String, limit: Int = 50): List[Face] =
    txManager.asReadOnly {
      faceDao.getTopFaces(personId, limit)
    }

  /**
   * When an asset gets recycled, we need to decrement the number of faces for the person toward person occurrences in the data
   * set.
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

  def updateName(person: Person, newName: String): Person =
    txManager.withTransaction {
      logger.debug(s"Renaming person [${person.persistedId}] to [$newName]")
      updateById(
        person.persistedId,
        Map(
          FieldConst.Person.NAME -> newName,
          FieldConst.Person.NAME_FOR_SORT -> newName.toLowerCase,
          FieldConst.Person.IS_NAMED -> true
        ))

      person.copy(name = Some(newName), isNamed = true)
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

  def getAllNotDiscarded: List[Person] =
    txManager.asReadOnly {
      dao.getAllNotDiscarded.values.toList
    }

  def getAllAboveThreshold: List[Person] =
    txManager.asReadOnly {
      dao.getAllAboveThreshold
    }

  def getAllBelowThreshold: List[Person] =
    txManager.asReadOnly {
      dao.getAllBelowThreshold
    }

  def getAllHidden: List[Person] =
    txManager.asReadOnly {
      dao.getAllHidden
    }
