package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.Validators.ModelDataValidator
import software.altitude.core.dao.FolderDao
import software.altitude.core.models.Folder
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.{Const => C, _}

object FolderService {
  class FolderValidator
    extends ModelDataValidator(
      required = Some(List(C.Folder.NAME, C.Folder.PARENT_ID)))
}

class FolderService(val app: Altitude) extends BaseService[Folder] {
  private final val log = LoggerFactory.getLogger(getClass)
  override protected val dao: FolderDao = app.injector.instance[FolderDao]

  override final val cleaner: Some[Cleaners.Cleaner] = Some(
    Cleaners.Cleaner(
      trim = Some(List(C.Folder.NAME, C.Folder.PARENT_ID))))

  override final val validator: Some[FolderService.FolderValidator] = Some(new FolderService.FolderValidator)

  /**
   * Add a new folder - THIS SHOULD NOT BE USED DIRECTLY. Use <code>addFolder</code>
   */
  override def add(folder: Folder, queryForDup: Option[Query] = None)
                  (implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject = {
    if (isSystemFolder(Some(folder.parentId))) {
      throw IllegalOperationException("Cannot add a child to a system folder")
    }

    txManager.withTransaction[JsObject] {
      val dupQuery = new Query(params = Map(
        C.Folder.PARENT_ID -> folder.parentId,
        C.Folder.NAME_LC -> folder.nameLowercase))

      try {
        val addedFolder = addPath(super.add(folder, Some(dupQuery)))
        require(addedFolder.path.nonEmpty)
        addedFolder
      } catch {
        case _: DuplicateException => {
          val ex = ValidationException()
          ex.errors += (C.Folder.NAME -> C.Msg.Err.DUPLICATE)
          throw ex
        }
      }
    }
  }

  /**
   * Return ALL folders - system and non-system
   */
  override def getAll(implicit ctx: Context, txId: TransactionId = new TransactionId): List[JsObject] = {
    txManager.asReadOnly[List[JsObject]] {
     val wCounts = addAssetCount(dao.getAll)
     val wPaths = wCounts
     wPaths
    }
  }

  /**
   * Get root folder object for this repository.
   */
  def rootFolder(implicit ctx: Context, txId: TransactionId = new TransactionId): Folder = Folder(
    id = Some(ctx.repo.rootFolderId),
    parentId = ctx.repo.rootFolderId,
    name = C.Folder.Name.ROOT,
    path = Some(app.service.fileStore.sortedFolderPath)
  )

  /**
   * Get the Triage folder for this repository
   */
  def triageFolder(implicit ctx: Context, txId: TransactionId = new TransactionId): Folder = Folder(
    id = Some(ctx.repo.triageFolderId),
    parentId = ctx.repo.rootFolderId,
    name = C.Folder.Name.TRIAGE,
    path = Some(app.service.fileStore.triageFolderPath)
  )

  /**
   * Get all systems folders - the ones the user cannot alter
   */
  def systemFolders(implicit ctx: Context, txId: TransactionId = new TransactionId): List[Folder] =
    List(triageFolder)

  def isRootFolder(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): Boolean =
    id == ctx.repo.rootFolderId

  def isTriageFolder(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): Boolean =
    id == ctx.repo.triageFolderId

  def isSystemFolder(id: Option[String])(implicit ctx: Context, txId: TransactionId = new TransactionId): Boolean =
    systemFolders.exists(_.id == id)

  /**
   * Given a list of folders, calculate and append asset counts
   */
  private def addAssetCount(folders: List[JsObject])
                           (implicit ctx: Context): List[JsObject] = {
    folders.map { json =>
      val id = (json \ C.Base.ID).as[String]
      val assetCount = flatChildren(id, folders).toSeq.map(_.numOfAssets).sum

      json ++ Json.obj(C.Folder.NUM_OF_ASSETS -> JsNumber(assetCount))
    }
  }

  private def addPath(folder: Folder)(implicit ctx: Context, txId: TransactionId): Folder = {
    if (isRootFolder(folder.id.get)) {
      return rootFolder
    }

    if (isTriageFolder(folder.id.get)) {
      return triageFolder
    }

    txManager.asReadOnly[Folder] {
      val _pathComponents = pathComponents(folder.id.get).map(_.name)
      val relPath = app.service.fileStore.assemblePath(_pathComponents)
      folder.modify(C.Folder.PATH -> relPath)
    }
  }

  /**
   * Return all NON-system folders.
   */
  def repositoryFolders(allRepoFolders: List[JsObject] = List())
                      (implicit ctx: Context, txId: TransactionId = new TransactionId): List[JsObject] = {
    txManager.asReadOnly[List[JsObject]] {
      val _all = if (allRepoFolders.isEmpty) getAll else allRepoFolders

      _all.filter(json => {
        val id = (json \ C.Base.ID).asOpt[String]
        !isSystemFolder(id) && !isRootFolder(id.get)
      })
    }
  }

  /**
   * Get the folder hierarchy, with an arbitrary folder ID as the root ID to start with.
   */
  def hierarchy(rootId: Option[String] = None, allRepoFolders: List[JsObject] = List())
               (implicit ctx: Context, txId: TransactionId = new TransactionId): List[Folder] = {

    val _rootId = if (rootId.isDefined) rootId.get else ctx.repo.rootFolderId

    txManager.asReadOnly {
      val repoFolders = repositoryFolders(allRepoFolders)

      val rootEl = repoFolders.find(json => (json \ C.Base.ID).as[String] == _rootId)

      if (isRootFolder(_rootId) || rootEl.isDefined) {
        children(_rootId, repoFolders)
      }
      else {
        throw NotFoundException(s"Cannot get hierarchy. Root folder $rootId does not exist")
      }
    }
  }

  /**
   * Folder ancestry as a list, top->bottom = root->this folder.
   */
  def pathComponents(folderId: String)
                    (implicit ctx: Context, txId: TransactionId = new TransactionId): List[Folder] = {
    // short-circuit for root folder
    if (isRootFolder(folderId)) {
      return List[Folder]()
    }

    txManager.asReadOnly[List[Folder]] {
      val repoFolders = repositoryFolders()

      val folderEl = repoFolders.find(json => (json \ C.Base.ID).as[String] == folderId)
      val folder: Folder = if (folderEl.isDefined) {
        Folder.fromJson(folderEl.get)
      } else {
        throw NotFoundException(s"Folder with ID '$folderId' not found")
      }

      val parents = findParents(folderId = folderId, allRepoFolders = repoFolders)

      List(rootFolder) ::: (folder :: parents).reverse
    }
  }

  /**
   * Get children for the root given, but only a single level - non-recursive
   */
  def immediateChildren(rootId: String, allRepoFolders: List[JsObject] = List())
                       (implicit ctx: Context, txId: TransactionId = new TransactionId): List[Folder] = {

    txManager.asReadOnly[List[Folder]] {
      val repoFolders = repositoryFolders(allRepoFolders)

      repoFolders.filter(json => {
          val id = (json \ C.Base.ID).as[String]
          val parentId = (json \ C.Folder.PARENT_ID).as[String]
          parentId == rootId && !isSystemFolder(Some(id))
        })
        .map{json => Folder.fromJson(json)}
        .sortBy(_.nameLowercase)
    }
  }

  override def deleteById(id: String)
                         (implicit ctx: Context, txId: TransactionId): Int = {
    dao.deleteById(id)
  }

  override def deleteByQuery(query: Query)
                            (implicit ctx: Context, txId: TransactionId = new TransactionId): Int = {
    throw new NotImplementedError("Cannot delete folders by query")
  }

  /**
   * Returns a nested list of children for a given folder.
   * Not a flat list! Subsequent children are in each folders' "children" element.
    */
  private def children(parentId: String, allRepoFolders: List[JsObject])
                      (implicit ctx: Context, txId: TransactionId): List[Folder] = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val immediateChildren = this.immediateChildren(parentId, repoFolders)

    val folders = for (folder <- immediateChildren.map(_.toJson)) yield  {
      val id: String = (folder \ C.Base.ID).as[String]
      val name = (folder \ C.Folder.NAME).as[String]
      val path = (folder \ C.Folder.PATH).as[String]
      val assetCount = (folder \ C.Folder.NUM_OF_ASSETS).as[Int]

      Folder(
        id = Some(id),
        name = name,
        path = Some(path),
        parentId = parentId,
        children = this.children(id, repoFolders),
        numOfAssets = assetCount)
    }

    folders.sortBy(_.nameLowercase)
  }

  /**
   * Returns a list of parents for a given folder ID.
   * The list goes from closest parent to farthest.
   */
  private def findParents(folderId: String, allRepoFolders: List[JsObject])
                         (implicit ctx: Context, txId: TransactionId): List[Folder] = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val folderEl = repoFolders.find(json => (json \ C.Base.ID).as[String] == folderId)

    val parentId = if (folderEl.isDefined) {
      (folderEl.get \ C.Folder.PARENT_ID).as[String]
    } else {
      throw NotFoundException(s"Folder with ID '$folderId' not found")
    }

    val parentElements = repoFolders filter (json => (json \ C.Base.ID).as[String] == parentId)

    if (parentElements.nonEmpty) {
      val folder = Folder.fromJson(parentElements.head)
      List(folder) ++ findParents(folderId = folder.id.get, repoFolders)
    }
    else {
      List()
    }
  }

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject = {
    txManager.asReadOnly[JsObject] {
      val folder: Folder = if (isRootFolder(id)) rootFolder else super.getById(id)
      val ret = addPath(folder)

      require(ret.path.isDefined)
      require(ret.path.get.nonEmpty)
      ret
    }
  }

  /**
   * Get a folder by ID, but with the number of assets in it as well - including child folders
   */
  def getByIdWithChildAssetCounts(id: String, allRepoFolders: List[JsObject] = List())
                                 (implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val matching = repoFolders.filter(j => (j \ C.Base.ID).asOpt[String].contains(id))

    if (matching.isEmpty) {
      throw NotFoundException(s"Base.ID $id not found")
    }

    matching.head
  }

  /**
   * Return all folders, with their depths, as a flat list, for a given parent folder.
   * Specifically, returns a flat list of tuples, where the first element is the depth,
   * relative to parent folder, and the second element is the folder ID.
   */
  def flatChildrenIdsWithDepths(parentId: String, allRepoFolders: List[JsObject] = List(), depth: Int = 0)
                     (implicit ctx: Context, txId: TransactionId = new TransactionId): List[(Int, String)] = {
    val repoFolders = repositoryFolders(allRepoFolders)

    val childElements = repoFolders.filter(j => (j \ C.Folder.PARENT_ID).asOpt[String].contains(parentId))

    // recursively combine with the result of deeper child levels + this one (depth-first)
    (depth, parentId) :: childElements.foldLeft(List[(Int, String)]()) { (res, json) =>
      val folderId = (json \ C.Base.ID).as[String]
      res ++ flatChildrenIdsWithDepths(folderId, repoFolders, depth + 1)}
  }

  /**
   * Returns a unique set of folder IDs for one OR more folder ids.
   * The difference between the other method is that folder depths are not returned.
   * It's a "raw" list of folder ids.
   */
  def flatChildrenIds(parentIds: Set[String], allRepoFolders: List[JsObject] = List())
                     (implicit ctx: Context, txId: TransactionId = new TransactionId): Set[String] =
    parentIds.foldLeft(Set[String]()) {(s, id) => {
      s ++ app.service.folder.flatChildrenIdsWithDepths(parentId = id, allRepoFolders = allRepoFolders).map(_._2).toSet
    }}

  /**
   * Returns flat children as a set of Folder objects
   */
  def flatChildren(parentId: String, all: List[JsObject], depth: Int = 0)
                  (implicit ctx: Context): Set[Folder] = {
    if (isSystemFolder(Some(parentId)) || isRootFolder(parentId)) {
      return Set()
    }

    val parentElements = all filter (json => (json \ C.Base.ID).as[String] == parentId)

    if (parentElements.isEmpty) {
      return Set()
    }

    val parentElement: Folder = parentElements.head
    val childElements = all.filter(j => (j \ C.Folder.PARENT_ID).asOpt[String].contains(parentId))

    // recursively combine with the result of deeper child levels + this one (depth-first)
    (parentElement :: childElements.foldLeft(List[Folder]()) {(res, json) =>
      val folder: Folder = json
      res ++ flatChildren(folder.id.get, all, depth + 1)}).toSet
  }

  /**
   * Move a folder from one parent to another
   */
  def move(folderBeingMovedId: String, destFolderId: String)
          (implicit ctx: Context, txId: TransactionId = new TransactionId): (Folder, Folder) = {

    if (isRootFolder(folderBeingMovedId)) {
      throw IllegalOperationException("Cannot move the root folder")
    }

    log.debug(s"Moving folder $folderBeingMovedId to $destFolderId")

    // cannot move into itself
    if (folderBeingMovedId == destFolderId) {
      throw IllegalOperationException(s"Cannot move a folder into itself. ID: $folderBeingMovedId")
    }

    txManager.withTransaction {
      // cannot move into own child
      if (flatChildrenIdsWithDepths(folderBeingMovedId, repositoryFolders()).map(_._2).contains(destFolderId)) {
        throw IllegalOperationException(
          s"Cannot move a folder into own child. $destFolderId ID in $folderBeingMovedId path")
      }

      var destFolder: Option[Folder] = None
      // check that the destination folder exists
      try {
        destFolder = Some(getById(destFolderId))
      } catch {
        case _: NotFoundException => throw ValidationException(s"Destination folder ID $destFolderId does not exist")
      }

      val folderBeingMoved: Folder = getById(folderBeingMovedId)

      // destination parent cannot have folder by the same name
      val dupQuery = new Query(params = Map(
        C.Folder.PARENT_ID -> destFolderId,
        C.Folder.NAME_LC -> folderBeingMoved.nameLowercase))

      val folderForUpdate = Folder(
        parentId = destFolderId,
        name = folderBeingMoved.name,
        path = Some(app.service.fileStore.getFolderPath(folderBeingMoved.name, destFolderId)))

      try {
        updateById(folderBeingMovedId, folderForUpdate, List(C.Folder.PARENT_ID), Some(dupQuery))
      } catch {
        case _: DuplicateException => throw ValidationException(
          s"Cannot move to '${destFolder.get.name}' as a folder by this name already exists")
      }

      Tuple2(folderBeingMoved, destFolder.get)
    }
  }

  def rename(folderId: String, newName: String)
            (implicit ctx: Context, txId: TransactionId = new TransactionId): Folder = {
    if (isRootFolder(folderId)) {
      throw IllegalOperationException("Cannot rename the root folder")
    }

    txManager.withTransaction {
      val folder: Folder = getById(folderId)

      // new folder name cannot match existing one
      val dupQuery = new Query(params = Map(
        C.Folder.PARENT_ID -> folder.parentId,
        C.Folder.NAME -> newName))

      try {
        val folderForUpdate: Folder = folder.modify(
          C.Folder.NAME -> newName,
          C.Folder.PATH -> app.service.fileStore.getFolderPath(newName, folder.parentId)
        )

        updateById(folderId, folderForUpdate, List(C.Folder.NAME, C.Folder.NAME_LC), Some(dupQuery))
        folderForUpdate
      } catch {
        case _: DuplicateException =>
          val ex = ValidationException()
          ex.errors += C.Folder.NAME -> C.Msg.Err.DUPLICATE
          throw ex
      }
    }
  }

  /**
   * Increase a folder's asset count.
   */
  def incrAssetCount(folderId: String, count: Int = 1)
                    (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    log.debug(s"Incrementing folder $folderId count by $count")

    txManager.withTransaction {
      dao.increment(folderId, C.Folder.NUM_OF_ASSETS, count)
    }
  }

  /**
   * Decrease a folder's asset count.
   */
  def decrAssetCount(folderId: String, count: Int = 1)
                    (implicit ctx: Context, txId: TransactionId = new TransactionId): Unit = {
    log.debug(s"Decrementing folder $folderId count by $count")

    txManager.withTransaction {
      dao.decrement(folderId, C.Folder.NUM_OF_ASSETS, count)
    }
  }

  /**
   * Get the id -> folder map of system folders
   */
  def sysFoldersByIdMap(allRepoFolders: List[JsObject] = List())
                   (implicit ctx: Context, txId: TransactionId = new TransactionId): Map[String, Folder] = {

    txManager.asReadOnly[Map[String, Folder]] {
      val allSysFolders = if (allRepoFolders.isEmpty) {
        val sysFolderIds: Set[String] = systemFolders.map(_.id.get).toSet
        dao.getByIds(sysFolderIds)
      }
      else {
        allRepoFolders.filter(json => {
          val id = (json \ C.Base.ID).asOpt[String]
          isSystemFolder(id)
        })
      }

      allSysFolders.map(j => {
        val id = (j \ C.Base.ID).as[String]
        val folder = Folder.fromJson(j)
        id -> folder}
      ).toMap
    }
  }

  def setRecycledProp(folder: Folder, isRecycled: Boolean)
                     (implicit ctx: Context, txId: TransactionId): Unit = {
    if (folder.isRecycled == isRecycled) {
      return
    }

    txManager.withTransaction {
      log.info(s"Setting folder [${folder.id.get}] recycled flag to [$isRecycled]")
      val updatedFolder = folder.modify(C.Folder.IS_RECYCLED -> isRecycled)
      dao.updateById(folder.id.get, data = updatedFolder, fields = List(C.Folder.IS_RECYCLED))
    }
  }

}
