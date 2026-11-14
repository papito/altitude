package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.{ Const => C }
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.FolderRow
import altitude.core.models.Folder

abstract class FolderDao(override val config: Config) extends BaseDao[Folder] with altitude.core.dao.FolderDao:
  final override val tableName = "folder"

  final override type Row[T[_]] = FolderRow[T]
  final override protected def table: Table[Row] = FolderRow

  // `numOfChildren` is a computed column of the hand-written queries; the typed paths never select it, as before
  override protected def toModel(row: FolderRow[Sc]): Folder =
    Folder(
      id = Option(row.id),
      name = row.name,
      parentId = row.parentId,
      isRecycled = row.isRecycled
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Folder =
    Folder(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Folder.NAME).asInstanceOf[String],
      parentId = rec(FieldConst.Folder.PARENT_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(FieldConst.Folder.IS_RECYCLED)),
      numOfChildren = rec.get(FieldConst.Folder.NUM_OF_CHILDREN).map(getIntField).getOrElse(0)
    )

  override def getById(id: String): Folder =
    /**
     * The wrinkle here is that we need to return the number of children for the folder, but if the folder is a root folder, we
     * need to subtract 1 from the count, because the root folder is its own parent, introducing a one-off error.
     *
     * Hand-written, so it carries the repository itself: another repository's folder is not found, as with any scoped read.
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
        AND f.repository_id = ?
   """

    getOneBySql(sql, List(nativeBool(false), nativeBool(false), id, RequestContext.getRepository.persistedId))

  override def add(folder: Folder): Folder =
    val id = folder.id match
      case Some(id) => id
      case None => BaseDao.genId

    val sql = s"""
        INSERT INTO $tableName (
                      ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Folder.NAME}, ${FieldConst.Folder.NAME_KEY}, ${FieldConst.Folder.PARENT_ID}
                    )
             VALUES (?, ? , ?, ?, ?)
    """

    val sqlVals: List[Any] =
      List(id, RequestContext.getRepository.persistedId, folder.name, folder.nameKey, folder.parentId)

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
     *
     * Both arms of the recursion carry the repository, as every hand-written folder read does: another repository's folder has no
     * children here.
     */
    val recursiveOrderByClause = getDataSourceType match
      case C.DbEngineName.POSTGRES => ""
      case C.DbEngineName.SQLITE => s"ORDER BY f.${FieldConst.Folder.NAME_KEY}"

    val sql = s"""
      WITH RECURSIVE children AS (
        -- start with the provided folder id
        SELECT *, 0 AS level
          FROM folder
         WHERE id = ?
           AND repository_id = ?

        UNION ALL

        -- get only immediate children (level = 1)
        SELECT f.*, c.level + 1
          FROM folder f, children c
         WHERE f.parent_id = c.id
           AND f.repository_id = ?
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
      ORDER BY f.${FieldConst.Folder.NAME_KEY}
    """

    val repositoryId = RequestContext.getRepository.persistedId
    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(parentId, repositoryId, repositoryId))
    recs.map(makeModel)

  /**
   * Every folder above `folderId` short of the repository root (its own parent), the top-level folder first. Another repository's
   * folder has no ancestors here.
   */
  def getAncestors(folderId: String): List[Folder] = pathFrom(folderId, firstLevel = 1)

  /**
   * The folder's path: its ancestors ([[getAncestors]]) and then the folder itself, so the root's is the root alone. An ID that
   * names no folder of the repository has no path.
   */
  def getPath(folderId: String): List[Folder] = pathFrom(folderId, firstLevel = 0)

  /**
   * The folders from `folderId` up, short of the repository root when it is above it, the highest first: `firstLevel` 0 starts at
   * the folder itself and 1 at its parent. Both arms of the recursion carry the repository.
   */
  private def pathFrom(folderId: String, firstLevel: Int): List[Folder] =
    val sql = """
      WITH RECURSIVE ancestors AS (
        SELECT *, 0 AS level
          FROM folder
         WHERE id = ?
           AND repository_id = ?

        UNION ALL

        SELECT f.*, a.level + 1 AS level
          FROM folder f
          JOIN ancestors a
            ON f.id = a.parent_id
         WHERE f.repository_id = ?
           AND f.id <> f.parent_id
      )
      SELECT *
        FROM ancestors
       WHERE level >= ?
       ORDER BY level DESC
    """

    val repositoryId = RequestContext.getRepository.persistedId
    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(folderId, repositoryId, repositoryId, firstLevel))
    recs.map(makeModel)

  override def getChild(parentId: String, nameKey: String): Option[Folder] =
    val sql = s"""
      SELECT *
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Folder.PARENT_ID} = ?
         AND ${FieldConst.Folder.NAME_KEY} = ?
         AND ${FieldConst.ID} <> ${FieldConst.Folder.PARENT_ID}
    """

    manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, parentId, nameKey)).headOption.map(makeModel)

  override def lockName(parentId: String, nameKey: String): Unit =
    advisoryLock(s"folder-name:${RequestContext.getRepository.persistedId}:$parentId:$nameKey")

  override def lockTree(): Unit = advisoryLock(s"folder-tree:${RequestContext.getRepository.persistedId}")

  // This does NOT return the child counts. Both arms of the recursion carry the repository: another repository's folder has no
  // children here
  def getChildrenRecursive(parentId: String): List[Folder] =
    val sql = """
      WITH RECURSIVE children AS (
        SELECT *
          FROM folder
         WHERE parent_id = ?
           AND repository_id = ?
           AND is_recycled = ?

        UNION ALL

        SELECT f.*
          FROM folder f
          JOIN children c
            ON f.parent_id = c.id
         WHERE f.repository_id = ?
           AND f.is_recycled = ?
           AND f.id <> f.parent_id
      )
      SELECT *
        FROM children
  """

    val repositoryId = RequestContext.getRepository.persistedId
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(parentId, repositoryId, nativeBool(false), repositoryId, nativeBool(false)))

    recs.map(makeModel)
