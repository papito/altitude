package altitude.core.dao.sqlite

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Person
import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import scala.language.implicitConversions

class PersonDao(override val config: Config) extends altitude.core.dao.jdbc.PersonDao(config) with SqliteOverrides:

  val RESERVED_LABEL_COUNT = 100 // Stub constant

  override def add(jsonIn: JsObject): JsObject =
    // Get the next person label using the person_label sequence table
    val labelSql = "INSERT INTO person_label DEFAULT VALUES RETURNING id"
    val labelRes = executeAndGetOne(labelSql, List())
    val label = labelRes("id").asInstanceOf[Int]
    val personSeqNum = label - RESERVED_LABEL_COUNT

    val sql =
      s"""
        INSERT INTO person (${FieldConst.ID},
                            ${FieldConst.REPO_ID},
                            ${FieldConst.Person.LABEL},
                            ${FieldConst.Person.NAME},
                            ${FieldConst.Person.NAME_FOR_SORT},
                            ${FieldConst.Person.IS_NAMED})
              VALUES (?, ?, ?, ?, ?, ?)
   """

    val person: Person = jsonIn: Person
    val personName = getPersonName(person, personSeqNum)
    val personSortName = getPersonSortName(person, personSeqNum)
    val isNamed = person.name.nonEmpty

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      label,
      personName,
      personSortName,
      isNamed
    )

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(
      FieldConst.ID -> id,
      FieldConst.Person.LABEL -> label,
      FieldConst.Person.NAME -> Some(personName),
      FieldConst.Person.IS_NAMED -> isNamed)
