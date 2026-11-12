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
   * Makes the transactions that create a folder of the name key under `parentId` take turns until they end, so the second finds
   * the folder the first created instead of failing on `folder_02`; see `BaseDao.advisoryLock`
   */
  def lockName(parentId: String, nameKey: String): Unit
