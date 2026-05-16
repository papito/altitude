package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder

trait FolderDao extends BaseDao[Folder]:
  def getChildren(parentId: String): List[Folder]
  def getAncestors(folderId: String): List[Folder]
  def getChildrenRecursive(parentId: String): List[Folder]
