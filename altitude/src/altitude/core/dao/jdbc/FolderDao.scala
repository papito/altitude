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

  // `numOfChildren` is computed only by the hand-written queries
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
    // The root folder is its own parent, so its child count is one less than the rows that name it as parent
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

  /** Every folder above `folderId` short of the repository root (its own parent), the top-level folder first */
  def getAncestors(folderId: String): List[Folder] = pathFrom(folderId, firstLevel = 1)

  /** The folder's ancestors and then the folder itself, so the root's path is the root alone; empty for an unknown ID */
  def getPath(folderId: String): List[Folder] = pathFrom(folderId, firstLevel = 0)

  /** The folders from `folderId` up, short of the root above it, highest first; `firstLevel` 1 skips the folder itself */
  private def pathFrom(folderId: String, firstLevel: Int): List[Folder] =
    val sql = s"""
      WITH RECURSIVE ancestors AS (
        SELECT *, 0 AS level ${Acyclic.anchorColumn}
          FROM folder
         WHERE id = ?
           AND repository_id = ?

        UNION ALL

        SELECT f.*, a.level + 1 AS level ${Acyclic.armColumn("a")}
          FROM folder f
          JOIN ancestors a
            ON f.id = a.parent_id
         WHERE f.repository_id = ?
           AND f.id <> f.parent_id
           ${Acyclic.armCondition("a")}
      ) ${Acyclic.clause}
      SELECT *
        FROM ancestors
       WHERE level >= ?
         AND ${Acyclic.kept}
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

  /** Every live folder below `parentId`, never the root; does not return the child counts */
  def getChildrenRecursive(parentId: String): List[Folder] =
    val sql = s"""
      WITH RECURSIVE children AS (
        SELECT * ${Acyclic.anchorColumn}
          FROM folder
         WHERE parent_id = ?
           AND repository_id = ?
           AND is_recycled = ?
           AND id <> parent_id

        UNION ALL

        SELECT f.* ${Acyclic.armColumn("c")}
          FROM folder f
          JOIN children c
            ON f.parent_id = c.id
         WHERE f.repository_id = ?
           AND f.is_recycled = ?
           AND f.id <> f.parent_id
           ${Acyclic.armCondition("c")}
      ) ${Acyclic.clause}
      SELECT *
        FROM children
       WHERE ${Acyclic.kept}
  """

    val repositoryId = RequestContext.getRepository.persistedId
    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(parentId, repositoryId, nativeBool(false), repositoryId, nativeBool(false)))

    recs.map(makeModel)

  /**
   * The parts of a recursive statement over `parent_id` that end it at a folder its path has already visited, so a cycle stored
   * in the tree ends the statement instead of recursing until the read statement timeout, or forever in a write. Moves take the
   * tree's turn, so none commits a cycle (`FolderService.move`); this guards the reads against one stored before that.
   * PostgreSQL's `CYCLE` clause marks the row that closes a cycle and stops there, and the outer select drops that row. SQLite
   * has no such clause, so the statement carries the IDs it visited as `,id,id,` text and the recursive arm refuses a folder
   * already among them: either way a folder of the path appears once.
   */
  private object Acyclic:
    private val onSqlite = getDataSourceType == C.DbEngineName.SQLITE

    /** Added to the anchor's select list */
    val anchorColumn: String = if onSqlite then ", ',' || id || ',' AS visited" else ""

    /** Added to the recursive arm's select list, `prior` naming the recursive table in it */
    def armColumn(prior: String): String = if onSqlite then s", $prior.visited || f.id || ','" else ""

    /** Added to the recursive arm's conditions */
    def armCondition(prior: String): String = if onSqlite then s"AND instr($prior.visited, ',' || f.id || ',') = 0" else ""

    /** Follows the recursive table's definition */
    val clause: String = if onSqlite then "" else "CYCLE id SET is_cycle USING visited"

    /** The outer select's condition */
    val kept: String = if onSqlite then "1 = 1" else "NOT is_cycle"
