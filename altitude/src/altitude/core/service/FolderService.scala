package altitude.core.service

import java.text.BreakIterator
import java.util.Locale

import scala.util.Try

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.Api
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.dao.FolderDao
import altitude.core.models.Folder
import altitude.core.util.NameKey
import altitude.core.util.Query
import altitude.core.util.SortKey

class FolderService(val app: Altitude) extends BaseService[Folder]:

  override protected val dao: FolderDao = app.DAO.folder

  /**
   * Adds the folder under `parentId`, or under the repository root. A recycled folder of the name there is restored instead,
   * alone and under the name as given, as [[ensurePath]] restores one, so a deleted folder never keeps its name from a new one; a
   * live one is a duplicate. A parent in the Trash is refused (`IllegalOperationException`).
   *
   * It takes the tree's turn first ([[lockTree]]), so a delete of the parent at the same time either has recycled it by the read
   * or recycles the new folder with it. It then waits its turn on the name (`FolderDao.lockName`) behind an import creating that
   * folder, and is then refused as a duplicate instead of failing the import's file. An add, a [[rename]] and a [[move]] each
   * take one such lock, after the tree's, and [[ensurePath]] takes them parent first, so they cannot deadlock.
   */
  def add(name: String, parentId: Option[String] = None): Folder =
    txManager.withTransaction {
      dao.lockTree()
      val _parentId = parentId
        .map(id => requireLive(getById(id), C.Msg.Err.PARENT_FOLDER_IN_TRASH).persistedId)
        .getOrElse(RequestContext.getRepository.rootFolderId)
      val wanted = named(name, _parentId)
      dao.lockName(_parentId, wanted.nameKey)
      dao.getChild(_parentId, wanted.nameKey) match
        case Some(existing) if existing.isRecycled =>
          logger.info(
            s"Restoring recycled folder [${existing.persistedId}] \"${existing.name}\", which a folder of its name replaces")
          restore(existing, wanted.name)
        case Some(_) => throw DuplicateException()
        case None => app.service.folder.add(wanted)
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
   * Takes the caller's turn, until its transaction ends, among imports sorting a file into the folder tree, folder deletes, and
   * folder adds and moves. Each takes it before reading, so the second sees what the first committed: a completion, an add or a
   * move finds its destination in the Trash, a delete recycles what the first put there, and a move sees another's new parent.
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
          restore(existing, existing.name)
        else existing
      case None => add(wanted)

  /**
   * The recycled folder out of the Trash alone, its subfolders and assets left there, named `name` under the name's own key,
   * which a folder moved aside ([[takeName]]) gets back here
   */
  private def restore(folder: Folder, name: String): Folder =
    val restored = folder.copy(name = name, isRecycled = false)
    val data = Map(
      FieldConst.Folder.IS_RECYCLED -> false,
      FieldConst.Folder.NAME -> restored.name,
      FieldConst.Folder.NAME_KEY -> restored.nameKey)
    dao.updateById(folder.persistedId, data): Unit
    restored

  /**
   * Brings a recycled folder out of the Trash alone, as restoring an asset does for its folder and each folder above it; a live
   * folder is left as it is. A folder a rename or a move moved aside ([[takeName]]) gets its name back when no live sibling holds
   * it, moving a recycled holder aside in turn, and under a live one it comes back as the first free "name (2)", "name (3)" and
   * so on. It waits its turn on each name it reads, as an add does, and reads the folder again once it has the name's turn, so a
   * restore that committed meanwhile is what it sees. The caller takes the tree's turn first ([[lockTree]]), which orders
   * restores among themselves and with deletes, so their name locks never wait on each other in a cycle.
   */
  def restoreRecycled(folder: Folder): Folder =
    if !folder.isRecycled then return folder

    txManager.withTransaction {
      dao.lockName(folder.parentId, folder.nameKey)
      // A recycled folder's name changes only by coming out of the Trash, so the name locked is still its own when it is recycled
      val current = getById(folder.persistedId)
      if !current.isRecycled then current
      else
        val name = dao.getChild(current.parentId, current.nameKey) match
          case Some(holder) if holder.persistedId != current.persistedId && !holder.isRecycled =>
            freeNumberedName(current)
          case _ =>
            takeName(current.parentId, current.nameKey, current.persistedId)
            current.name
        logger.debug(s"Restoring recycled folder [${current.persistedId}] as [$name]")
        restore(current, name)
    }

  /**
   * Frees the name key under `parentId` for the folder `takerId` when a recycled sibling holds it: the sibling moves aside under
   * a key of its own ID that no name folds to (folded keys are lower case), keeping its name, so a deleted folder never keeps its
   * name from a live one and is still shown by its name in the Trash. The caller holds the name's lock.
   */
  private def takeName(parentId: String, nameKey: String, takerId: String): Unit =
    dao.getChild(parentId, nameKey).filter(holder => holder.isRecycled && holder.persistedId != takerId).foreach {
      holder =>
        logger.info(s"Moving recycled folder [${holder.persistedId}] \"${holder.name}\" aside: folder [$takerId] takes its name")
        dao.updateById(holder.persistedId, Map(FieldConst.Folder.NAME_KEY -> s"Recycled ${holder.persistedId}")): Unit
    }

  /**
   * The first of "name (2)", "name (3)" and so on that no other folder beside `folder` holds, live or recycled; each is locked as
   * it is read
   */
  private def freeNumberedName(folder: Folder): String =
    Iterator
      .from(2)
      .map(n => numbered(folder.name, n))
      .find {
        candidate =>
          val key = NameKey.of(candidate)
          dao.lockName(folder.parentId, key)
          dao.getChild(folder.parentId, key).forall(_.persistedId == folder.persistedId)
      }
      .get

  /**
   * `name` followed by " (n)", the name shortened by whole characters (grapheme clusters, so a combining mark never loses its
   * base) until the folder rule accepts it: the number can take a long name past its length or its key past
   * `NameKey.MAX_KEY_BYTES`
   */
  private def numbered(name: String, n: Int): String =
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundaries.setText(name)
    // From the whole name down to none of it; the number alone always passes
    Iterator
      .iterate(boundaries.last())(_ => boundaries.previous())
      .takeWhile(_ != BreakIterator.DONE)
      .flatMap(end => Try(validName(name.substring(0, end) + s" ($n)")).toOption)
      .next()

  /** The folder, or `IllegalOperationException` with `refusal` when it is in the Trash */
  private def requireLive(folder: Folder, refusal: String): Folder =
    if folder.isRecycled then throw IllegalOperationException(refusal)
    folder

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
      .sortBy(child => (SortKey.of(child.name), child.persistedId))
      .map(assembleTree(_, childrenByParentId, directCounts))

    folder.copy(
      children = children,
      numOfChildren = children.length,
      numOfAssets = directCounts.getOrElse(folder.persistedId, 0) + children.map(_.numOfAssets).sum)

  def isRootFolder(id: String): Boolean =
    id == contextRepo.rootFolderId

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

  /**
   * Moves the folder under `destFolderId`, refusing the root, a destination in the Trash and a move into the folder itself or
   * below it. It takes the tree's turn ([[lockTree]]) before any read, so of two moves at once the second checks its ancestors
   * against what the first committed, and no two moves commit a cycle; a delete of the destination at the same time is seen done
   * or recycles the moved folder with it.
   */
  def move(folderBeingMovedId: String, destFolderId: String): (Folder, Folder) =
    if isRootFolder(folderBeingMovedId) then throw IllegalOperationException("Cannot move the root folder")

    logger.debug(s"Moving folder $folderBeingMovedId to $destFolderId")

    if folderBeingMovedId == destFolderId then
      throw IllegalOperationException(s"Cannot move a folder into itself. ID: $folderBeingMovedId")

    txManager.withTransaction {
      dao.lockTree()

      val destFolder: Folder =
        try getById(destFolderId)
        catch case _: NotFoundException => throw ValidationException(s"Destination folder ID $destFolderId does not exist")
      requireLive(destFolder, "Cannot move a folder into a folder in the Trash")

      val folderBeingMoved: Folder = requireLive(getById(folderBeingMovedId), "Cannot move a folder in the Trash")

      if getAncestors(destFolderId).map(_.persistedId).contains(folderBeingMovedId) then
        throw IllegalOperationException("Cannot move a folder into its own descendant")

      // Waits its turn on the name in the destination, as an add does, and takes it from a recycled folder there
      dao.lockName(destFolderId, folderBeingMoved.nameKey)
      takeName(destFolderId, folderBeingMoved.nameKey, folderBeingMovedId)

      val data = Map(
        FieldConst.Folder.PARENT_ID -> destFolderId,
        FieldConst.Folder.NAME -> folderBeingMoved.name
      )
      updateById(folderBeingMovedId, data)

      Tuple2(folderBeingMoved, destFolder)
    }

  def rename(folderId: String, newName: String): Folder =
    if isRootFolder(folderId) then throw IllegalOperationException("Cannot rename the root folder")

    txManager.withTransaction {
      val folder: Folder = requireLive(getById(folderId), "Cannot rename a folder in the Trash")
      logger.debug(s"Renaming folder [${folder.name}] to [$newName]")

      val folderForUpdate: Folder = folder.copy(name = validName(newName))

      // Waits its turn on the new name beside it, as an add does, and takes it from a recycled sibling
      dao.lockName(folder.parentId, folderForUpdate.nameKey)
      takeName(folder.parentId, folderForUpdate.nameKey, folderId)

      val data = Map(FieldConst.Folder.NAME -> folderForUpdate.name, FieldConst.Folder.NAME_KEY -> folderForUpdate.nameKey)

      updateById(folderId, data)
      folderForUpdate
    }
