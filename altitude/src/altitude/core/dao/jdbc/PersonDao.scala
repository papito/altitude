package altitude.core.dao.jdbc

import altitude.core.Const as C
import altitude.core.Const.FaceRecognition
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Person
import altitude.core.service.PersonService
import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import scala.collection.mutable
import scala.language.implicitConversions

abstract class PersonDao(override val config: Config) extends BaseDao with altitude.core.dao.PersonDao:

  final override val tableName = "person"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject =
    Person(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      isHidden = getBooleanField(rec(FieldConst.Person.IS_HIDDEN)),
      isBadMatch = getBooleanField(rec(FieldConst.Person.IS_BAD_MATCH)),
      isNamed = getBooleanField(rec(FieldConst.Person.IS_NAMED)),
      name = Option(rec(FieldConst.Person.NAME).asInstanceOf[String]),
      coverFaceId = Option(rec(FieldConst.Person.COVER_FACE_ID).asInstanceOf[String]),
      numOfFaces = rec(FieldConst.Person.NUM_OF_FACES).asInstanceOf[Int]
    ).toJson

  override def add(jsonIn: JsObject): JsObject =
    val personSeqNum = getDataSourceType match
      case C.DbEngineName.POSTGRES => getNextVal("person_label").asInstanceOf[Long]
      case C.DbEngineName.SQLITE => getNextVal("person_label").asInstanceOf[Int].toLong

    val sql =
      s"""
        INSERT INTO person (${FieldConst.ID},
                            ${FieldConst.REPO_ID},
                            ${FieldConst.Person.NAME},
                            ${FieldConst.Person.NAME_FOR_SORT},
                            ${FieldConst.Person.IS_NAMED})
              VALUES (?, ?, ?, ?, ?)
   """

    val person: Person = jsonIn: Person
    val personName = getPersonName(person, personSeqNum)
    val personSortName = getPersonSortName(person, personSeqNum)
    val isNamed = person.name.nonEmpty

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      personName,
      personSortName,
      isNamed
    )

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(FieldConst.ID -> id, FieldConst.Person.NAME -> Some(personName), FieldConst.Person.IS_NAMED -> isNamed)

  protected def getPersonName(person: Person, sequenceNum: Long): String =
    val name =
      if person.name.nonEmpty then person.name.get
      else s"${PersonService.UNKNOWN_NAME_PREFIX} $sequenceNum"

    name

  // lowercase name or "unknown_0001" etc
  protected def getPersonSortName(person: Person, sequenceNum: Long): String =
    val sortName =
      if person.name.nonEmpty then person.name.get.toLowerCase()
      else f"${PersonService.UNKNOWN_NAME_PREFIX.toLowerCase()} $sequenceNum%04d"

    sortName

  def getAll: Map[String, Person] =
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND num_of_faces > 0
                     AND is_deleted = FALSE
                ORDER BY num_of_faces DESC
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

  def getAllNotDiscarded: Map[String, Person] =
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND is_deleted = FALSE
                     ORDER BY num_of_faces DESC
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

  def getAllAboveThreshold: List[Person] =
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND (num_of_faces >= ? OR is_named = ?)
                     AND is_hidden = FALSE
                     AND is_deleted = FALSE
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

  def getAllBelowThreshold: List[Person] =
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND num_of_faces < ?
                     AND is_named = ?
                     AND is_deleted = FALSE
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, FaceRecognition.MIN_FACES_THRESHOLD, nativeBool(false)))

    recs.map(makeModel)

  def getAllHidden: List[Person] =
    val sql = """SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND is_hidden = TRUE
                     AND is_deleted = FALSE
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId))

    recs.map(makeModel)

  def recycleFacesForAssets(assetIds: Set[String]): Unit =
    val placeHolders = List.fill(assetIds.size)("?").mkString(",")

    txManager.withTransaction {
      val sql = s"""
        UPDATE person
           SET num_of_faces = num_of_faces - (
              SELECT COUNT(*)
              FROM face f
              WHERE f.person_id = person.id
                AND f.asset_id IN ($placeHolders))
           WHERE EXISTS (
            SELECT 1
                FROM face
                WHERE person.id = face.person_id
                  AND face.asset_id IN ($placeHolders))
      """
      updateByBySql(sql, assetIds.toList ::: assetIds.toList)
    }

  def restoreFacesForAssets(assetIds: Set[String]): Unit =
    val placeHolders = List.fill(assetIds.size)("?").mkString(",")

    txManager.withTransaction {
      val sql = s"""
        UPDATE person
           SET num_of_faces = num_of_faces + (
              SELECT COUNT(*)
              FROM face f
              WHERE f.person_id = person.id
                AND f.asset_id IN ($placeHolders))
           WHERE EXISTS (
            SELECT 1
                FROM face
                WHERE person.id = face.person_id
                  AND face.asset_id IN ($placeHolders))
      """
      updateByBySql(sql, assetIds.toList ::: assetIds.toList)
    }
