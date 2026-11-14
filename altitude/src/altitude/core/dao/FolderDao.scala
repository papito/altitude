package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder

trait FolderDao extends BaseDao[Folder]:
  def getChildren(parentId: String): List[Folder]
  def getAncestors(folderId: String): List[Folder]
  def getPath(folderId: String): List[Folder]
  def getChildrenRecursive(parentId: String): List[Folder]

  /** The folder under `parentId` whose name has the key, live or recycled; never the root, which is its own parent */
  def getChild(parentId: String, nameKey: String): Option[Folder]

  /**
   * Makes the transactions that put a folder of the name key under `parentId` (an add, a rename, a move, an import's path) take
   * turns until they end, so the second finds the folder the first put there instead of failing on `folder_02`; see
   * `BaseDao.advisoryLock`
   */
  def lockName(parentId: String, nameKey: String): Unit

  /**
   * Makes the transactions that sort an import's file into the context repository's folder tree and those that delete a folder of
   * it take turns until they end. Neither's row locks hold the other off: a delete only marks its folders, which no reader and no
   * foreign key waits for, and it reads the folders and the assets below the one it deletes before the other commits them. See
   * `BaseDao.advisoryLock`
   */
  def lockTree(): Unit
