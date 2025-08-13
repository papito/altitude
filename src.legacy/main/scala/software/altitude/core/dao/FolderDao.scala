package software.altitude.core.dao
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Folder

trait FolderDao extends BaseDao {
  def getChildren(parentId: String): List[Folder]
  def getAncestors(folderId: String): List[Folder]
  def getChildrenRecursive(parentId: String): List[Folder]
}
