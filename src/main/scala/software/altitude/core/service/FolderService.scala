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

  def repositoryFolders(allRepoFolders: List[JsObject] = List()): List[JsObject] = {
    txManager.asReadOnly[List[JsObject]] {
      val _all = if (allRepoFolders.isEmpty) getAll else allRepoFolders

      _all.filter(
        json => {
          val id = (json \ FieldConst.ID).asOpt[String]
          !isRootFolder(id.get)
        })
    }
  }

  /** Get children for the parent given, but only a single level - non-recursive */
  def immediateChildren(rootId: String, allRepoFolders: List[JsObject] = List()): List[Folder] = {
    txManager.asReadOnly[List[Folder]] {
      val repoFolders = repositoryFolders(allRepoFolders)

      repoFolders
        .filter(
          json => {
            val parentId = (json \ FieldConst.Folder.PARENT_ID).as[String]
            parentId == rootId
          })
        .map(json => Folder.fromJson(json))
        .sortBy(_.nameLowercase)
    }
  }

  override def deleteById(id: String): Int = {
    dao.deleteById(id)
  }

  override def deleteByQuery(query: Query): Int = {
    throw new NotImplementedError("Cannot delete folders by query")
  }

  /**
   * Return all folders, with their depths, as a flat list, for a given parent folder. Specifically, returns a flat list of
   * tuples, where the first element is the depth, relative to parent folder, and the second element is the folder ID.
   */
  def flatChildrenIdsWithDepths(
      parentId: String,
      allRepoFolders: List[JsObject] = List(),
      depth: Int = 0): List[(Int, String)] = {
    val repoFolders = repositoryFolders(allRepoFolders)

    val childElements = repoFolders.filter(j => (j \ FieldConst.Folder.PARENT_ID).asOpt[String].contains(parentId))

    // recursively combine with the result of deeper child levels + this one (depth-first)
    (depth, parentId) :: childElements.foldLeft(List[(Int, String)]()) {
      (res, json) =>
        val folderId = (json \ FieldConst.ID).as[String]
        res ++ flatChildrenIdsWithDepths(folderId, repoFolders, depth + 1)
    }
  }

  /**
   * Returns a unique set of folder IDs for one OR more folder ids. The difference between the other method is that folder depths
   * are not returned. It's a "raw" list of folder ids.
   */
  def flatChildrenIds(parentIds: Set[String], allRepoFolders: List[JsObject] = List()): Set[String] =
    parentIds.foldLeft(Set[String]()) {
      (s, id) =>
        {
          s ++ app.service.folder.flatChildrenIdsWithDepths(parentId = id, allRepoFolders = allRepoFolders).map(_._2).toSet
        }
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
      if (flatChildrenIdsWithDepths(folderBeingMovedId, repositoryFolders()).map(_._2).contains(destFolderId)) {
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
