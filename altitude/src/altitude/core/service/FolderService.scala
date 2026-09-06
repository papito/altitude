package altitude.core.service

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.dao.FolderDao
import altitude.core.models.Folder
import altitude.core.util.Query

class FolderService(val app: Altitude) extends BaseService[Folder]:

  override protected val dao: FolderDao = app.DAO.folder

  def add(name: String, parentId: Option[String] = None): Folder =
    txManager.withTransaction {
      val _parentId = if parentId.isDefined then parentId.get else RequestContext.getRepository.rootFolderId
      val folder = Folder(name = name.trim, parentId = _parentId)
      app.service.folder.add(folder)
    }

  override def add(folder: Folder): Folder =
    txManager.withTransaction {
      super.add(folder)
    }

  def getAll: List[Folder] =
    txManager.asReadOnly {
      val q: Query = new Query().withRepository()
      dao.query(q).records
    }

  /**
   * The non-recycled folder tree rooted at the repository root, children sorted by name, with `numOfChildren` and the recursive
   * `numOfAssets` populated on every node. Loaded with one folder query and one per-folder asset count query.
   */
  def getTree: Folder =
    txManager.asReadOnly {
      val folders = getAll.filterNot(_.isRecycled)

      val rootFolder = folders
        .find(_.persistedId == contextRepo.rootFolderId)
        .getOrElse(throw NotFoundException(s"Root folder ${contextRepo.rootFolderId} not found"))

      // The root folder is its own parent - keep it out of the index so it does not become its own child
      val childrenByParentId = folders.filter(f => f.persistedId != f.parentId).groupBy(_.parentId)

      // Assets in recycled folders never surface here: deleting a folder recycles its assets in the same transaction
      val directCounts = app.service.asset.countByFolder()

      assembleTree(rootFolder, childrenByParentId, directCounts)
    }

  /** Post-order pass: a folder's asset count is its own assets plus those of its assembled children */
  private def assembleTree(
      folder: Folder,
      childrenByParentId: Map[String, List[Folder]],
      directCounts: Map[String, Int]): Folder =
    val children = childrenByParentId
      .getOrElse(folder.persistedId, Nil)
      .sortBy(_.nameLowercase)
      .map(assembleTree(_, childrenByParentId, directCounts))

    folder.copy(
      children = children,
      numOfChildren = children.length,
      numOfAssets = directCounts.getOrElse(folder.persistedId, 0) + children.map(_.numOfAssets).sum)

  def isRootFolder(id: String): Boolean =
    id == contextRepo.rootFolderId

  /** Get children for the parent given, but only a single level - non-recursive */
  def getChildren(rootId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getChildren(rootId)
    }

  def getChildrenRecursive(rootId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getChildrenRecursive(rootId)
    }

  def getAncestors(folderId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getAncestors(folderId)
    }

  override def deleteById(id: String): Int =
    throw NotImplementedError("Deleting a folder is handled by the library service")

  override def deleteByQuery(query: Query): Int =
    throw NotImplementedError("Cannot delete folders by query")

  /** Move a folder from one parent to another */
  def move(folderBeingMovedId: String, destFolderId: String): (Folder, Folder) =
    if isRootFolder(folderBeingMovedId) then throw IllegalOperationException("Cannot move the root folder")

    logger.debug(s"Moving folder $folderBeingMovedId to $destFolderId")

    // cannot move into itself
    if folderBeingMovedId == destFolderId then
      throw IllegalOperationException(s"Cannot move a folder into itself. ID: $folderBeingMovedId")

    txManager.withTransaction {
      // cannot move into own child
      if getAncestors(destFolderId).map(_.persistedId).contains(folderBeingMovedId) then
        throw DuplicateException(Some("Cannot move parent folder into a child node"))

      var destFolder: Option[Folder] = None
      // check that the destination folder exists
      try destFolder = Some(getById(destFolderId))
      catch case _: NotFoundException => throw ValidationException(s"Destination folder ID $destFolderId does not exist")

      val folderBeingMoved: Folder = getById(folderBeingMovedId)

      val data = Map(
        FieldConst.Folder.PARENT_ID -> destFolderId,
        FieldConst.Folder.NAME -> folderBeingMoved.name
      )
      updateById(folderBeingMovedId, data)

      Tuple2(folderBeingMoved, destFolder.get)
    }

  def rename(folderId: String, newName: String): Folder =
    if isRootFolder(folderId) then throw IllegalOperationException("Cannot rename the root folder")

    txManager.withTransaction {
      val folder: Folder = getById(folderId)

      val folderForUpdate: Folder = folder.copy(
        name = newName
      )

      val data = Map(FieldConst.Folder.NAME -> newName, FieldConst.Folder.NAME_LC -> newName.toLowerCase)

      updateById(folderId, data)
      folderForUpdate
    }

  def setRecycledProp(folder: Folder, isRecycled: Boolean): Unit =
    if folder.isRecycled == isRecycled then return

    txManager.withTransaction {
      logger.info(s"Setting folder [${folder.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(folder.persistedId, Map(FieldConst.Folder.IS_RECYCLED -> isRecycled))
    }
