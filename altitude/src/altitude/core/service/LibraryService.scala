package altitude.core.service

import java.nio.file.Files
import java.nio.file.Path
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import altitude.core.{ Const => _, _ }
import altitude.core.models._
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.sinks.AssetSeqOutputSink
import altitude.core.transactions.TransactionManager
import altitude.core.util.BoundingBox
import altitude.core.util.GroupedSearchResult
import altitude.core.util.MurmurHash
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SearchCursor
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

/**
 * What is the difference between this and the AssetService?
 *
 * The LibraryService is a higher-level service that deals with the library as a whole, where methods touch multiple sub-services.
 * While it's not a strict separation, and sub-services can mingle on their own, anything that has to do with high-level library
 * concepts should be in this service.
 */
object LibraryService:
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set(
    "image",
    "video",
    "x-none" // in test, this is used to force zero-length preview data
  )

class LibraryService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val txManager: TransactionManager = app.txManager

  def checkMediaType(asset: Asset): Unit =
    if !LibraryService.SUPPORTED_MEDIA_TYPES.contains(asset.assetType.mediaType) then throw UnsupportedMediaTypeException(asset)

  /** Stages a copy of the file to import and describes it; see [[stagedFileToAsset]] */
  def convImportAsset2dataAsset(importAsset: ImportAsset): AssetWithData =
    stagedFileToAsset(importAsset.fileName, app.service.staging.stageCopy(importAsset.path))

  /** The asset of a staged file: its media type by content, its checksum streamed, its size; the pipeline owns the file from here */
  def stagedFileToAsset(fileName: String, staged: Path): AssetWithData =
    val asset = Asset(
      userId = RequestContext.account.value.get.persistedId,
      fileName = fileName,
      checksum = MurmurHash.hash32(staged),
      assetType = app.service.metadataExtractor.detectAssetType(staged),
      sizeBytes = Files.size(staged),
      isTriaged = true,
      folderId = ""
    )
    AssetWithData(asset, staged)

  def addImportAsset(importAsset: ImportAsset): Asset =
    logger.info(s"Importing asset '$importAsset'")
    val dataAssetIn = convImportAsset2dataAsset(importAsset)
    addAsset(dataAssetIn)

  def addAsset(dataAsset: AssetWithData): Asset =
    val pipelineContext = PipelineContext(RequestContext.getRepository, RequestContext.getAccount)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source.single((dataAsset, pipelineContext))
    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] = app.service.importPipeline.run(source, AssetSeqOutputSink())

    val result: Seq[TAssetOrInvalidWithContext] = Await.result(pipelineResFuture, Duration.Inf)

    result.head match {
      case (Left(assetOut), _) => assetOut
      case (Right(invalid), _) => throw invalid.cause.getOrElse(new Exception("Unknown error"))
    }

  /** The import pipeline's index stage: the asset persisted and its Search document written, together or not at all */
  def persistAndIndex(asset: Asset): Asset =
    txManager.withTransaction {
      val persisted = app.service.asset.add(asset)
      app.service.search.indexAsset(persisted)
      persisted
    }

  /**
   * The import pipeline's last stage: the asset marked complete and counted in the stats. An asset that never gets here is purged
   * at startup, so it is never counted.
   */
  def completeImport(asset: Asset): Asset =
    txManager.withTransaction {
      val completed = app.service.asset.markAsCompleted(asset)
      app.service.stats.addAsset(asset)
      completed
    }

  def query(query: Query): QueryResult[Asset] =
    txManager.asReadOnly {
      val folderId = query.params.get(FieldConst.Asset.FOLDER_ID).asInstanceOf[Option[String]]

      val _query: Query =
        if folderId.isDefined then
          val allFolders = app.service.folder.getChildrenRecursive(rootId = folderId.get)
          val allFolderIds = (folderId.get :: allFolders.map(_.persistedId)).toSet

          query.add(FieldConst.Asset.FOLDER_ID -> Query.IN(allFolderIds.asInstanceOf[Set[Any]]))
        else query

      app.service.asset.query(_query)
    }

  def search(query: SearchQuery): SearchResult =
    txManager.asReadOnly {
      app.service.search.search(withResolvedScope(query))
    }

  /** How many assets a search matches, exactly, scoped like `search`: what merging people recounts a person's assets by */
  def count(query: SearchQuery): Int =
    txManager.asReadOnly {
      app.service.search.count(withResolvedScope(query))
    }

  /**
   * How many assets a search matches up to the query's cap, scoped like `search`, for a total the results UI shows without
   * rendering rows of its own (the map layout); one past the cap means more than it
   */
  def cappedCount(query: SearchQuery): Int =
    txManager.asReadOnly {
      app.service.search.cappedCount(withResolvedScope(query))
    }

  /** The map's cells and Locations for a viewport at a zoom, scoped like `search`; both aggregates read one snapshot */
  def mapCells(query: SearchQuery, bbox: BoundingBox, zoom: Int): MapCells =
    txManager.asReadOnly {
      app.service.search.mapCells(withResolvedScope(query), bbox, zoom)
    }

  /** The box around every point a search plots, scoped like `search`; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds] =
    txManager.asReadOnly {
      app.service.search.mapBounds(withResolvedScope(query))
    }

  /**
   * A grouped page with its assets, for the grouped grid. A cursor is accepted only for the search it was issued for,
   * fingerprinted as requested: a folder filter by the folder given and the Search text as typed, since a folder's descendants
   * and the names the text matches are resolved afresh on every page.
   */
  def searchGrouped(query: SearchQuery): GroupedSearchResult =
    txManager.asReadOnly {
      val scope = SearchCursor.scopeFingerprint(query, RequestContext.getRepository.persistedId, app.dataSourceType)
      query.cursor.foreach(_.requireScope(scope))
      app.service.search.searchGrouped(withResolvedScope(query), scope)
    }

  /** What every search resolves against the repository as it is now, in the caller's transaction, before it reaches the DAO */
  private def withResolvedScope(query: SearchQuery): SearchQuery =
    withResolvedText(withResolvedFolderScope(query))

  /** The names a Search text matches are resolved on every request, so a rename, a merge or a move shows in the next search */
  private def withResolvedText(query: SearchQuery): SearchQuery =
    query.textExpression.fold(query)(
      expression => query.withResolvedText(app.service.search.resolveText(expression, query.textProbeLimit)))

  /** Folder membership is resolved on every request: a folder filter means the folder and all of its current descendants */
  private def withResolvedFolderScope(query: SearchQuery): SearchQuery =
    if query.folderIds.isEmpty then return query
    if query.folderIds.size > 1 then throw IllegalOperationException("Currently cannot search in multiple folders at once")

    val folderId = query.folderIds.head

    // If the root folder is selected, treat it as "no folder filter" so that
    // triaged assets (which have an empty folderId) are included — matching the default view.
    if app.service.folder.isRootFolder(folderId) then query.withFolderIds(Set.empty)
    else
      val allFolders = app.service.folder.getChildrenRecursive(rootId = folderId)
      val allFolderIds = (folderId :: allFolders.map(_.persistedId)).toSet
      query.withFolderIds(allFolderIds)

  /** Delete a folder by ID, including its children. Does not allow deleting the root folder, or any system folders. */
  def deleteFolderById(id: String): Unit =
    if app.service.folder.isRootFolder(id) then throw IllegalOperationException("Cannot delete the root folder")

    txManager.withTransaction {
      val folder: Folder = app.service.folder.getById(id)
      logger.info(s"Deleting folder $folder")

      val children = app.service.folder.getChildrenRecursive(id)
      val allFoldersToDeleteIds = (children.map(_.persistedId) :+ folder.persistedId).toSet[Any]

      val folderQuery = new Query().add(FieldConst.ID -> Query.IN(allFoldersToDeleteIds))
      app.service.folder.updateByQuery(folderQuery, Map(FieldConst.Folder.IS_RECYCLED -> true))

      val assetQuery = new Query().add(FieldConst.Asset.FOLDER_ID -> Query.IN(allFoldersToDeleteIds))
      val assetsToRecycle = app.service.asset.queryAll(assetQuery)

      val assetIdsToRecycle = assetsToRecycle.records.map(r => r: Asset).map(_.persistedId).toSet
      recycleAssets(assetIdsToRecycle)
    }

  /**
   * Restores recycled assets, all in one transaction: an asset whose content is live again (imported anew after it was recycled)
   * stays in the trash and is reported, and any other failure restores nothing. An asset that is not recycled is ignored.
   */
  def restoreRecycledAssets(assetIds: Set[String]): RestoreResult =
    logger.info(s"Restoring recycled assets [${assetIds.mkString(",")}]")

    val result = txManager.withTransaction {
      assetIds.foldLeft(RestoreResult(restored = Set.empty, duplicates = Set.empty)) {
        (result, assetId) =>
          val asset: Asset = app.service.asset.getById(assetId)

          if !asset.isRecycled then result
          else if app.service.asset.getByChecksum(asset.checksum).isDefined then
            logger.debug(s"Not restoring asset [$assetId]: an asset with the same content is live")
            result.copy(duplicates = result.duplicates + assetId)
          else
            restoreRecycledAsset(asset)
            result.copy(restored = result.restored + assetId)
      }
    }

    logger.info(s"Restored ${result.restored.size} assets; ${result.duplicates.size} left in the trash as duplicates")
    result

  private def restoreRecycledAsset(asset: Asset): Unit =
    logger.info(s"Restoring recycled asset [${asset.persistedId}]")
    app.service.asset.setRecycledProp(asset, isRecycled = false)

    // Assets recycled directly from triage have no folder assigned — skip folder restoration
    if !asset.isTriaged then
      // Restore the full ancestor chain (top-down) so the folder tree is consistent.
      // getAncestors returns from root -> direct parent, so we can iterate in order.
      val ancestors: List[Folder] = app.service.folder.getAncestors(asset.folderId)
      ancestors.foreach {
        ancestor => if ancestor.isRecycled then app.service.folder.setRecycledProp(folder = ancestor, isRecycled = false)
      }

      // Restore the immediate folder of the asset
      val folder: Folder = app.service.folder.getById(asset.folderId)
      if folder.isRecycled then app.service.folder.setRecycledProp(folder = folder, isRecycled = false)

    val restoredAsset: Asset = app.service.asset.getById(asset.persistedId)
    app.service.stats.restoreAsset(restoredAsset)
    app.service.person.restoreFacesForAssets(Set(asset.persistedId))

  /** Note that this is also how we restore assets from the recycle bin or move them from triage. */
  def moveAssetsToFolder(assetIds: Set[String], destFolderId: String): Unit =
    txManager.withTransaction {
      val assetsToMove = app.service.asset.getAssetsToMove(assetIds, destFolderId)

      if destFolderId == null then throw IllegalOperationException("Destination folder ID cannot be null")

      logger.info(s"Moving assets [${assetIds.mkString(",")}] to folder [$destFolderId] " + assetsToMove.length)
      if assetsToMove.nonEmpty then
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
              if asset.isTriaged then
                (
                  triagedAssetsSum + 1,
                  triagedBytesSum + asset.sizeBytes,
                  recycledAssetsSum,
                  recycledAssetsBytesSum,
                  sortedAssetsSum + 1,
                  sortedBytesSum + asset.sizeBytes)
              else if asset.isRecycled then
                (
                  triagedAssetsSum,
                  triagedBytesSum,
                  recycledAssetsSum + 1,
                  recycledAssetsBytesSum + asset.sizeBytes,
                  sortedAssetsSum + 1,
                  sortedBytesSum + asset.sizeBytes)
              else
                // asset, not in a special state, being movied from one folder to another - no global stats change
                (triagedAssetsSum, triagedBytesSum, recycledAssetsSum, recycledAssetsBytesSum, sortedAssetsSum, sortedBytesSum)
          }

        app.service.stats.decrementStat(Stats.TRIAGE_ASSETS, triagedAssets)
        app.service.stats.decrementStat(Stats.TRIAGE_BYTES, triagedBytes)
        app.service.stats.decrementStat(Stats.RECYCLED_ASSETS, recycledAssets)
        app.service.stats.decrementStat(Stats.RECYCLED_BYTES, recycledAssetsBytes)

        app.service.stats.incrementStat(Stats.SORTED_ASSETS, sortedAssets)
        app.service.stats.incrementStat(Stats.SORTED_BYTES, sortedBytes)

        // Restoring face counts is only valid when assets are coming out of recycle.
        // Triage/folder moves should not mutate person.numOfFaces.
        if assetsToMove.nonEmpty && assetsToMove.forall(_.isRecycled) then app.service.person.restoreFacesForAssets(assetIds)
    }

  def recycleAssets(assetIds: Set[String]): Unit =
    txManager.withTransaction {
      val assetsToRecycle = app.service.asset.getAssetsToRecycle(assetIds)

      if assetsToRecycle.nonEmpty then
        val assetQuery = new Query().add(FieldConst.ID -> Query.IN(assetsToRecycle.map(_.persistedId).toSet[Any]))

        app.service.asset.updateByQuery(
          query = assetQuery,
          data = Map(FieldConst.Asset.IS_RECYCLED -> true)
        )

        // update the stats in one pass
        val (triagedAssets, triagedBytes, sortedAssets, sortedBytes) = assetsToRecycle.foldLeft((0, 0L, 0, 0L)) {
          case ((triagedAssetsSum, triagedBytesSum, sortedAssetsSum, sortedBytesSum), asset) =>
            if asset.isTriaged then (triagedAssetsSum + 1, triagedBytesSum + asset.sizeBytes, sortedAssetsSum, sortedBytesSum)
            else (triagedAssetsSum, triagedBytesSum, sortedAssetsSum + 1, sortedBytesSum + asset.sizeBytes)
        }

        app.service.stats.decrementStat(Stats.TRIAGE_ASSETS, triagedAssets)
        app.service.stats.decrementStat(Stats.TRIAGE_BYTES, triagedBytes)
        app.service.stats.decrementStat(Stats.SORTED_ASSETS, sortedAssets)
        app.service.stats.decrementStat(Stats.SORTED_BYTES, sortedBytes)

        app.service.stats.incrementStat(Stats.RECYCLED_ASSETS, assetIds.size)
        app.service.stats.incrementStat(Stats.RECYCLED_BYTES, triagedBytes + sortedBytes)

        app.service.person.recycleFacesForAssets(assetIds)

        // Albums and Locations only point at assets; a recycled asset leaves every one of them and a restore does not bring
        // it back
        val recycledIds = assetsToRecycle.map(_.persistedId).toSet
        app.service.album.removeAssetsFromAllAlbums(recycledIds)
        app.service.location.removeAssetsFromAllLocations(recycledIds)
    }

  def purgeRecycleBin(): Unit =
    val assetsToPurge = txManager.withTransaction {
      this.markRecycledAssetsForPurging()
      app.service.asset.queryRecycled(new Query().add(FieldConst.Asset.IS_PURGED -> true))
    }

    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = RequestContext.getAccount)
    assetsToPurge.records.map(app.service.purgePipeline.addToQueue(_, pipelineContext))

  def purgeSelectedAssets(assetIds: Set[String]): Unit =
    logger.info(s"Purging selected assets [${assetIds.mkString(",")}]")

    val assetsToPurge = txManager.withTransaction {
      markSelectedAssetsForPurging(assetIds)
      app.service.asset.queryRecycled(
        new Query()
          .add(FieldConst.Asset.IS_PURGED -> true)
          .add(FieldConst.ID -> Query.IN(assetIds.asInstanceOf[Set[Any]]))
      )
    }

    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = RequestContext.getAccount)
    assetsToPurge.records.map(app.service.purgePipeline.addToQueue(_, pipelineContext))

  private def markSelectedAssetsForPurging(assetIds: Set[String]): Unit =
    txManager.withTransaction {
      val assetQuery = new Query()
        .add(FieldConst.Asset.IS_RECYCLED -> true)
        .add(FieldConst.ID -> Query.IN(assetIds.asInstanceOf[Set[Any]]))

      val assets = app.service.asset.queryAll(assetQuery).records.map(r => r: Asset)

      if assets.nonEmpty then
        val purgeQuery = new Query().add(FieldConst.ID -> Query.IN(assets.map(_.persistedId).toSet[Any]))
        app.service.asset.updateByQuery(purgeQuery, Map(FieldConst.Asset.IS_PURGED -> true))

        val totalBytes = assets.foldLeft(0L)((sum, asset) => sum + asset.sizeBytes)

        app.service.stats.decrementStat(Stats.RECYCLED_ASSETS, assets.size)
        app.service.stats.decrementStat(Stats.RECYCLED_BYTES, totalBytes)
    }

  private def markRecycledAssetsForPurging(): Unit =
    txManager.withTransaction {
      val assetQuery = new Query().add(FieldConst.Asset.IS_RECYCLED -> true)

      val recycledCount = app.service.asset.updateByQuery(assetQuery, Map(FieldConst.Asset.IS_PURGED -> true))

      // trashbin should be at zero
      val stats = app.service.stats.getStats
      require(stats.getStatValue(Stats.RECYCLED_ASSETS) == recycledCount, "Recycled assets count mismatch")

      app.service.stats.decrementStat(Stats.RECYCLED_ASSETS, stats.getStatValue(Stats.RECYCLED_ASSETS))
      app.service.stats.decrementStat(Stats.RECYCLED_BYTES, stats.getStatValue(Stats.RECYCLED_BYTES))
    }

  def pruneDanglingAssets(): Unit =
    forEachRepository {
      repository =>
        logger.info(s"Pruning dangling assets. Repo: ${repository.name}")
        val danglingAssets = app.service.asset.getDanglingAssets

        if danglingAssets.nonEmpty then
          logger.warn(s"Found ${danglingAssets.size} dangling assets")
          danglingAssets.foreach(asset => logger.warn(s"Will prune: ${asset.persistedId} - ${asset.fileName}"))
        app.service.asset.pruneDanglingAssets()
    }

  /** Runs the operation in every repository's context, then restores the caller's own repository context */
  def forEachRepository(operation: Repository => Unit): Unit =
    val callerRepository = RequestContext.repository.value

    txManager.withTransaction {
      val repositories = app.service.repository.getAll

      try
        repositories.foreach {
          repository =>
            RequestContext.repository.value = Some(repository)
            operation(repository)
        }
      finally RequestContext.repository.value = callerRepository
    }
