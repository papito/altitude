package software.altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.dao.FaceDao
import software.altitude.core.dao.PersonDao
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.models.Repository
import software.altitude.core.transactions.TransactionManager

class FaceCacheService(app: Altitude) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val personDao: PersonDao = app.DAO.person
  private val faceDao: FaceDao = app.DAO.face
  protected val txManager: TransactionManager = app.txManager

  private type Label = Int
  private type PersonCache = TrieMap[Label, Person]
  private type RepositoryPersonCache = TrieMap[String, PersonCache]

  // Model label (int) -> Person
  private def newPersonCache(): PersonCache = TrieMap[Label, Person]()

  // Repo ID -> Person Cache
  private val cache: RepositoryPersonCache = TrieMap[String, PersonCache]()

  private def getRepositoryPersonCache: PersonCache = {
    cache.getOrElseUpdate(RequestContext.getRepository.persistedId, newPersonCache())
  }

  def getPersonByLabel(label: Label): Option[Person] = {
    val personOpt = getRepositoryPersonCache.get(label)

    // if this person had been merged - return the merge destination instead
    if (personOpt.nonEmpty && personOpt.get.mergedIntoLabel.nonEmpty) {
      logger.info(s"Person ${personOpt.get.label} was merged into ${personOpt.get.mergedIntoLabel.get}")
      logger.info("Returning the merged person instead")
      return getRepositoryPersonCache.get(personOpt.get.mergedIntoLabel.get)
    }

    if (personOpt.isEmpty) {
      logger.info(s"Person with label $label not found in cache")
      None
    } else {
      logger.debug(s"Returning label $label from cache: ${personOpt.get.name}")
      personOpt
    }
  }

  def putPerson(person: Person): Unit = {
    // A person that was not merged cannot be empty.
    // Probably operator error not correctly constructing the person object.
    if (!person.hasFaces && !person.wasMergedFrom) {
      throw new IllegalArgumentException(s"Person ${person.label} has no faces attached to it")
    }

    getRepositoryPersonCache.put(person.label, person)
  }

  def addFace(face: Face): Person = {
    val person = getRepositoryPersonCache(face.personLabel.get)

    /**
     * Add the face and trim the number to top X faces - we don't need all of them. The faces are sorted by detection score
     * automatically, so we can just take the top X.
     */
    val faces = person.getFaces

    faces.add(face)
    person.setFaces(faces.take(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON).toSeq)

    // record the latest number of faces for this person
    val updatedPerson = person.copy(numOfFaces = person.numOfFaces + 1)
    updatedPerson.setFaces(person.getFaces.toSeq)
    putPerson(updatedPerson)
    updatedPerson
  }

  def removePerson(label: Label): Unit = {
    getRepositoryPersonCache.remove(label)
  }

  def size(): Int = getRepositoryPersonCache.keys.size

  /** This should only be used for debugging and testing */
  def getAll: List[Person] = getRepositoryPersonCache.values.toList

  /**
   * In "dirty" (post-startup-load) cache state, we need to filter out the people that have been merged from. These will be gone
   * on the next cache load as the model will be retrained and the old labels purged.
   *
   * This is the method that should always be used for face rec.
   */
  def getAllMatchable: List[Person] = getRepositoryPersonCache.values.toList.filterNot(_.wasMergedFrom)

  def clear(): Unit = getRepositoryPersonCache.clear()

  def loadCache(repository: Repository): Unit = {
    txManager.asReadOnly {
      logger.info(s"Loading faces for repository ${repository.name}")

      val allTopFaces: List[Face] = faceDao.getAllForCache
      val personLookup: Map[String, Person] = personDao.getAll

      var faceCount = 0
      allTopFaces.foreach {
        face: Face =>
          val person: Person = personLookup(face.personId.get)

          val alignedGreyscaleData = app.service.fileStore.getAlignedGreyscaleFaceById(face.persistedId)
          val faceWithImageData = face.copy(alignedImageGs = alignedGreyscaleData.data)
          person.addFace(faceWithImageData)
          faceCount += 1
      }

      // faces added, now cache the peeps whole
      personLookup.foreach {
        case (_, person) =>
          putPerson(person)
      }

      logger.info(s"Loaded ${personLookup.size} people into the cache, $faceCount faces.")
    }

  }

  def loadCacheForAll(): Unit = {
    app.service.library.forEachRepository(repository => loadCache(repository))
  }

  def dump(): Unit = {
    cache.foreach {
      case (repoId, personCache) =>
        println(s"Repository: $repoId\n")
        personCache.foreach {
          case (label, person) =>
            println(s"  ID: ${person.persistedId}")
            println(s"  Label: $label -> ${person.name.get}")
            println(s"  Merged with IDs: ${person.mergedWithIds}")
            println(s"  Merged into: ${person.mergedIntoId.getOrElse("Nothing")}, ${person.mergedIntoLabel.getOrElse("Nothing")}")

            if (person.hasFaces) {
              person.getFaces.foreach(face => println(s"    Face $face"))
            } else {
              println("    Person has no faces stored in cache")
            }
            println()
        }
    }
  }
}
