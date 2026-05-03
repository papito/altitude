package altitude.core.service

import altitude.core
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
import altitude.core.util.Sort
import altitude.core.util.SortDirection
import altitude.core.util.Util.newDuplicateExceptionOrRethrow
import java.sql.SQLException

object PersonService {
  val UNKNOWN_NAME_PREFIX = "Unknown"
}

class PersonService(val app: Altitude) extends BaseService[Person] {
  protected val dao: PersonDao = app.DAO.person
  private val faceDao: FaceDao = app.DAO.face

  override protected val txManager: TransactionManager = app.txManager

  override def add(objIn: Person): Person = {
    throw new NotImplementedError("Use the alternate addPerson() method")
  }

  def getPersonById(personId: String): Person = {
    txManager.asReadOnly {
      dao.getById(personId)
    }
  }

  def getFaceById(faceId: String): Face = {
    txManager.asReadOnly {
      faceDao.getById(faceId)
    }
  }

  def addFace(face: Face, asset: Asset, person: Person): Face = {
    require(person.persistedId.nonEmpty, "Cannot add a face to an unsaved person object")
    require(asset.persistedId.nonEmpty, "Cannot add a face to an unsaved asset object")

    txManager.withFaceVector[Face] {
      var persistedFace: Option[Face] = None
      try {
        persistedFace = Some(faceDao.add(face, asset, person))
      } catch {
        case e: SQLException =>
          throw newDuplicateExceptionOrRethrow(
            e,
            Some(s"Face already exists for person ${person.persistedId} in asset ${asset.persistedId}"))
        case ex: Exception =>
          throw ex
      }

      if (person.numOfFaces == 0) {
        setFaceAsCover(person, persistedFace.get)
      }

      increment(person.persistedId, FieldConst.Person.NUM_OF_FACES)

      persistedFace.get
    }
  }

  def addPerson(person: Person): Person = {
    txManager.withTransaction {
      require(person.getFaces.size < 2, "Adding a new person with more than one face is currently not supported")

      val personForUpdate = person.copy(
        numOfFaces = person.getFaces.size
      )

      dao.add(personForUpdate)
    }
  }

  def merge(dest: Person, source: Person): Person = {
    if (source == dest) {
      throw new IllegalArgumentException("Cannot merge a person with itself. That's perverse!")
    }

    logger.info(s"Merging person ${source.name} into ${dest.name}")

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
      val mergedPersonName = if (!dest.isNamed && source.isNamed) {
        source.name.get
      } else {
        dest.name.get
      }

      /**
       * Note that this has to be done AFTER the source is updated as "merged", in order to avoid clawing with the unique name
       * constraint across non-merged people.
       *
       * Secondly, we DO NOT do a simple sum of the number of faces, as the source and destination may overlap in assets.
       * We recalculate using the same person-filtered search semantics as the main results set:
       * recycled assets excluded, triaged assets included.
       */

      val recountQuery = new SearchQuery(
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        personIds = Set(persistedDest.persistedId)
      )

      val mergedFaceCount = app.service.library.search(recountQuery).total

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
  }

  def getPersonFaces(personId: String, limit: Int = 50): List[Face] = {
    txManager.asReadOnly {
      val sort: Sort = Sort(FieldConst.Face.DETECTION_SCORE, SortDirection.DESC)

      val q = new Query(params = Map(FieldConst.Face.PERSON_ID -> personId), sort = List(sort))

      val qRes: QueryResult[Face] = faceDao.query(q)
      qRes.records.take(limit)
    }
  }

  /**
   * When an asset gets recycled, we need to decrement the number of faces for the person as the recycled assets do not count
   * toward person occurrences in the data set.
   *
   * We don't do anything else, as we remove the actual faces during asset Purge.
   *
   * If an asset is restored, we just do the reverse of this and everyone is happy.
   */
  def recycleFacesForAssets(assetIds: Set[String]): Unit = {
    dao.recycleFacesForAssets(assetIds)
  }

  def restoreFacesForAssets(assetIds: Set[String]): Unit = {
    dao.restoreFacesForAssets(assetIds)
  }

  def getAssetFaces(assetId: String): List[Face] = {
    txManager.asReadOnly {
      faceDao.getAssetFaces(assetId)
    }
  }

  def getPeopleForAsset(assetId: String): List[Person] = {
    txManager.asReadOnly {
      val faces = getAssetFaces(assetId)
      val personIds = faces.map(_.personId.get)

      if (personIds.isEmpty) {
        List()
      } else {
        val q = new Query(params = Map(FieldConst.ID -> Query.IN(personIds.toSet)))

        val qRes: QueryResult[Person] = dao.query(q)
        qRes.records
      }
    }
  }

  def setFaceAsCover(person: Person, face: Face): Person = {
    txManager.withTransaction {
      val personForUpdate = person.copy(coverFaceId = Some(face.persistedId))

      updateById(person.persistedId, Map(FieldConst.Person.COVER_FACE_ID -> face.persistedId))

      personForUpdate
    }
  }

  def updateName(person: Person, newName: String): Person = {
    txManager.withTransaction {
      updateById(
        person.persistedId,
        Map(
          FieldConst.Person.NAME -> newName,
          FieldConst.Person.NAME_FOR_SORT -> newName.toLowerCase(),
          FieldConst.Person.IS_NAMED -> true
        ))

      person.copy(name = Some(newName), isNamed = true)
    }
  }

  def setVisibility(person: Person, isHidden: Boolean): Person = {
    txManager.withTransaction {
      updateById(person.persistedId, Map(FieldConst.Person.IS_HIDDEN -> isHidden))
      person.copy(isHidden = isHidden)
    }
  }

  def markAsBadMatch(person: Person): Person = {
    txManager.withTransaction {
      updateById(person.persistedId, Map(FieldConst.Person.IS_BAD_MATCH -> true))
      person.copy(isBadMatch = true)
    }
  }

  def getAll: List[Person] = {
    txManager.asReadOnly {
      dao.getAll.values.toList
    }
  }

  def getAllNotDiscarded: List[Person] = {
    txManager.asReadOnly {
      dao.getAllNotDiscarded.values.toList
    }
  }

  def getAllAboveThreshold: List[Person] = {
    txManager.asReadOnly {
      dao.getAllAboveThreshold
    }
  }

  def getAllBelowThreshold: List[Person] = {
    txManager.asReadOnly {
      dao.getAllBelowThreshold
    }
  }

  def getAllHidden: List[Person] = {
    txManager.asReadOnly {
      dao.getAllHidden
    }
  }
}
