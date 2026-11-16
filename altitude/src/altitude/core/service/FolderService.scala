package altitude.core.service

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.dao.FolderDao
import altitude.core.models.Folder
import altitude.core.util.Query

class FolderService(val app: Altitude) extends BaseService[Folder]:

  override protected val dao: FolderDao = app.DAO.folder

  /**
   * Adds the folder under `parentId`, or under the repository root. It waits its turn on the name (`FolderDao.lockName`) behind
   * an import creating that folder, and is then refused as a duplicate instead of failing the import's file. An add, a [[rename]]
   * and a [[move]] each take one such lock and [[ensurePath]] takes them parent first, so they cannot deadlock.
   */
  def add(name: String, parentId: Option[String] = None): Folder =
    txManager.withTransaction {
      val _parentId = parentId.map(getById(_).persistedId).getOrElse(RequestContext.getRepository.rootFolderId)
      val folder = named(name, _parentId)
      dao.lockName(_parentId, folder.nameKey)
      app.service.folder.add(folder)
    }

  /**
   * The folder at the end of the path `names` under `parentId` (the parent itself for none). Each folder is found by its name
   * key: a live one is reused, a recycled one restored alone (as restoring an asset restores the folders above it), a missing one
   * added. Creators take turns on a name, so of two imports missing a folder the second finds the first's. A refused name or an
   * unknown parent rolls the whole path back.
   */
  def ensurePath(parentId: String, names: Seq[String]): Folder =
    txManager.withTransaction {
      names.foldLeft(getById(parentId))(ensureChild)
    }

  /**
   * Takes the caller's turn, until its transaction ends, among imports sorting a file into the folder tree and folder deletes.
   * Each takes it before reading, so the second sees what the first committed: a completion finds its destination in the Trash,
   * or a delete recycles what the completion put there.
   */
  def lockTree(): Unit =
    txManager.withTransaction {
      dao.lockTree()
    }

  /** The folder of the name under `parent`: the one there, restored if it was recycled, or a new one */
  private def ensureChild(parent: Folder, name: String): Folder =
    val wanted = named(name, parent.persistedId)

    // Before the read: a creator that waits here reads the folder the one before it committed
    dao.lockName(parent.persistedId, wanted.nameKey)
    dao.getChild(parent.persistedId, wanted.nameKey) match
      case Some(existing) =>
        if existing.isRecycled then
          logger.info(s"Restoring recycled folder [${existing.persistedId}] \"${existing.name}\", which an import sorts into")
          setRecycledProp(existing, isRecycled = false)
        existing.copy(isRecycled = false)
      case None => add(wanted)

  /** A folder to add under `parentId`, its name trimmed and validated */
  private def named(name: String, parentId: String): Folder = Folder(name = validName(name.trim), parentId = parentId)

  private def validName(name: String): String =
    Validators.validName(name, Api.Field.Folder.NAME, Api.Constraints.MAX_FOLDER_NAME_LENGTH)

  override def add(folder: Folder): Folder =
    txManager.withTransaction {
      logger.debug(s"Adding folder [${folder.name}] under [${folder.parentId}]")
      super.add(folder)
    }

  def getAll: List[Folder] =
    txManager.asReadOnly {
      dao.query(new Query()).records
    }

  /**
   * The live folder tree from the repository root, children sorted by name, each node with `numOfChildren` and the recursive
   * `numOfAssets`; two queries, one for the folders and one counting assets by folder.
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
      val directCounts = app.service.search.countByFolder()

      assembleTree(rootFolder, childrenByParentId, directCounts)
    }

  /** Post-order pass: a folder's asset count is its own assets plus those of its assembled children */
  private def assembleTree(
      folder: Folder,
      childrenByParentId: Map[String, List[Folder]],
      directCounts: Map[String, Int]): Folder =
    val children = childrenByParentId
      .getOrElse(folder.persistedId, Nil)
      .sortBy(_.nameKey)
      .map(assembleTree(_, childrenByParentId, directCounts))

    folder.copy(
      children = children,
      numOfChildren = children.length,
      numOfAssets = directCounts.getOrElse(folder.persistedId, 0) + children.map(_.numOfAssets).sum)

  def isRootFolder(id: String): Boolean =
    id == contextRepo.rootFolderId

  /** The folder's direct children only */
  def getChildren(rootId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getChildren(rootId)
    }

  def getChildrenRecursive(rootId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getChildrenRecursive(rootId)
    }

  /** The folders above `folderId`, the top-level one first and its parent last; the repository root is not among them */
  def getAncestors(folderId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getAncestors(folderId)
    }

  /**
   * The folder's path, the top-level folder first and the folder itself last, the repository root not among them unless it is the
   * folder; none for an ID that names no folder of the repository
   */
  def getPath(folderId: String): List[Folder] =
    txManager.asReadOnly {
      dao.getPath(folderId)
    }

  override def deleteById(id: String): Int =
    throw NotImplementedError("Deleting a folder is handled by the library service")

  override def deleteByQuery(query: Query): Int =
    throw NotImplementedError("Cannot delete folders by query")

  def move(folderBeingMovedId: String, destFolderId: String): (Folder, Folder) =
    if isRootFolder(folderBeingMovedId) then throw IllegalOperationException("Cannot move the root folder")

    logger.debug(s"Moving folder $folderBeingMovedId to $destFolderId")

    if folderBeingMovedId == destFolderId then
      throw IllegalOperationException(s"Cannot move a folder into itself. ID: $folderBeingMovedId")

    txManager.withTransaction {
      if getAncestors(destFolderId).map(_.persistedId).contains(folderBeingMovedId) then
        throw IllegalOperationException("Cannot move a folder into its own descendant")

      var destFolder: Option[Folder] = None
      try destFolder = Some(getById(destFolderId))
      catch case _: NotFoundException => throw ValidationException(s"Destination folder ID $destFolderId does not exist")

      val folderBeingMoved: Folder = getById(folderBeingMovedId)

      // Waits its turn on the name in the destination, as an add does
      dao.lockName(destFolderId, folderBeingMoved.nameKey)

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
      logger.debug(s"Renaming folder [${folder.name}] to [$newName]")

      val folderForUpdate: Folder = folder.copy(name = validName(newName))

      // Waits its turn on the new name beside it, as an add does
      dao.lockName(folder.parentId, folderForUpdate.nameKey)

      val data = Map(FieldConst.Folder.NAME -> folderForUpdate.name, FieldConst.Folder.NAME_KEY -> folderForUpdate.nameKey)

      updateById(folderId, data)
      folderForUpdate
    }

  def setRecycledProp(folder: Folder, isRecycled: Boolean): Unit =
    if folder.isRecycled == isRecycled then return

    txManager.withTransaction {
      logger.debug(s"Setting folder [${folder.persistedId}] recycled flag to [$isRecycled]")

      dao.updateById(folder.persistedId, Map(FieldConst.Folder.IS_RECYCLED -> isRecycled))
    }
