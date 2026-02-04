package altitude.core.service

import altitude.core.{ Const => _, _ }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models._
import altitude.core.models.Folder
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.transactions.TransactionManager
import altitude.core.util.MurmurHash
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * What is the difference between this and the AssetService?
 *
 * The LibraryService is a higher-level service that deals with the library as a whole, where methods touch multiple sub-services.
 * While it's not a strict separation, and sub-services can mingle on their own, anything that has to do with high-level library
 * concepts should be in this service.
 */
object LibraryService {
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set(
    "image",
    "x-none" // in test, this is used to force zero-length preview data
  )
}

class LibraryService(val app: Altitude) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val txManager: TransactionManager = app.txManager

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
      folderId = ""
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

  def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      val folderId = query.params.get(FieldConst.Asset.FOLDER_ID).asInstanceOf[Option[String]]

      val _query: Query = if (folderId.isDefined) {
        val allFolders = app.service.folder.getChildrenRecursive(rootId = folderId.get)
        val allFolderIds = (folderId.get :: allFolders.map(_.persistedId)).toSet

        query.add(FieldConst.Asset.FOLDER_ID -> Query.IN(allFolderIds.asInstanceOf[Set[Any]]))
      } else {
        query
      }

      app.service.asset.query(_query)
    }
  }

  def search(query: SearchQuery): SearchResult = {
    txManager.asReadOnly[SearchResult] {
      val _query: SearchQuery = if (query.folderIds.nonEmpty) {
        if (query.folderIds.size > 1) {
          throw IllegalOperationException("Currently cannot search in multiple folders at once")
        }

        val allFolders = app.service.folder.getChildrenRecursive(rootId = query.folderIds.head)
        val allFolderIds = (query.folderIds.head :: allFolders.map(_.persistedId)).toSet

        new SearchQuery(
          text = query.text,
          folderIds = allFolderIds,
          params = query.params,
          metadataFilters = query.metadataFilters,
          rpp = query.rpp,
          page = query.page,
          searchSort = query.searchSort
        )
      } else {
        query
      }

      app.service.search.search(_query)
    }
  }

  /** Delete a folder by ID, including its children. Does not allow deleting the root folder, or any system folders. */
  def deleteFolderById(id: String): Unit = {
    if (app.service.folder.isRootFolder(id)) {
      throw IllegalOperationException("Cannot delete the root folder")
    }

    txManager.withTransaction {
      val folder: Folder = app.service.folder.getById(id)
      logger.info(s"Deleting folder $folder")

      val children = app.service.folder.getChildrenRecursive(id)
      val allFoldersToDeleteIds = (children.map(_.persistedId) :+ folder.persistedId).toSet[Any]

      val folderQuery = new Query().add(FieldConst.ID -> Query.IN(allFoldersToDeleteIds))
      app.service.folder.updateByQuery(folderQuery, Map(FieldConst.Folder.IS_RECYCLED -> true))

      val assetQuery = new Query().add(FieldConst.Asset.FOLDER_ID -> Query.IN(allFoldersToDeleteIds))
      val assetsToRecycle = app.service.asset.queryAll(assetQuery)

      val assetIdsToRecycle = assetsToRecycle.records.map(r => (r: Asset)).map(_.persistedId).toSet
      recycleAssets(assetIdsToRecycle)
    }
  }

  def restoreRecycledAssets(assetIds: Set[String]): Unit = {
    logger.info(s"Restoring recycled assets [${assetIds.mkString(",")}]")

    assetIds.foreach {
      assetId =>
        logger.info(s"Restoring recycled asset [$assetId]")

        val asset: Asset = app.service.asset.getById(assetId)
        val existing = app.service.asset.getByChecksum(asset.checksum)

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

            val restoredAsset: Asset = app.service.asset.getById(assetId)
            app.service.stats.restoreAsset(restoredAsset)
          }
        }
    }
  }

  /** Note that this is also how we restore assets from the recycle bin or move them from triage. */
  def moveAssetsToFolder(assetIds: Set[String], destFolderId: String): Unit = {
    txManager.withTransaction {
      val assetsToMove = app.service.asset.getAssetsToMove(assetIds, destFolderId)

      if (destFolderId == null) {
        throw IllegalOperationException("Destination folder ID cannot be null")
      }

      logger.info(s"Moving assets [${assetIds.mkString(",")}] to folder [$destFolderId] " + assetsToMove.length)
      if (assetsToMove.nonEmpty) {
        val assetQuery = new Query().add(FieldConst.ID -> Query.IN(assetsToMove.map(_.persistedId).toSet[Any]))

        app.service.asset.updateByQuery(
          query = assetQuery,
          data = Map(
            FieldConst.Asset.FOLDER_ID -> destFolderId,
            FieldConst.Asset.IS_RECYCLED -> false,
            FieldConst.Asset.IS_TRIAGED -> false)
        )

        // update the stats in one pass
        val (triagedAssets, triagedBytes, recycledAssets, recycledAssetsBytes, sortedAssets, sortedBytes) =
          assetsToMove.foldLeft((0, 0L, 0, 0L, 0, 0L)) {
            case (
                  (triagedAssetsSum, triagedBytesSum, recycledAssetsSum, recycledAssetsBytesSum, sortedAssetsSum, sortedBytesSum),
                  asset) =>
              if (asset.isTriaged) {
                (
                  triagedAssetsSum + 1,
                  triagedBytesSum + asset.sizeBytes,
                  recycledAssetsSum,
                  recycledAssetsBytesSum,
                  sortedAssetsSum + 1,
                  sortedBytesSum + asset.sizeBytes)
              } else if (asset.isRecycled) {
                (
                  triagedAssetsSum,
                  triagedBytesSum,
                  recycledAssetsSum + 1,
                  recycledAssetsBytesSum + asset.sizeBytes,
                  sortedAssetsSum + 1,
                  sortedBytesSum + asset.sizeBytes)
              } else {
                // asset, not in a special state, being movied from one folder to another - no global stats change
                (triagedAssetsSum, triagedBytesSum, recycledAssetsSum, recycledAssetsBytesSum, sortedAssetsSum, sortedBytesSum)
              }
          }

        app.service.stats.decrementStat(Stats.TRIAGE_ASSETS, triagedAssets)
        app.service.stats.decrementStat(Stats.TRIAGE_BYTES, triagedBytes)
        app.service.stats.decrementStat(Stats.RECYCLED_ASSETS, recycledAssets)
        app.service.stats.decrementStat(Stats.RECYCLED_BYTES, recycledAssetsBytes)

        app.service.stats.incrementStat(Stats.SORTED_ASSETS, sortedAssets)
        app.service.stats.incrementStat(Stats.SORTED_BYTES, sortedBytes)

        app.service.person.restoreFacesForAssets(assetIds)
      }
    }
  }

  def recycleAssets(assetIds: Set[String]): Unit = {
    txManager.withTransaction {
      val assetsToRecycle = app.service.asset.getAssetsToRecycle(assetIds)

      if (assetsToRecycle.nonEmpty) {
        val assetQuery = new Query().add(FieldConst.ID -> Query.IN(assetsToRecycle.map(_.persistedId).toSet[Any]))

        app.service.asset.updateByQuery(
          query = assetQuery,
          data = Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_TRIAGED -> false)
        )

        // update the stats in one pass
        val (triagedAssets, triagedBytes, sortedAssets, sortedBytes) = assetsToRecycle.foldLeft((0, 0L, 0, 0L)) {
          case ((triagedAssetsSum, triagedBytesSum, sortedAssetsSum, sortedBytesSum), asset) =>
            if (asset.isTriaged) {
              (triagedAssetsSum + 1, triagedBytesSum + asset.sizeBytes, sortedAssetsSum, sortedBytesSum)
            } else {
              (triagedAssetsSum, triagedBytesSum, sortedAssetsSum + 1, sortedBytesSum + asset.sizeBytes)
            }
        }

        app.service.stats.decrementStat(Stats.TRIAGE_ASSETS, triagedAssets)
        app.service.stats.decrementStat(Stats.TRIAGE_BYTES, triagedBytes)
        app.service.stats.decrementStat(Stats.SORTED_ASSETS, sortedAssets)
        app.service.stats.decrementStat(Stats.SORTED_BYTES, sortedBytes)

        app.service.stats.incrementStat(Stats.RECYCLED_ASSETS, assetIds.size)
        app.service.stats.incrementStat(Stats.RECYCLED_BYTES, triagedBytes + sortedBytes)

        app.service.person.recycleFacesForAssets(assetIds)
      }
    }
  }

  def purgeRecycleBin(): Unit = {
    val assetsToPurge = txManager.withTransaction {
      this.markRecycledAssetsForPurging()
      app.service.asset.queryRecycled(new Query().add(FieldConst.Asset.IS_PURGED -> true))
    }

    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = RequestContext.getAccount)
    assetsToPurge.records.map(app.service.purgePipeline.addToQueue(_, pipelineContext))
  }

  private def markRecycledAssetsForPurging(): Unit = {
    txManager.withTransaction {
      val assetQuery = new Query().add(FieldConst.Asset.IS_RECYCLED -> true)

      val recycledCount = app.service.asset.updateByQuery(assetQuery, Map(FieldConst.Asset.IS_PURGED -> true))

      // trashbin should be at zero
      val stats = app.service.stats.getStats
      require(stats.getStatValue(Stats.RECYCLED_ASSETS) == recycledCount, "Recycled assets count mismatch")

      app.service.stats.decrementStat(Stats.RECYCLED_ASSETS, stats.getStatValue(Stats.RECYCLED_ASSETS))
      app.service.stats.decrementStat(Stats.RECYCLED_BYTES, stats.getStatValue(Stats.RECYCLED_BYTES))
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
