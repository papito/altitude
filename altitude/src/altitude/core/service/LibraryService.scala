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
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SearchCursor
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.Sha256
import altitude.core.util.TriageSubset

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

  /**
   * The asset of a staged file: its media type by content, its checksum (the file's SHA-256, streamed), its size, and the context
   * account as its owner and in its importer record, which [[persistAndIndex]] builds again from the account as stored when it
   * inserts the asset; the pipeline owns the file from here
   */
  def stagedFileToAsset(fileName: String, staged: Path): AssetWithData =
    val importer = RequestContext.getAccount
    val asset = Asset(
      ownerUserId = importer.persistedId,
      originalOwner = AccountSnapshot.of(importer),
      fileName = fileName,
      checksum = Sha256.hex(staged),
      assetType = app.service.metadataExtractor.detectAssetType(staged),
      sizeBytes = Files.size(staged),
      isTriaged = true,
      folderId = ""
    )
    AssetWithData(asset, staged)

  def addImportAsset(importAsset: ImportAsset): Asset =
    logger.debug(s"Importing asset '$importAsset'")
    val dataAssetIn = convImportAsset2dataAsset(importAsset)
    addAsset(dataAssetIn)

  def addAsset(dataAsset: AssetWithData): Asset =
    val pipelineContext =
      PipelineContext(RequestContext.getRepository, Some(RequestContext.getAccount), RequestContext.permissions.value)
    val source: Source[(AssetWithData, PipelineContext), NotUsed] = Source.single((dataAsset, pipelineContext))
    val pipelineResFuture: Future[Seq[TAssetOrInvalidWithContext]] = app.service.importPipeline.run(source, AssetSeqOutputSink())

    val result: Seq[TAssetOrInvalidWithContext] = Await.result(pipelineResFuture, Duration.Inf)

    result.head match {
      case (Left(assetOut), _) => assetOut
      case (Right(invalid), _) => throw invalid.cause.getOrElse(new Exception("Unknown error"))
    }

  /**
   * The import pipeline's index stage: the asset persisted and its Search document written, together or not at all. Its importer
   * record is built here from the importing account as stored now (`UserService.lockImporter`), not as it was at upload, so an
   * account change made while the file waited in the pipeline is in it, or rewrites it once the insert commits; an account gone
   * or deactivated by then fails the file with `ImporterUnavailableException`.
   */
  def persistAndIndex(asset: Asset): Asset =
    txManager.withTransaction {
      val importer = app.service.user.lockImporter(asset.ownerUserId)
      val persisted = app.service.asset.add(asset.copy(originalOwner = AccountSnapshot.of(importer)))
      app.service.search.indexAsset(persisted)
      logger.trace(s"Persisted and indexed asset [${persisted.persistedId}]")
      persisted
    }

  /**
   * The import pipeline's completion stage, one transaction: the asset marked complete, with its Image quality and perceptual
   * hash stored alongside the flag (`markAsCompleted`), then placed and counted in the stats, and, when it is a file of an Import
   * (`origin`), its Import file settled as imported with the asset (`ImportService.settle`), so the record says imported exactly
   * when the asset is complete. A file of an Import with no destination, and an asset with no origin, stays in Triage, in the
   * Sequence of its burst if it has one (`SequenceService.assign`, for which the earlier uploads of the burst are completed
   * candidates already). A file of an Import with a destination is sorted on arrival ([[sortInto]]): into the folder its path
   * names under the destination, which is where a folder is born, with the first asset that lands in or below it. A destination
   * in the Trash, and a folder name the folder rule refuses, fail the completion with `ImportDestinationException`, which is a
   * drop that creates nothing. An asset that never gets here is purged at startup, so it is never counted.
   */
  def completeImport(asset: Asset, origin: Option[ImportOrigin] = None): Asset =
    txManager.withTransaction {
      val completed = app.service.asset.markAsCompleted(asset)
      val destination = origin.flatMap(o => app.service.imports.destinationOf(o.importFileId).map((_, o.folderPath)))
      val placed = destination match
        case Some((folder, folderPath)) => sortInto(completed, folder, folderPath)
        case None => app.service.sequence.assign(completed)
      app.service.stats.addAsset(placed)
      origin.foreach(o => app.service.imports.settle(o.importFileId, ImportOutcome.Imported, assetId = asset.id))
      logger.trace(s"Import of asset [${asset.persistedId}] complete")
      placed
    }

  /**
   * Sorts a just-completed asset into the folder `folderPath` names under `destination`, the destination itself for no names
   * (`FolderService.ensurePath`: the folders of the path that exist are reused, a recycled one restored, a missing one created),
   * and returns it as it is then: in that folder and out of Triage, so in no Sequence, as an asset moved out of Triage is.
   */
  private def sortInto(asset: Asset, destination: Folder, folderPath: Seq[String]): Asset =
    val folder =
      try app.service.folder.ensurePath(destination.persistedId, folderPath)
      catch
        case refused: ValidationException =>
          throw ImportDestinationException(altitude.core.Const.Msg.Err.DESTINATION_FOLDER_NAME.format(refused.message))

    app.service.asset.updateById(
      asset.persistedId,
      Map(FieldConst.Asset.FOLDER_ID -> folder.persistedId, FieldConst.Asset.IS_TRIAGED -> false)): Unit
    logger.debug(s"Sorted asset [${asset.persistedId}] into folder [${folder.persistedId}]")
    asset.copy(folderId = folder.persistedId, isTriaged = false)

  /**
   * Undoes what the import of an asset wrote, for an asset the pipeline dropped or an import a crash cut off. Nothing to undo for
   * an asset that was never persisted (dropped before the index stage, or a duplicate it refused) or that completed. In one
   * transaction: the people of its faces are locked, so a face another import gives one of them meanwhile keeps that person; a
   * cover on one of its faces moves to the person's best face elsewhere, as on purge (`PersonService.moveCoversOff`); the face
   * counts it added are given back; its row goes, with its faces, its Search document and its metadata; and a person left with no
   * face, which only this import can have started, goes too. Once that commits, its files go: its faces', its stored file and its
   * preview. Its stats need nothing: an asset counts once its import completes.
   */
  def discardImport(asset: Asset): Unit =
    asset.id.foreach {
      assetId =>
        val unfinished = new Query(Map(FieldConst.ID -> assetId, FieldConst.Asset.IS_PIPELINE_PROCESSED -> false))

        val discarded = txManager.withTransaction {
          Option.when(app.service.asset.queryAll(unfinished).nonEmpty) {
            // Locks the people of its faces first
            app.service.person.moveCoversOff(Set(assetId))
            val faces = app.service.person.getAllAssetFaces(assetId)
            app.service.person.recycleFacesForAssets(Set(assetId))
            app.service.asset.deleteByQuery(unfinished)
            val deletedPeople = app.service.person.deletePeopleWithoutFaces(faces.map(_.personId.get).toSet)
            (faces, deletedPeople)
          }
        }

        discarded.foreach {
          case (faces, deletedPeople) =>
            app.service.person.purgeFaceFiles(faces)
            app.service.fileStore.purgeAssetById(assetId)
            logger.debug(
              s"Discarded the import of asset [$assetId]: ${faces.size} faces, ${deletedPeople.size} people it started")
        }
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

  /** A flat page with its assets, continued by cursor as a grouped page is ([[continued]]) */
  def search(query: SearchQuery): SearchResult =
    continued(query)(app.service.search.search)

  /** How many assets a search matches, exactly, scoped like `search`: what merging people recounts a person's assets by */
  def count(query: SearchQuery): Int =
    txManager.asReadOnly {
      app.service.search.count(withResolvedScope(query))
    }

  /**
   * The counts of the Triage summary, exact and read in one snapshot: the Triage view, then its two Triage subsets, the `subset`
   * scopes its links search
   */
  def triageSummary(): TriageSummary =
    txManager.asReadOnly {
      def triage(subset: Option[TriageSubset]) = count(new SearchQuery(params = SearchQuery.TRIAGE_VIEW, subset = subset))

      val summary = TriageSummary(
        total = triage(None),
        unknownPeople = triage(Some(TriageSubset.UnknownPeople)),
        noLocation = triage(Some(TriageSubset.NoLocation)))
      logger.debug(s"Triage summary: $summary")
      summary
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

  /** A grouped page with its assets, for the grouped grid, continued by cursor ([[continued]]) */
  def searchGrouped(query: SearchQuery): GroupedSearchResult =
    continued(query)(app.service.search.searchGrouped)

  /**
   * Runs a page of a search that a cursor may continue. A cursor is accepted only for the search it was issued for, fingerprinted
   * as requested: a folder filter by the folder given and the Search text as typed, since a folder's descendants and the names
   * the text matches are resolved afresh on every page.
   */
  private def continued[T](query: SearchQuery)(page: (SearchQuery, String) => T): T =
    txManager.asReadOnly {
      val scope = SearchCursor.scopeFingerprint(query, RequestContext.getRepository.persistedId, app.dataSourceType)
      query.cursor.foreach(_.requireScope(scope))
      page(withResolvedScope(query), scope)
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

  /**
   * Delete a folder by ID, including its children. Does not allow deleting the root folder, or any system folders. A folder of
   * another repository, or none, is not found. The folders' assets are recycled ([[recycleAssets]]), so deleting a folder that
   * holds any takes Recycle.
   */
  def deleteFolderById(id: String): Unit =
    if app.service.folder.isRootFolder(id) then throw IllegalOperationException("Cannot delete the root folder")

    txManager.withTransaction {
      val folder: Folder = app.service.folder.getById(id)
      logger.debug(s"Deleting folder $folder")

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
   * stays in the trash and is reported, and any other failure restores nothing. An ID that is no asset of the repository fails
   * the restore; a live asset, or one marked for purging, is ignored. Restoring takes what recycling the asset would
   * (`AssetRules.requireRecycle`), checked once the assets are read, so an asset of another repository is not found rather than
   * refused.
   */
  def restoreRecycledAssets(assetIds: Set[String]): RestoreResult =
    logger.debug(s"Restoring recycled assets [${assetIds.mkString(",")}]")

    val result = txManager.withTransaction {
      val recycled = app.service.asset.getAssetsToRestore(assetIds)
      requireAssets(assetIds -- recycled.map(_.persistedId))
      AssetRules.requireRecycle(recycled, action = "restore")

      // One at a time: a restored asset makes a recycled copy of its content a duplicate
      val (restored, duplicates) = recycled.foldLeft((List.empty[Asset], List.empty[Asset])) {
        case ((restored, duplicates), asset) =>
          if app.service.asset.getByChecksum(asset.checksum).isDefined then
            logger.debug(s"Not restoring asset [${asset.persistedId}]: an asset with the same content is live")
            (restored, asset :: duplicates)
          else
            restoreRecycledAsset(asset)
            (asset :: restored, duplicates)
      }

      if restored.nonEmpty then
        app.service.stats.transition(before = restored, after = restored.map(_.copy(isRecycled = false)))
        app.service.person.restoreFacesForAssets(restored.map(_.persistedId).toSet)

      RestoreResult(restored = restored.map(_.persistedId).toSet, duplicates = duplicates.map(_.persistedId).toSet)
    }

    logger.debug(s"Restored ${result.restored.size} assets; ${result.duplicates.size} left in the trash as duplicates")
    result

  /** Fails with NotFoundException when an ID is no asset of the context repository */
  private def requireAssets(assetIds: Set[String]): Unit =
    if assetIds.isEmpty then return

    val found = app.service.asset.queryAll(new Query().add(FieldConst.ID -> Query.IN(assetIds.asInstanceOf[Set[Any]])))
    val unknown = assetIds -- found.records.map(_.persistedId)
    if unknown.nonEmpty then
      throw NotFoundException(s"Cannot find ${unknown.size} of the assets in the repository, [${unknown.head}] among them")

  private def restoreRecycledAsset(asset: Asset): Unit =
    logger.debug(s"Restoring recycled asset [${asset.persistedId}]")
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

  /**
   * Moves assets into a folder, which is also how an asset is moved out of the recycle bin into a folder, or out of triage. An
   * asset leaving triage leaves its Sequence in the same update: a sorted asset never carries one, so a Sequence whose last
   * member moves is gone with it. The column is written NULL for every moved asset in the one update: a sorted asset has none
   * already, and an asset recycled from triage keeps its Sequence until it is moved into a folder. A destination that is no
   * folder of the context repository, unknown or another repository's, is a `NotFoundException` that moves nothing. An asset
   * moved out of the trash is restored, which takes what recycling it would (`AssetRules.requireRecycle`).
   */
  def moveAssetsToFolder(assetIds: Set[String], destFolderId: String): Unit =
    if destFolderId == null then throw IllegalOperationException("Destination folder ID cannot be null")

    txManager.withTransaction {
      app.service.folder.getById(destFolderId): Unit
      val assetsToMove = app.service.asset.getAssetsToMove(assetIds)
      AssetRules.requireRecycle(assetsToMove.filter(_.isRecycled), action = "restore")

      logger.debug(s"Moving assets [${assetIds.mkString(",")}] to folder [$destFolderId] " + assetsToMove.length)
      if assetsToMove.nonEmpty then
        val assetQuery = new Query().add(FieldConst.ID -> Query.IN(assetsToMove.map(_.persistedId).toSet[Any]))

        app.service.asset.updateByQuery(
          query = assetQuery,
          data = Map(
            FieldConst.Asset.FOLDER_ID -> destFolderId,
            FieldConst.Asset.IS_RECYCLED -> false,
            FieldConst.Asset.IS_TRIAGED -> false,
            FieldConst.Asset.SEQUENCE_ID -> None)
        )

        // A recycled asset counts as recycled even when it was recycled from triage and keeps its triage flag, and an asset moved
        // from one folder to another stays sorted
        app.service.stats
          .transition(before = assetsToMove, after = assetsToMove.map(_.copy(isRecycled = false, isTriaged = false)))

        // Only the assets coming out of recycle give their faces back; triage and folder moves leave face counts alone
        val restoredIds = assetsToMove.filter(_.isRecycled).map(_.persistedId).toSet
        if restoredIds.nonEmpty then app.service.person.restoreFacesForAssets(restoredIds)
    }

  /**
   * Recycles the live assets among the IDs, in one transaction; an ID that is no live asset of the context repository is left
   * out. A sorted asset takes Recycle and a triaged one a Curate Permission (`AssetRules.requireRecycle`): one refused asset
   * recycles nothing.
   */
  def recycleAssets(assetIds: Set[String]): Unit =
    txManager.withTransaction {
      val assetsToRecycle = app.service.asset.getAssetsToRecycle(assetIds)
      AssetRules.requireRecycle(assetsToRecycle)
      logger.debug(s"Recycling assets [${assetIds.mkString(",")}]: ${assetsToRecycle.size} recyclable")

      if assetsToRecycle.nonEmpty then
        val assetQuery = new Query().add(FieldConst.ID -> Query.IN(assetsToRecycle.map(_.persistedId).toSet[Any]))

        app.service.asset.updateByQuery(
          query = assetQuery,
          data = Map(FieldConst.Asset.IS_RECYCLED -> true)
        )

        // Only the assets not already in the recycle bin are counted and lose their faces
        app.service.stats.transition(before = assetsToRecycle, after = assetsToRecycle.map(_.copy(isRecycled = true)))

        val recycledIds = assetsToRecycle.map(_.persistedId).toSet
        app.service.person.recycleFacesForAssets(recycledIds)

        // Albums and Locations only point at assets; a recycled asset leaves every one of them and a restore does not bring
        // it back
        app.service.album.removeAssetsFromAllAlbums(recycledIds)
        app.service.location.removeAssetsFromAllLocations(recycledIds)
    }

  def purgeRecycleBin(): Unit =
    logger.debug("Purging the recycle bin")
    val assetsToPurge = txManager.withTransaction(markForPurging(app.service.asset.getAssetsToPurge(None)))
    queueForPurging(assetsToPurge)

  def purgeSelectedAssets(assetIds: Set[String]): Unit =
    logger.debug(s"Purging selected assets [${assetIds.mkString(",")}]")
    val assetsToPurge = txManager.withTransaction(markForPurging(app.service.asset.getAssetsToPurge(Some(assetIds))))
    queueForPurging(assetsToPurge)

  /**
   * Marks the assets for purging and takes them out of the recycled stats. From here they are out of every library operation; the
   * purge queue deletes their files and rows.
   */
  private def markForPurging(assets: List[Asset]): List[Asset] =
    if assets.nonEmpty then
      val purgeQuery = new Query().add(FieldConst.ID -> Query.IN(assets.map(_.persistedId).toSet[Any]))
      app.service.asset.updateByQuery(purgeQuery, Map(FieldConst.Asset.IS_PURGED -> true))

      app.service.stats.transition(before = assets, after = Nil)
      logger.info(s"Marked ${assets.size} assets for purging")
    assets

  /**
   * Hands assets marked for purging in the context repository to the purge queue, once their mark has committed, with the context
   * account, if there is one, as the pipeline's
   */
  private def queueForPurging(assets: List[Asset], account: Option[User] = RequestContext.account.value): Unit =
    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = account)
    app.service.purgePipeline.enqueue(assets.map((_, pipelineContext)))

  /**
   * Queues again, in every repository, the assets marked for purging that are still there: the app stopped before the purge queue
   * deleted them, or their delete failed. They were taken out of the stats when they were marked.
   */
  def requeuePurgePending(): Unit =
    forEachRepository {
      repository =>
        val pending = app.service.asset.queryRecycled(new Query().add(FieldConst.Asset.IS_PURGED -> true)).records

        if pending.nonEmpty then
          logger.info(s"Queueing ${pending.size} assets marked for purging again. Repo: ${repository.name}")
          // No request queued them, so no account does: a Library has no owner to stand in for one
          queueForPurging(pending, account = None)
    }

  /** Repairs every repository's stats from its assets, which the library operations only ever adjust */
  def reconcileStats(): Unit =
    forEachRepository(_ => app.service.stats.reconcile(): Unit)

  /** Discards, in every repository, the imports a crash or a shutdown cut off before they completed ([[discardImport]]) */
  def pruneDanglingAssets(): Unit =
    forEachRepository {
      repository =>
        logger.debug(s"Pruning dangling assets. Repo: ${repository.name}")
        val danglingAssets = app.service.asset.getDanglingAssets

        if danglingAssets.nonEmpty then logger.warn(s"Found ${danglingAssets.size} dangling assets")
        danglingAssets.foreach {
          asset =>
            logger.warn(s"Will prune: ${asset.persistedId} - ${asset.fileName}")
            discardImport(asset)
        }
    }

  /**
   * Runs the operation in every repository's context, then restores the caller's own repository context. The operation opens its
   * own transactions, so what it does after one commits, such as deleting files, happens after that commit.
   */
  def forEachRepository(operation: Repository => Unit): Unit =
    app.service.repository.getAll.foreach {
      repository => RequestContext.repository.withValue(Some(repository))(operation(repository))
    }
