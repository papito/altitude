package altitude.core.dao.jdbc

import com.typesafe.config.Config

import scala.language.implicitConversions

import altitude.core.{ Const => C }
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Folder

abstract class FolderDao(override val config: Config) extends BaseDao[Folder] with altitude.core.dao.FolderDao:
  final override val tableName = "folder"

  override protected def makeModel(rec: Map[String, AnyRef]): Folder =
    Folder(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Folder.NAME).asInstanceOf[String],
      parentId = rec(FieldConst.Folder.PARENT_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(FieldConst.Folder.IS_RECYCLED)),
      numOfChildren = rec.getOrElse(FieldConst.Folder.NUM_OF_CHILDREN, 0L) match
        case i: java.lang.Integer => i
        case l: java.lang.Long => l.toInt
        case _ =>
          throw new IllegalArgumentException(s"Invalid type for NUM_OF_CHILDREN: ${rec(FieldConst.Folder.NUM_OF_CHILDREN)}")
    )

  override def getById(id: String): Folder =
    /**
     * The wrinkle here is that we need to return the number of children for the folder, but if the folder is a root folder, we
     * need to subtract 1 from the count, because the root folder is its own parent, introducing a one-off error.
     */
    val sql = """
      SELECT f.*, (
        CASE
          WHEN f.id = f.parent_id THEN (
            (SELECT COUNT(*) FROM folder sub
              WHERE sub.is_recycled = ?
                AND sub.parent_id = f.id) - 1
          )
          ELSE (
            SELECT COUNT(*) FROM folder sub
              WHERE sub.is_recycled = ?
                AND sub.parent_id = f.id
          )
        END
      ) AS num_of_children
      FROM folder f
      WHERE f.id = ?
   """

    getOneBySql(sql, List(nativeBool(false), nativeBool(false), id))

  override def add(folder: Folder): Folder =
    val id = folder.id match
      case Some(id) => id
      case None => BaseDao.genId

    val sql = s"""
        INSERT INTO $tableName (
                      ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Folder.NAME}, ${FieldConst.Folder.NAME_LC}, ${FieldConst.Folder.PARENT_ID}
                    )
             VALUES (?, ? , ?, ?, ?)
    """

    val sqlVals: List[Any] =
      List(id, RequestContext.getRepository.persistedId, folder.name, folder.nameLowercase, folder.parentId)

    addRecord(sql, sqlVals)
    folder.copy(id = Some(id))

  def getChildren(parentId: String): List[Folder] =
    /**
     * Postgres does not allow ORDER BY inside a recursion, but it's not needed - the order is correct via the final "order by"
     * clause.
     *
     * SQLite DOES allow ORDER BY during recursion, and it IS needed to get the correct order.
     *
     * While normally we create an override function for each engine if a query is different, the query here is complex and
     * effectively the same, except for one line of SQL, so we are going to break the rules and do the engine-specific logic in
     * the query itself.
     *
     * This kind of shenanigan is normally not recommended.
     */
    val recursiveOrderByClause = getDataSourceType match
      case C.DbEngineName.POSTGRES => ""
      case C.DbEngineName.SQLITE => "ORDER BY f.name_lc"

    val sql = s"""
      WITH RECURSIVE children AS (
        -- start with the provided folder id
        SELECT *, 0 AS level
          FROM folder
         WHERE id = ?

        UNION ALL

        -- get only immediate children (level = 1)
        SELECT f.*, c.level + 1
          FROM folder f, children c
         WHERE f.parent_id = c.id
           AND f.is_recycled = false
           AND c.level < 1
          $recursiveOrderByClause
      )
      SELECT f.*,
        (
          SELECT COUNT(*)
            FROM folder sub
           WHERE sub.parent_id = f.id
        ) AS num_of_children
       FROM children f
      WHERE f.level = 1
        AND f.id <> f.parent_id
      ORDER BY f.name_lc
    """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(parentId))
    recs.map(makeModel)

  def getAncestors(folderId: String): List[Folder] =
    val sql = """
      WITH RECURSIVE ancestors AS (
        SELECT *, 0 AS level
          FROM folder
         WHERE id = ?

        UNION ALL

        SELECT f.*, a.level + 1 AS level
          FROM folder f
          JOIN ancestors a
            ON f.id = a.parent_id
         WHERE f.id <> f.parent_id
      )
      SELECT *
        FROM ancestors
       WHERE level > 0
    """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(folderId))
    recs.map(makeModel)

  // this does NOT return the child counts
  def getChildrenRecursive(parentId: String): List[Folder] =
    val sql = """
      WITH RECURSIVE children AS (
        SELECT *
          FROM folder
         WHERE parent_id = ?
           AND is_recycled = ?

        UNION ALL

        SELECT f.*
          FROM folder f
          JOIN children c
            ON f.parent_id = c.id
         WHERE f.is_recycled = ?
           AND f.id <> f.parent_id
      )
      SELECT *
        FROM children
  """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(parentId, nativeBool(false), nativeBool(false)))

    recs.map(makeModel)
