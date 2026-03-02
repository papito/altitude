package altitude.core.dao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder

trait FolderDao extends BaseDao:
  def getChildren(parentId: String): List[Folder]
  def getAncestors(folderId: String): List[Folder]
  def getChildrenRecursive(parentId: String): List[Folder]
