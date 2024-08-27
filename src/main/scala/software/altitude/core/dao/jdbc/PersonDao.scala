package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import software.altitude.core.RequestContext
import software.altitude.core.models.Field
import software.altitude.core.models.Person

import scala.collection.mutable

abstract class PersonDao(override val config: Config) extends BaseDao with software.altitude.core.dao.PersonDao {

  override final val tableName = "person"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val mergedIntoLabel = rec(Field.Person.MERGED_INTO_LABEL)

    val model = Person(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      // To placate Postgres Sequences, which return Longs
      label = rec(Field.Person.LABEL).getClass match {
        case c if c == classOf[java.lang.Integer] => rec(Field.Person.LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(Field.Person.LABEL).asInstanceOf[Long].toInt
      },
      name = Option(rec(Field.Person.NAME).asInstanceOf[String]),
      coverFaceId = Option(rec(Field.Person.COVER_FACE_ID).asInstanceOf[String]),
      mergedWithIds = loadCsv[String](rec(Field.Person.MERGED_WITH_IDS).asInstanceOf[String]),
      mergedIntoId = Option(rec(Field.Person.MERGED_INTO_ID).asInstanceOf[String]),
      // If mergedIntoLabel is there, it's an Int or a Long, depending on DB
      mergedIntoLabel = if (mergedIntoLabel != null) {
        Some(mergedIntoLabel.getClass match {
        case c if c == classOf[java.lang.Integer] => rec(Field.Person.MERGED_INTO_LABEL).asInstanceOf[Int]
        case c if c == classOf[java.lang.Long] => rec(Field.Person.MERGED_INTO_LABEL).asInstanceOf[Long].toInt
      })
      } else {
        None
      },
      numOfFaces = rec(Field.Person.NUM_OF_FACES).asInstanceOf[Int],
      isHidden = getBooleanField(rec(Field.Person.IS_HIDDEN))
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
               SET ${Field.Person.MERGED_WITH_IDS} = ?
             WHERE ${Field.ID} = ?
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
