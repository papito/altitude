package altitude.core.service

import java.sql.SQLException
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.json.JsObject

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.implicitConversions

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.FaceDao
import altitude.core.dao.PersonDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Sort
import altitude.core.util.SortDirection
import altitude.core.util.Util.getDuplicateExceptionOrSame

object PersonService:
  val UNKNOWN_NAME_PREFIX = "Unknown"

class PersonService(val app: Altitude) extends BaseService[Person]:
  protected val dao: PersonDao = app.DAO.person
  private val faceDao: FaceDao = app.DAO.face

  override protected val txManager: TransactionManager = app.txManager

  override def add(objIn: Person): JsObject =
    throw new NotImplementedError("Use the alternate addPerson() method")

  def getPersonById(personId: String): Person =
    txManager.asReadOnly[Person] {
      dao.getById(personId)
    }

  def getFaceById(faceId: String): Face =
    txManager.asReadOnly[Face] {
      faceDao.getById(faceId)
    }

  def addPerson(person: Person): Person =
    txManager.withTransaction[Person] {
      try
        dao.add(person.toJson)
      catch
        case e: SQLException => throw getDuplicateExceptionOrSame(e)
        case ex: Exception => throw ex
    }

  def addFace(face: Face, asset: Asset, person: Person): Face =
    txManager.withTransaction[Face] {
      faceDao.add(face.toJson, asset.toJson, person.toJson)
    }

  def getAllAboveThreshold: List[Person] =
    txManager.asReadOnly[List[Person]] {
      dao.getAllAboveThreshold
    }

  def getAllBelowThreshold: List[Person] =
    txManager.asReadOnly[List[Person]] {
      dao.getAllBelowThreshold
    }

  def getAllHidden: List[Person] =
    txManager.asReadOnly[List[Person]] {
      dao.getAllHidden
    }
