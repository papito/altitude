package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import software.altitude.core.models.Person
import software.altitude.core.util.Query
import software.altitude.core.{RequestContext, Const => C}

import scala.collection.mutable

abstract class PersonDao(override val config: Config) extends BaseDao with software.altitude.core.dao.PersonDao {

  override final val tableName = "person"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val mergedIntoLabel = rec(C.Person.MERGED_INTO_LABEL)

    val model = Person(
      id = Option(rec(C.Base.ID).asInstanceOf[String]),
      // To placate Postgres Sequences, which return Longs
      label = rec(C.Person.LABEL).getClass match {
        case c if c == classOf[java.lang.Integer] => rec(C.Person.LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(C.Person.LABEL).asInstanceOf[Long].toInt
      },
      name = Option(rec(C.Person.NAME).asInstanceOf[String]),
      coverFaceId = Option(rec(C.Person.COVER_FACE_ID).asInstanceOf[String]),
      mergedWithIds = loadCsv[String](rec(C.Person.MERGED_WITH_IDS).asInstanceOf[String]),
      mergedIntoId = Option(rec(C.Person.MERGED_INTO_ID).asInstanceOf[String]),
      // If mergedIntoLabel is there, it's an Int or a Long, depending on DB
      mergedIntoLabel = if (mergedIntoLabel != null) {
        Some(mergedIntoLabel.getClass match {
        case c if c == classOf[java.lang.Integer] => rec(C.Person.MERGED_INTO_LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(C.Person.MERGED_INTO_LABEL).asInstanceOf[Long].toInt
      })
      } else {
        None
      },
      numOfFaces = rec(C.Person.NUM_OF_FACES).asInstanceOf[Int],
      isHidden = getBooleanField(rec(C.Person.IS_HIDDEN))
    )

    addCoreAttrs(model, rec)
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
            UPDATE $tableName
               SET ${C.Person.MERGED_WITH_IDS} = ?
             WHERE ${C.Base.ID} = ?
      """

    updateByBySql(sql, sqlVals)
    person.copy(mergedWithIds = updatedIdList)
  }

  def getAll: Map[String, Person] = {
    val sql = s"SELECT * from $tableName WHERE repository_id = ? AND num_of_faces > 0"
    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    val lookup: mutable.Map[String, Person] = mutable.Map()

    recs.foreach { rec =>
      val person: Person = makeModel(rec)
      lookup += (person.persistedId -> person)
    }

    lookup.toMap
  }

}
