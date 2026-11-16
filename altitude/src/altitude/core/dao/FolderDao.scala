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
   * Makes the transactions that put a folder of the name key under `parentId` take turns, so the second finds the folder the
   * first put there instead of failing on `folder_02` (`BaseDao.advisoryLock`)
   */
  def lockName(parentId: String, nameKey: String): Unit

  /**
   * Makes an import sorting a file into the folder tree and a folder delete take turns (`BaseDao.advisoryLock`). Row locks do
   * not: a delete only marks its folders, which nothing waits for, and could read the tree before the import commits to it.
   */
  def lockTree(): Unit
