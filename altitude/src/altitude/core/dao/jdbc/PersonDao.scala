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
import altitude.core.util.SortKey

abstract class PersonDao(override val config: Config) extends BaseDao[Person] with altitude.core.dao.PersonDao:

  final override val tableName = "person"

  final override type Row[T[_]] = PersonRow[T]
  final override protected def table: Table[Row] = PersonRow

  override protected val sortKeyColumns: Map[String, String] = Map(FieldConst.Person.NAME -> FieldConst.Person.NAME_SORT)

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
    // A Long on PostgreSQL; on SQLite an Integer until the label outgrows one
    val personSeqNum = getNextVal("person_label").asInstanceOf[Number].longValue

    val sql =
      s"""
        INSERT INTO person (${FieldConst.ID},
                            ${FieldConst.REPO_ID},
                            ${FieldConst.Person.NAME},
                            ${FieldConst.Person.NAME_FOR_SORT},
                            ${FieldConst.Person.NAME_SORT},
                            ${FieldConst.Person.IS_NAMED})
              VALUES (?, ?, ?, ?, ?, ?)
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
      SortKey.of(personName).bytes,
      isNamed
    )

    addRecord(sql, sqlVals)
    person.copy(id = Some(id), name = Some(personName), isNamed = isNamed)

  // The name in NFC, or "Unknown 42" etc
  protected def getPersonName(person: Person, sequenceNum: Long): String =
    if person.name.isDefined then PersonService.normalizedName(person.name.get)
    else s"${PersonService.UNKNOWN_NAME_PREFIX} $sequenceNum"

  // The name key, or "unknown 0000000042" etc: the name key of the prefix, so the reserved-name rule
  // (PersonService.isReservedName) keeps every named person's key apart from it. Ten digits keep the unnamed in number order as
  // text up to label 9,999,999,999 of the instance-wide label sequence, which discarded imports also draw from.
  protected def getPersonSortName(person: Person, sequenceNum: Long): String =
    if person.name.isDefined then PersonService.nameKey(person.name.get)
    else f"${PersonService.nameKey(PersonService.UNKNOWN_NAME_PREFIX)} $sequenceNum%010d"

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
   * One query for any buckets: the shared clauses, then an OR of the chosen buckets' own. Hidden takes precedence (Matched and
   * Unmatched require `is_hidden = FALSE`), so the buckets are disjoint.
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
                ORDER BY is_named DESC, name_sort, id
               """
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, RequestContext.getRepository.persistedId :: bucketClauses.flatMap(_._2))

    recs.map(makeModel)

  // A Long on PostgreSQL and an Int on SQLite, so it is read through `Number`
  private val TRIAGED_ASSETS = "triaged_assets"

  override def getTriagedAssetCounts: Map[String, Int] =
    val (sql, values) = triagedAssetCountsStatement
    manyBySqlQuery(sql, values)
      .map(rec => rec(FieldConst.Face.PERSON_ID).asInstanceOf[String] -> rec(TRIAGED_ASSETS).asInstanceOf[Number].intValue)
      .toMap

  /**
   * Exposed for the plan test. The flags are literals: SQLite uses the partial `asset_triage_*` indexes only for a matching
   * predicate, which a flag bound as text is not. `is_pipeline_processed` is not filtered: an in-flight import's Faces are in
   * `num_of_faces`, and its assets are triaged.
   */
  def triagedAssetCountsStatement: (String, List[Any]) =
    val sql = s"""
      SELECT f.${FieldConst.Face.PERSON_ID}, COUNT(DISTINCT f.${FieldConst.Face.ASSET_ID}) AS $TRIAGED_ASSETS
        FROM asset a
        JOIN face f ON f.${FieldConst.Face.ASSET_ID} = a.${FieldConst.ID}
       WHERE a.${FieldConst.REPO_ID} = ?
         AND a.${FieldConst.Asset.IS_TRIAGED} = TRUE
         AND a.${FieldConst.Asset.IS_RECYCLED} = FALSE
    GROUP BY f.${FieldConst.Face.PERSON_ID}
    """
    (sql, List(RequestContext.getRepository.persistedId))

  // By `person_01`
  override def getLiveByNameKey(nameKey: String): Option[Person] =
    val live = PersonDao.liveByNameKey(dialect, RequestContext.getRepository.persistedId, nameKey)
    Db.read(dialect)(_.run(live)).headOption.map(toModel)

  override def lockName(nameKey: String): Unit =
    advisoryLock(s"person-name:${RequestContext.getRepository.persistedId}:$nameKey")

  // By `person_03`, the partial index over the live people
  override def getAssignable: List[Person] =
    val sql = """SELECT *
                   FROM person
                  WHERE repository_id = ?
                    AND is_deleted = FALSE
                    AND is_bad_match = FALSE
               ORDER BY is_named DESC, name_sort, id
              """
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId)).map(makeModel)

  def recycleFacesForAssets(assetIds: Set[String]): Unit = addFacesOfAssets(assetIds, sign = "-")

  def restoreFacesForAssets(assetIds: Set[String]): Unit = addFacesOfAssets(assetIds, sign = "+")

  /**
   * Adds to (or with `-`, takes from) each person's face count the given assets they have a Face on; an asset with two of their
   * Faces counts once. The people come from one uncorrelated read of the assets' Faces by the asset index, rather than testing
   * every person. Another repository's asset counts for nobody.
   */
  private def addFacesOfAssets(assetIds: Set[String], sign: String): Unit =
    val repositoryId = RequestContext.getRepository.persistedId
    val sql = s"""
      UPDATE person
         SET num_of_faces = num_of_faces $sign (
            SELECT COUNT(DISTINCT f.asset_id)
              FROM face f
             WHERE f.repository_id = ?
               AND f.person_id = person.id
               AND f.asset_id $inIdSet)
       WHERE repository_id = ?
         AND id IN (SELECT person_id FROM face WHERE repository_id = ? AND asset_id $inIdSet)
    """
    updateByBySql(sql, List(repositoryId, idSet(assetIds), repositoryId, repositoryId, idSet(assetIds)))

  // Locks on PostgreSQL only; SQLite's one write connection serializes the writers
  override def lockAssetPeople(assetIds: Set[String]): Set[String] =
    if assetIds.isEmpty then return Set.empty

    val sql = s"""
      SELECT id
        FROM person
       WHERE repository_id = ?
         AND id IN (SELECT person_id FROM face WHERE repository_id = ? AND asset_id $inIdSet)
    ORDER BY id
      $forUpdate
    """
    val repositoryId = RequestContext.getRepository.persistedId
    manyBySqlQuery(sql, List(repositoryId, repositoryId, idSet(assetIds))).map(_(FieldConst.ID).asInstanceOf[String]).toSet

  override def getAssetPeople(assetIds: Set[String]): Set[String] =
    if assetIds.isEmpty then return Set.empty

    val sql = s"SELECT DISTINCT person_id FROM face WHERE repository_id = ? AND asset_id $inIdSet"
    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, idSet(assetIds)))
      .map(_(FieldConst.Face.PERSON_ID).asInstanceOf[String])
      .toSet

  // The dead are locked too and left out afterwards, so a caller waits for whatever is changing them
  override def lockAndGetLive(personIds: Set[String]): Set[String] =
    if personIds.isEmpty then return Set.empty

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

  override def deleteFaceless(personIds: Set[String], keepNamed: Boolean): Set[String] =
    if personIds.isEmpty then return Set.empty

    val repositoryId = RequestContext.getRepository.persistedId
    val namedTest = if keepNamed then s"AND ${FieldConst.Person.IS_NAMED} = FALSE" else ""
    val sql = s"""
      SELECT id
        FROM person
       WHERE repository_id = ?
         AND id $inIdSet
         $namedTest
         AND NOT EXISTS (SELECT 1 FROM face WHERE face.person_id = person.id)
    """
    val faceless = manyBySqlQuery(sql, List(repositoryId, idSet(personIds)))
      .map(_(FieldConst.ID).asInstanceOf[String])
      .toSet

    if faceless.nonEmpty then
      updateByBySql(s"DELETE FROM person WHERE repository_id = ? AND id $inIdSet", List(repositoryId, idSet(faceless)))
    faceless

object PersonDao:

  /**
   * The live person with that name key. The predicate is `person_01`'s, typed so ScalaSql binds the flags as numbers: SQLite uses
   * a partial index only when the query's terms imply its predicate, which a flag bound as text (`nativeBool` on SQLite) does
   * not.
   */
  def liveByNameKey(dialect: Dialect, repositoryId: String, nameKey: String): Select[PersonRow[Expr], PersonRow[Sc]] =
    import dialect.*

    PersonRow.select.filter {
      person =>
        (person.repositoryId `=` repositoryId) && (person.nameForSort `=` nameKey) && (person.isDeleted `=` false) &&
        (person.isBadMatch `=` false)
    }
