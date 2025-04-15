package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject

import scala.collection.mutable

import software.altitude.core.Const.FaceRecognition
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models.Person
import software.altitude.core.service.PersonService

abstract class PersonDao(override val config: Config) extends BaseDao with software.altitude.core.dao.PersonDao {

  final override val tableName = "person"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val mergedIntoLabel = rec(FieldConst.Person.MERGED_INTO_LABEL)

    Person(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      // To placate Postgres Sequences, which return Longs
      label = rec(FieldConst.Person.LABEL).getClass match {
        case c if c == classOf[java.lang.Integer] => rec(FieldConst.Person.LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(FieldConst.Person.LABEL).asInstanceOf[Long].toInt
      },
      isHidden = getBooleanField(rec(FieldConst.Person.IS_HIDDEN)),
      isBadMatch = getBooleanField(rec(FieldConst.Person.IS_BAD_MATCH)),
      isNamed = getBooleanField(rec(FieldConst.Person.IS_NAMED)),
      name = Option(rec(FieldConst.Person.NAME).asInstanceOf[String]),
      coverFaceId = Option(rec(FieldConst.Person.COVER_FACE_ID).asInstanceOf[String]),
      numOfFaces = rec(FieldConst.Person.NUM_OF_FACES).asInstanceOf[Int],
      mergedWithIds = loadCsv[String](rec(FieldConst.Person.MERGED_WITH_IDS).asInstanceOf[String]),
      mergedIntoId = Option(rec(FieldConst.Person.MERGED_INTO_ID).asInstanceOf[String]),
      // If mergedIntoLabel is there, it's an Int or a Long, depending on DB
      mergedIntoLabel = if (mergedIntoLabel != null) {
        Some(mergedIntoLabel.getClass match {
          case c if c == classOf[java.lang.Integer] => rec(FieldConst.Person.MERGED_INTO_LABEL).asInstanceOf[Int]
          case c if c == classOf[java.lang.Long] => rec(FieldConst.Person.MERGED_INTO_LABEL).asInstanceOf[Long].toInt
        })
      } else {
        None
      }
    )
  }

  protected def getPersonName(person: Person, sequenceNum: Long): String = {
    val name = if (person.name.nonEmpty) {
      person.name.get
    } else {
      s"${PersonService.UNKNOWN_NAME_PREFIX} $sequenceNum"
    }

    name
  }

  // lowercase name or "unknown_0001" etc
  protected def getPersonSortName(person: Person, sequenceNum: Long): String = {
    val sortName = if (person.name.nonEmpty) {
      person.name.get.toLowerCase()
    } else {
      f"${PersonService.UNKNOWN_NAME_PREFIX.toLowerCase()} $sequenceNum%04d"
    }

    sortName
  }

  override def updateMergedWithIds(person: Person, newId: String): Person = {
    val updatedIdList = person.mergedWithIds :+ newId

    val mergedWithIdsCsv = makeCsv(updatedIdList)

    val sqlVals: List[Any] = List(
      mergedWithIdsCsv,
      person.persistedId
    )

    val sql =
      s"""
            UPDATE person
               SET ${FieldConst.Person.MERGED_WITH_IDS} = ?
             WHERE ${FieldConst.ID} = ?
      """

    updateByBySql(sql, sqlVals)
    person.copy(mergedWithIds = updatedIdList)
  }

  def getAll: Map[String, Person] = {
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND num_of_faces > 0
                     AND merged_into_id is NULL
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    val lookup: mutable.Map[String, Person] = mutable.Map()

    recs.foreach {
      rec =>
        val person: Person = makeModel(rec)
        lookup += (person.persistedId -> person)
    }

    lookup.toMap
  }

  def getAllNotDiscarded: Map[String, Person] = {
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND merged_into_id is NULL
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    val lookup: mutable.Map[String, Person] = mutable.Map()

    recs.foreach {
      rec =>
        val person: Person = makeModel(rec)
        lookup += (person.persistedId -> person)
    }

    lookup.toMap
  }

  def getAllAboveThreshold: List[Person] = {
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND (num_of_faces >= ? OR is_named = ?)
                     AND is_hidden = FALSE
                     AND merged_into_id is NULL
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(
        sql,
        List(
          RequestContext.getRepository.persistedId,
          FaceRecognition.MIN_FACES_THRESHOLD,
          nativeBool(true)
        ))

    recs.map(makeModel)
  }

  def getAllBelowThreshold: List[Person] = {
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND num_of_faces < ?
                     AND is_named = ?
                     AND merged_into_id is NULL
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, FaceRecognition.MIN_FACES_THRESHOLD, nativeBool(false)))

    recs.map(makeModel)
  }

  def getAllHidden: List[Person] = {
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND is_hidden = TRUE
                     AND merged_into_id is NULL
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    recs.map(makeModel)
  }
}
