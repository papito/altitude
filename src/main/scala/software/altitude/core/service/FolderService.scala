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
      val wCounts = addAssetCount(dao.query(q).records)
      val wPaths = wCounts
      wPaths
    }
  }

  def isRootFolder(id: String): Boolean =
    id == contextRepo.rootFolderId

  /** Given a list of folders, calculate and append asset counts */
  private def addAssetCount(folders: List[JsObject]): List[JsObject] = {
    folders.map {
      json =>
        val id = (json \ FieldConst.ID).as[String]
        val assetCount = flatChildren(id, folders).toSeq.map(_.numOfAssets).sum

        json ++ Json.obj(FieldConst.Folder.NUM_OF_ASSETS -> JsNumber(assetCount))
    }
  }

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

  /** Get the folder hierarchy, with an arbitrary folder ID as the root ID to start with. */
  def hierarchy(rootId: Option[String] = None, allRepoFolders: List[JsObject] = List()): List[Folder] = {

    val _rootId = if (rootId.isDefined) rootId.get else contextRepo.rootFolderId

    txManager.asReadOnly {
      val repoFolders = repositoryFolders(allRepoFolders)

      val rootEl = repoFolders.find(json => (json \ FieldConst.ID).as[String] == _rootId)

      if (isRootFolder(_rootId) || rootEl.isDefined) {
        children(_rootId, repoFolders)
      } else {
        throw NotFoundException(s"Cannot get hierarchy. Root folder $rootId does not exist")
      }
    }
  }

  /** Folder ancestry as a list, top->bottom = root->this folder. */
  def pathComponents(folderId: String): List[Folder] = {
    // short-circuit for root folder
    if (isRootFolder(folderId)) {
      return List[Folder]()
    }

    txManager.asReadOnly[List[Folder]] {
      val repoFolders = repositoryFolders()

      val folderEl = repoFolders.find(json => (json \ FieldConst.ID).as[String] == folderId)
      val folder: Folder = if (folderEl.isDefined) {
        Folder.fromJson(folderEl.get)
      } else {
        throw NotFoundException(s"Folder with ID '$folderId' not found")
      }

      val parents = findParents(folderId = folderId, allRepoFolders = repoFolders)

      val rootFolder = Folder(
        id = Some(contextRepo.rootFolderId),
        parentId = contextRepo.rootFolderId,
        name = FieldConst.Folder.Name.ROOT
      )

      List(rootFolder) ::: (folder :: parents).reverse
    }
  }

  /** Get children for the root given, but only a single level - non-recursive */
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
   * Returns a nested list of children for a given folder. Not a flat list! Subsequent children are in each folders' "children"
   * element.
   */
  private def children(parentId: String, allRepoFolders: List[JsObject]): List[Folder] = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val immediateChildren = this.immediateChildren(parentId, repoFolders)

    val folders = for (folder <- immediateChildren.map(_.toJson)) yield {
      val id: String = (folder \ FieldConst.ID).as[String]
      val name = (folder \ FieldConst.Folder.NAME).as[String]
      val assetCount = (folder \ FieldConst.Folder.NUM_OF_ASSETS).as[Int]

      Folder(id = Some(id), name = name, parentId = parentId, children = this.children(id, repoFolders), numOfAssets = assetCount)
    }

    folders.sortBy(_.nameLowercase)
  }

  /** Returns a list of parents for a given folder ID. The list goes from closest parent to farthest. */
  private def findParents(folderId: String, allRepoFolders: List[JsObject]): List[Folder] = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val folderEl = repoFolders.find(json => (json \ FieldConst.ID).as[String] == folderId)

    val parentId = if (folderEl.isDefined) {
      (folderEl.get \ FieldConst.Folder.PARENT_ID).as[String]
    } else {
      throw NotFoundException(s"Folder with ID '$folderId' not found")
    }

    val parentElements = repoFolders.filter(json => (json \ FieldConst.ID).as[String] == parentId)

    if (parentElements.nonEmpty) {
      val folder = Folder.fromJson(parentElements.head)
      List(folder) ++ findParents(folderId = folder.persistedId, repoFolders)
    } else {
      List()
    }
  }

  /** Get a folder by ID, but with the number of assets in it as well - including child folders */
  def getByIdWithChildAssetCounts(id: String, allRepoFolders: List[JsObject] = List()): JsObject = {
    val repoFolders = repositoryFolders(allRepoFolders)
    val matching = repoFolders.filter(j => (j \ FieldConst.ID).asOpt[String].contains(id))

    if (matching.isEmpty) {
      throw NotFoundException(s"Base.ID $id not found")
    }

    matching.head
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

  /** Returns flat children as a set of Folder objects */
  private def flatChildren(parentId: String, all: List[JsObject], depth: Int = 0): Set[Folder] = {
    if (isRootFolder(parentId)) {
      return Set()
    }

    val parentElements = all.filter(json => (json \ FieldConst.ID).as[String] == parentId)

    if (parentElements.isEmpty) {
      return Set()
    }

    val parentElement: Folder = parentElements.head
    val childElements = all.filter(j => (j \ FieldConst.Folder.PARENT_ID).asOpt[String].contains(parentId))

    // recursively combine with the result of deeper child levels + this one (depth-first)
    (parentElement :: childElements.foldLeft(List[Folder]()) {
      (res, json) =>
        val folder: Folder = json
        res ++ flatChildren(folder.persistedId, all, depth + 1)
    }).toSet
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

      incrChildCount(destFolderId)
      // this is still the old parent as the model is immutable
      decrChildCount(folderBeingMoved.parentId)

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

  def incrAssetCount(folderId: String, count: Int = 1): Unit = {
    logger.debug(s"Incrementing folder $folderId asset count by $count")

    txManager.withTransaction {
      dao.increment(folderId, FieldConst.Folder.NUM_OF_ASSETS, count)
    }
  }

  def decrAssetCount(folderId: String, count: Int = 1): Unit = {
    logger.debug(s"Decrementing folder $folderId asset count by $count")

    txManager.withTransaction {
      dao.decrement(folderId, FieldConst.Folder.NUM_OF_ASSETS, count)
    }
  }

  def incrChildCount(folderId: String, count: Int = 1): Unit = {
    logger.debug(s"Incrementing folder $folderId child folder count by $count")

    txManager.withTransaction {
      dao.increment(folderId, FieldConst.Folder.NUM_OF_CHILDREN, count)
    }
  }

  def decrChildCount(folderId: String, count: Int = 1): Unit = {
    logger.debug(s"Decrementing folder $folderId child folder count by $count")

    txManager.withTransaction {
      dao.decrement(folderId, FieldConst.Folder.NUM_OF_CHILDREN, count)
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
