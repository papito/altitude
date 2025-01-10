package software.altitude.core.service
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import software.altitude.core.{ Const => C, _ }
import software.altitude.core.Altitude
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models._
import software.altitude.core.models.Folder
import software.altitude.core.pipeline.PipelineTypes.PipelineContext
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import software.altitude.core.pipeline.sinks.AssetSeqOutputSink
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.ImageUtil.makeImageThumbnail
import software.altitude.core.util.MurmurHash
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult

object LibraryService {
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set(
    "image",
    "x-none" // in test, this is used to force zero-length preview data
  )
}

class LibraryService(val app: Altitude) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val txManager: TransactionManager = app.txManager

  private val previewBoxSize: Int = app.config.getInt(C.Conf.PREVIEW_BOX_PIXELS)

  def checkMediaType(asset: Asset): Unit = {
    if (!LibraryService.SUPPORTED_MEDIA_TYPES.contains(asset.assetType.mediaType)) {
      throw UnsupportedMediaTypeException(asset)
    }
  }

  def convImportAsset2dataAsset(importAsset: ImportAsset): AssetWithData = {
    val assetType = app.service.metadataExtractor.detectAssetType(importAsset.data)

    val asset = Asset(
      userId = RequestContext.account.value.get.persistedId,
      fileName = importAsset.fileName,
      checksum = MurmurHash.hash32(importAsset.data),
      assetType = assetType,
      sizeBytes = importAsset.data.length,
      isTriaged = true,
      folderId = RequestContext.getRepository.rootFolderId
    )
    AssetWithData(asset, importAsset.data)
  }

  def addImportAsset(importAsset: ImportAsset): Asset = {
    logger.info(s"Importing asset '$importAsset'")
    val dataAssetIn = convImportAsset2dataAsset(importAsset)
    addAsset(dataAssetIn)
  }

  def addAsset(dataAsset: AssetWithData): Asset = {
    val pipelineContext = PipelineContext(RequestContext.getRepository, RequestContext.getAccount)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source.single((dataAsset, pipelineContext))
    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] = app.service.importPipeline.run(source, AssetSeqOutputSink())

    val result: Seq[TAssetOrInvalidWithContext] = Await.result(pipelineResFuture, Duration.Inf)

    result.head match {
      case (Left(assetOut), _) => assetOut
      case (Right(invalid), _) => throw invalid.cause.getOrElse(new Exception("Unknown error"))
    }
  }

  def deleteById(id: String): Unit = {
    throw new NotImplementedError
  }

  def getById(id: String): JsObject = {
    txManager.asReadOnly[JsObject] {
      app.service.asset.getById(id)
    }
  }

  def getByChecksum(checksum: Int): Option[Asset] = {
    txManager.asReadOnly[Option[Asset]] {
      val query = new Query(params = Map(FieldConst.Asset.CHECKSUM -> checksum)).withRepository()
      val existing = app.service.asset.query(query)
      if (existing.nonEmpty) Some(existing.records.head: Asset) else None
    }
  }

  def moveAssetToFolder(assetId: String, folderId: String): Asset = {
    txManager.withTransaction[Asset] {
      moveAssetsToFolder(Set(assetId), folderId)
      getById(assetId)
    }
  }

  def moveAssetsToFolder(assetIds: Set[String], destFolderId: String): Unit = {

    def move(asset: Asset): Unit = {
      // Cannot move to the same folder
      // Note that a recycled asset CAN be restored to its original folder
      if (!asset.isRecycled && asset.folderId == destFolderId) {
        return
      }

      app.service.stats.moveAsset(asset, destFolderId)

      /* Point the asset to the new folder.
         It may or may not be recycled or triaged, so we update it as neither unconditionally
         (saves us a separate update query)
       */
      val data = Map(
        FieldConst.Asset.FOLDER_ID -> destFolderId,
        FieldConst.Asset.IS_RECYCLED -> false,
        FieldConst.Asset.IS_TRIAGED -> false
      )

      app.service.asset.updateById(asset.persistedId, data)
    }

    txManager.withTransaction {
      // ensure the folder exists
      app.service.folder.getById(destFolderId)

      assetIds.foreach {
        assetId =>
          val asset: Asset = getById(assetId)

          move(asset)
      }
    }
  }

  def renameAsset(assetId: String, newFilename: String): Asset = {

    txManager.withTransaction[Asset] {
      val asset: Asset = getById(assetId)

      if (asset.isRecycled) {
        throw IllegalOperationException(s"Cannot rename a recycled asset: [$asset]")
      }

      val data = Map(
        FieldConst.Asset.FILENAME -> newFilename
      )
      app.service.asset.updateById(asset.persistedId, data)

      asset.copy(fileName = newFilename)
    }
  }

  private def genPreviewData(dataAsset: AssetWithData): Array[Byte] = {
    dataAsset.asset.assetType.mediaType match {
      case "image" =>
        makeImageThumbnail(dataAsset.data, previewBoxSize)
      case _ => new Array[Byte](0)
    }
  }

  def addPreview(dataAsset: AssetWithData): Option[MimedPreviewData] = {
    val previewData: Array[Byte] = genPreviewData(dataAsset)

    previewData.length match {
      case size if size > 0 =>
        val preview: MimedPreviewData = MimedPreviewData(assetId = dataAsset.asset.persistedId, data = previewData)

        app.service.fileStore.addPreview(preview)

        Some(preview)
      case _ => None
    }
  }

  def getPreview(assetId: String): MimedPreviewData = {
    app.service.fileStore.getPreviewById(assetId)
  }

  def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      val folderId = query.params.get(FieldConst.Asset.FOLDER_ID).asInstanceOf[Option[String]]

      val _query: Query = if (folderId.isDefined) {
        val allFolderIds = app.service.folder.flatChildrenIds(parentIds = Set(folderId.get))
        query.add(FieldConst.Asset.FOLDER_ID -> Query.IN(allFolderIds.asInstanceOf[Set[Any]]))
      } else {
        query
      }

      app.service.asset.query(_query.withRepository())
    }
  }

  def search(query: SearchQuery): SearchResult = {
    txManager.asReadOnly[SearchResult] {
      val _query: SearchQuery = if (query.folderIds.nonEmpty) {
        // create a new query, with the new folder set
        val allFolderIds = app.service.folder.flatChildrenIds(parentIds = query.folderIds)

        new SearchQuery(text = query.text, folderIds = allFolderIds, params = query.params, rpp = query.rpp, page = query.page)
      } else {
        query
      }

      app.service.search.search(_query)
    }
  }

  def queryRecycled(query: Query): QueryResult = {
    app.service.asset.queryRecycled(query)
  }

  def queryAll(query: Query): QueryResult = {
    app.service.asset.queryAll(query)
  }

  def addFolder(name: String, parentId: Option[String] = None): Folder = {
    txManager.withTransaction[JsObject] {
      val _parentId = if (parentId.isDefined) parentId.get else RequestContext.getRepository.rootFolderId
      val folder = Folder(name = name.trim, parentId = _parentId)
      val addedFolder: Folder = app.service.folder.add(folder)

      app.service.folder.incrChildCount(_parentId)

      addedFolder
    }
  }

  /**
   * Delete a folder by ID, including its children. Does not allow deleting the root folder, or any system folders.
   *
   * Recycle all the assets in the tree.
   */
  def deleteFolderById(id: String): Unit = {
    if (app.service.folder.isRootFolder(id)) {
      throw IllegalOperationException("Cannot delete the root folder")
    }

    txManager.withTransaction {
      val folder: Folder = app.service.folder.getById(id)

      logger.info(s"Deleting folder $folder")

      // get the list of tuples - (depth, id), with most-deep first
      val childrenAndDepths: List[(Int, String)] =
        app.service.folder.flatChildrenIdsWithDepths(id, app.service.folder.repositoryFolders()).sortBy(_._1).reverse
      /* ^^^ sort by depth */

      logger.trace(s"Folder children, deepest first: $childrenAndDepths")

      childrenAndDepths.foldLeft(0) {
        (assetCount: Int, f: (Int, String)) =>
          val folderId = f._2
          val childFolder: Folder = app.service.folder.getById(folderId)

          logger.trace(s"Deleting or recycling folder $f")

          // set all the assets as recycled
          val folderAssetsQuery = new Query(Map(FieldConst.Asset.FOLDER_ID -> folderId))

          val results: QueryResult = app.service.library.queryAll(folderAssetsQuery)

          logger.trace(s"Folder $folderId has ${results.total} assets")

          // OPTIMIZE: bulk deletions.
          results.records.foreach {
            record =>
              val asset: Asset = record

              /* We only want to recycle assets that are not recycled,
             however the query is for all assets, as we need to know if the folder
             has any asset references in the repository. This spares us a second
             query.
               */
              if (!asset.isRecycled) this.recycleAsset(asset.persistedId)
          }

          val treeAssetCount = assetCount + results.total

          logger.trace(s"Total assets in the tree of folder ${folder.persistedId}: $treeAssetCount")

          // delete folder if it has no assets, otherwise - recycle it
          if (treeAssetCount == 0) {
            logger.trace(s"DELETING (PURGING) folder $f")
            app.service.folder.deleteById(folderId)
          } else {
            logger.trace(s"RECYCLING folder $folderId")
            app.service.folder.setRecycledProp(childFolder, isRecycled = true)
          }

          treeAssetCount // accumulates total asset count for the next step in the fold
      }

      app.service.folder.decrChildCount(folder.parentId)
    }
  }

  def renameFolder(folderId: String, newName: String): Folder = {
    txManager.withTransaction[Folder] {
      val updatedFolder = app.service.folder.rename(folderId, newName)
      updatedFolder
    }
  }

  def moveFolder(folderBeingMovedId: String, destFolderId: String): Folder = {
    txManager.withTransaction[Folder] {
      val (movedFolder, _) = app.service.folder.move(folderBeingMovedId, destFolderId)
      movedFolder
    }
  }

  def restoreRecycledAsset(assetId: String): Asset = {
    txManager.withTransaction[Asset] {
      restoreRecycledAssets(Set(assetId))
      getById(assetId)
    }
  }

  def restoreRecycledAssets(assetIds: Set[String]): Unit = {
    logger.info(s"Restoring recycled assets [${assetIds.mkString(",")}]")

    assetIds.foreach {
      assetId =>
        logger.info(s"Restoring recycled asset [$assetId]")

        val asset: Asset = getById(assetId)
        val existing = getByChecksum(asset.checksum)

        if (existing.isDefined) {
          throw DuplicateException()
        }

        txManager.withTransaction {
          if (asset.isRecycled) {
            app.service.asset.setRecycledProp(asset, isRecycled = false)
            // OPTIMIZE: create a lookup cache for folders, to avoid querying for each asset
            val folder: Folder = app.service.folder.getById(asset.folderId)

            if (folder.isRecycled) {
              app.service.folder.setRecycledProp(folder = folder, isRecycled = false)
            }

            val restoredAsset: Asset = getById(assetId)
            app.service.stats.restoreAsset(restoredAsset)
          }
        }
    }
  }

  def recycleAsset(assetId: String): Asset = {
    txManager.withTransaction {
      recycleAssets(Set(assetId))
      getById(assetId)
    }
  }

  def recycleAssets(assetIds: Set[String]): Unit = {
    assetIds.foreach {
      assetId =>
        txManager.withTransaction {
          val asset: Asset = getById(assetId)
          app.service.asset.setRecycledProp(asset, isRecycled = true)
          app.service.stats.recycleAsset(asset.copy(isRecycled = true))
        }
    }
  }

  def addMetadataValue(assetId: String, fieldId: String, newValue: Any): Unit = {
    txManager.withTransaction {
      app.service.metadata.addFieldValue(assetId, fieldId, newValue.toString)
      val field: UserMetadataField = app.service.metadata.getFieldById(fieldId)
      val asset: Asset = getById(assetId)
      app.service.search.addMetadataValue(asset, field, newValue.toString)
    }
  }

  def deleteMetadataValue(assetId: String, valueId: String): Unit = {
    txManager.withTransaction {
      app.service.metadata.deleteFieldValue(assetId, valueId)
      val asset: Asset = getById(assetId)
      // OPTIMIZE: store value ID with search to delete in a targeted way
      app.service.search.reindexAsset(asset)
    }
  }

  def updateMetadataValue(assetId: String, valueId: String, newValue: Any): Unit = {

    txManager.withTransaction {
      app.service.metadata.updateFieldValue(assetId, valueId, newValue.toString)
      val asset: Asset = getById(assetId)
      // OPTIMIZE: store value ID with search to update in a more efficient way
      app.service.search.reindexAsset(asset)
    }
  }

  def pruneDanglingAssets(): Unit = {
    forEachRepository {
      repository =>
        logger.info(s"Pruning dangling assets. Repo: ${repository.name}")
        val danglingAssets = app.service.asset.getDanglingAssets

        if (danglingAssets.nonEmpty) {
          logger.warn(s"Found ${danglingAssets.size} dangling assets")
          danglingAssets.foreach(asset => logger.warn(s"Will prune: ${asset.persistedId} - ${asset.fileName}"))
        }
        app.service.asset.pruneDanglingAssets()
    }
  }

  def forEachRepository(operation: Repository => Unit): Unit = {
    txManager.withTransaction {
      val repositories = app.DAO.repository.getAll

      repositories.foreach {
        repository =>
          {
            RequestContext.repository.value = Some(repository)
            operation(repository)
            RequestContext.repository.value = None
          }
      }
    }
  }
}
