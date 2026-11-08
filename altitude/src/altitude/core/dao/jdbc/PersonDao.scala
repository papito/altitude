package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table
import scalasql.core.Expr
import scalasql.dialects.Dialect
import scalasql.query.Select

import altitude.core.{ Const => C }
import altitude.core.Const.FaceRecognition
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.PersonRow
import altitude.core.models.Person
import altitude.core.service.PersonService

abstract class PersonDao(override val config: Config) extends BaseDao[Person] with altitude.core.dao.PersonDao:

  final override val tableName = "person"

  final override type Row[T[_]] = PersonRow[T]
  final override protected def table: Table[Row] = PersonRow

  override protected def toModel(row: PersonRow[Sc]): Person =
    Person(
      id = Option(row.id),
      isHidden = row.isHidden,
      isBadMatch = row.isBadMatch,
      isNamed = row.isNamed,
      name = Option(row.name),
      coverFaceId = row.coverFaceId,
      numOfFaces = row.numOfFaces
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Person =
    Person(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      isHidden = getBooleanField(rec(FieldConst.Person.IS_HIDDEN)),
      isBadMatch = getBooleanField(rec(FieldConst.Person.IS_BAD_MATCH)),
      isNamed = getBooleanField(rec(FieldConst.Person.IS_NAMED)),
      name = Option(rec(FieldConst.Person.NAME).asInstanceOf[String]),
      coverFaceId = Option(rec(FieldConst.Person.COVER_FACE_ID).asInstanceOf[String]),
      numOfFaces = rec(FieldConst.Person.NUM_OF_FACES).asInstanceOf[Int]
    )

  override def add(person: Person): Person =
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

    val personName = getPersonName(person, personSeqNum)
    val personSortName = getPersonSortName(person, personSeqNum)
    val isNamed = person.name.isDefined

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      personName,
      personSortName,
      isNamed
    )

    addRecord(sql, sqlVals)
    person.copy(id = Some(id), name = Some(personName), isNamed = isNamed)

  // The name in NFC, or "Unknown 42" etc
  protected def getPersonName(person: Person, sequenceNum: Long): String =
    if person.name.isDefined then PersonService.normalizedName(person.name.get)
    else s"${PersonService.UNKNOWN_NAME_PREFIX} $sequenceNum"

  // The name key, or "unknown 0042" etc
  protected def getPersonSortName(person: Person, sequenceNum: Long): String =
    if person.name.isDefined then PersonService.nameKey(person.name.get)
    else f"${PersonService.UNKNOWN_NAME_PREFIX.toLowerCase} $sequenceNum%04d"

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

    makePersonLookup(recs)

  private def makePersonLookup(recs: List[Map[String, AnyRef]]): Map[String, Person] =
    recs.map(makeModel).map(p => p.persistedId -> p).toMap

  /**
   * One query whatever the buckets: the clauses every bucket shares, then an OR of the chosen buckets' own. Hidden takes
   * precedence, so a hidden person is in Hidden alone whatever its count or name: the Matched and Unmatched clauses both require
   * `is_hidden = FALSE`, and the three buckets are disjoint.
   */
  def getInBuckets(buckets: Set[String]): List[Person] =
    val unknown = buckets -- C.PeopleBucket.ALL
    require(unknown.isEmpty, s"Unknown People bucket(s): ${unknown.mkString(", ")}")
    if buckets.isEmpty then return Nil

    val bucketClauses: List[(String, List[Any])] = buckets.toList.sorted.map {
      case C.PeopleBucket.MATCHED =>
        (
          "(is_hidden = FALSE AND (num_of_faces >= ? OR is_named = ?))",
          List(FaceRecognition.MIN_FACES_THRESHOLD, nativeBool(true)))
      case C.PeopleBucket.UNMATCHED =>
        (
          "(is_hidden = FALSE AND num_of_faces < ? AND is_named = ?)",
          List(FaceRecognition.MIN_FACES_THRESHOLD, nativeBool(false)))
      case C.PeopleBucket.HIDDEN =>
        ("(is_hidden = TRUE)", Nil)
    }

    val sql = s"""SELECT *
                    FROM person
                   WHERE repository_id = ?
                     AND is_bad_match = FALSE
                     AND num_of_faces > 0
                     AND is_deleted = FALSE
                     AND (${bucketClauses.map(_._1).mkString(" OR ")})
                ORDER BY is_named DESC, name_for_sort
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, RequestContext.getRepository.persistedId :: bucketClauses.flatMap(_._2))

    recs.map(makeModel)

  // Reads `person_01`, the unique index the name key is kept unique by
  override def getLiveByNameKey(nameKey: String): Option[Person] =
    val live = PersonDao.liveByNameKey(dialect, RequestContext.getRepository.persistedId, nameKey)
    Db.read(dialect)(_.run(live)).headOption.map(toModel)

  def recycleFacesForAssets(assetIds: Set[String]): Unit = addFacesOfAssets(assetIds, sign = "-")

  def restoreFacesForAssets(assetIds: Set[String]): Unit = addFacesOfAssets(assetIds, sign = "+")

  /**
   * Adds to (or with `-`, takes from) each person's face count the assets, among the given ones, they have a Face on: the count
   * is of assets, so an asset holding two Faces of one person counts once. The people are found from the assets' faces, an
   * uncorrelated set read once through the index that leads with the asset, rather than by testing every person for a face on one
   * of them.
   */
  private def addFacesOfAssets(assetIds: Set[String], sign: String): Unit =
    val sql = s"""
      UPDATE person
         SET num_of_faces = num_of_faces $sign (
            SELECT COUNT(DISTINCT f.asset_id)
              FROM face f
             WHERE f.person_id = person.id
               AND f.asset_id $inIdSet)
       WHERE id IN (SELECT person_id FROM face WHERE asset_id $inIdSet)
    """
    updateByBySql(sql, List(idSet(assetIds), idSet(assetIds)))

  // On PostgreSQL; SQLite's one write connection serializes the writers
  override def lockAssetPeople(assetId: String): Unit =
    val sql = s"""
      SELECT id
        FROM person
       WHERE repository_id = ?
         AND id IN (SELECT person_id FROM face WHERE asset_id = ?)
      $forUpdate
    """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId))

  // The dead are locked too, and left out afterwards, so that a caller waits for whatever is changing them.
  // On PostgreSQL; SQLite's one write connection serializes the writers
  override def lockAndGetLive(personIds: Set[String]): Set[String] =
    val sql = s"""
      SELECT id, is_deleted, is_bad_match
        FROM person
       WHERE repository_id = ?
         AND id $inIdSet
    ORDER BY id
      $forUpdate
    """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(personIds)))
      .filterNot(
        rec => getBooleanField(rec(FieldConst.Person.IS_DELETED)) || getBooleanField(rec(FieldConst.Person.IS_BAD_MATCH)))
      .map(_(FieldConst.ID).asInstanceOf[String])
      .toSet

  override def deleteFaceless(personIds: Set[String]): Set[String] =
    if personIds.isEmpty then return Set.empty

    val sql = s"""
      SELECT id
        FROM person
       WHERE repository_id = ?
         AND id $inIdSet
         AND NOT EXISTS (SELECT 1 FROM face WHERE face.person_id = person.id)
    """
    val faceless = manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(personIds)))
      .map(_(FieldConst.ID).asInstanceOf[String])
      .toSet

    if faceless.nonEmpty then updateByBySql(s"DELETE FROM person WHERE id $inIdSet", List(idSet(faceless)))
    faceless

object PersonDao:

  /**
   * The live person of a repository whose sort name is the name key. The predicate is `person_01`'s, typed, so ScalaSql binds the
   * flags as numbers: SQLite uses a partial index only for a query whose own terms imply the index's, which a flag bound as the
   * text `nativeBool` binds on SQLite does not.
   */
  def liveByNameKey(dialect: Dialect, repositoryId: String, nameKey: String): Select[PersonRow[Expr], PersonRow[Sc]] =
    import dialect.*

    PersonRow.select.filter {
      person =>
        (person.repositoryId `=` repositoryId) && (person.nameForSort `=` nameKey) && (person.isDeleted `=` false) &&
        (person.isBadMatch `=` false)
    }
