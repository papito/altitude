package software.altitude.core.service
import play.api.libs.json._

import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.FieldConst
import software.altitude.core.IllegalOperationException
import software.altitude.core.NotFoundException
import software.altitude.core.ValidationException
import software.altitude.core.dao.FolderDao
import software.altitude.core.models.Folder
import software.altitude.core.util.Query

class FolderService(val app: Altitude) extends BaseService[Folder] {

  override protected val dao: FolderDao = app.DAO.folder

  /** Add a new folder - THIS SHOULD NOT BE USED DIRECTLY. Use <code>addFolder</code> */
  override def add(folder: Folder): JsObject = {
    txManager.withTransaction[JsObject] {
      super.add(folder)
    }
  }

  def getAll: List[JsObject] = {
    txManager.asReadOnly[List[JsObject]] {
      val q: Query = new Query().withRepository()
      dao.query(q).records
    }
  }

  def isRootFolder(id: String): Boolean =
    id == contextRepo.rootFolderId

  /** Get children for the parent given, but only a single level - non-recursive */
  def getChildren(rootId: String): List[Folder] = {
    txManager.asReadOnly[List[Folder]] {
      dao.getChildren(rootId)
    }
  }

  def getChildrenRecursive(rootId: String): List[Folder] = {
    txManager.asReadOnly[List[Folder]] {
      dao.getChildrenRecursive(rootId)
    }
  }

  def getAncestors(folderId: String): List[Folder] = {
    txManager.asReadOnly[List[Folder]] {
      dao.getAncestors(folderId)
    }
  }

  override def deleteById(id: String): Int = {
    dao.deleteById(id)
  }

  override def deleteByQuery(query: Query): Int = {
    throw new NotImplementedError("Cannot delete folders by query")
  }

  /** Move a folder from one parent to another */
  def move(folderBeingMovedId: String, destFolderId: String): (Folder, Folder) = {
    if (isRootFolder(folderBeingMovedId)) {
      throw IllegalOperationException("Cannot move the root folder")
    }

    logger.debug(s"Moving folder $folderBeingMovedId to $destFolderId")

    // cannot move into itself
    if (folderBeingMovedId == destFolderId) {
      throw IllegalOperationException(s"Cannot move a folder into itself. ID: $folderBeingMovedId")
    }

    txManager.withTransaction {
      // cannot move into own child
      if (getAncestors(destFolderId).map(_.persistedId).contains(folderBeingMovedId)) {
        throw DuplicateException(Some("Cannot move parent folder into a child node"))
      }

      var destFolder: Option[Folder] = None
      // check that the destination folder exists
      try {
        destFolder = Some(getById(destFolderId))
      } catch {
        case _: NotFoundException => throw ValidationException(s"Destination folder ID $destFolderId does not exist")
      }

      val folderBeingMoved: Folder = getById(folderBeingMovedId)

      val data = Map(
        FieldConst.Folder.PARENT_ID -> destFolderId,
        FieldConst.Folder.NAME -> folderBeingMoved.name
      )
      try {
        updateById(folderBeingMovedId, data)
      } catch {
        case e: Exception =>
          throw e
      }

      Tuple2(folderBeingMoved, destFolder.get)
    }
  }

  def rename(folderId: String, newName: String): Folder = {
    if (isRootFolder(folderId)) {
      throw IllegalOperationException("Cannot rename the root folder")
    }

    txManager.withTransaction {
      val folder: Folder = getById(folderId)

      val folderForUpdate: Folder = folder.copy(
        name = newName
      )

      val data = Map(FieldConst.Folder.NAME -> newName, FieldConst.Folder.NAME_LC -> newName.toLowerCase)

      updateById(folderId, data)
      folderForUpdate
    }
  }

  def setRecycledProp(folder: Folder, isRecycled: Boolean): Unit = {
    if (folder.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction {
      logger.info(s"Setting folder [${folder.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(folder.persistedId, Map(FieldConst.Folder.IS_RECYCLED -> isRecycled))
    }
  }

}
