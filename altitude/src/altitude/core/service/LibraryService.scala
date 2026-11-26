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

/** Operations on the library as a whole that span several services, above `AssetService` and its peers */
object LibraryService:
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set(
    "image",
    "video",
    "x-none" // used by tests to force empty preview data
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
   * The asset of a staged file: its media type by content, its SHA-256 checksum, its size, and the context account as owner. The
   * pipeline owns the file from here.
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
   * The index stage: persists the asset and writes its Search document, together. The importer record is rebuilt from the account
   * as stored now (`UserService.lockImporter`), not at upload, so an account change made while the file waited is in it, and one
   * made meanwhile waits and rewrites it; an account gone or deactivated by then fails the file.
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
   * The completion stage, in one transaction: marks the asset complete (with its Image quality and perceptual hash), places it,
   * counts it and settles its Import file as imported, so the record says imported exactly when the asset is complete. Without a
   * destination the asset stays in Triage, in its burst's Sequence if any; with one it is sorted on arrival ([[sortInto]]). A
   * lost destination or an uncreatable folder fails it with `ImportDestinationException`, a drop that creates nothing and is
   * never a duplicate. An asset that never gets here is discarded at startup, so it is never counted.
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
   * Sorts a just-completed asset into the folder `folderPath` names under `destination` (`FolderService.ensurePath`), out of
   * Triage and so in no Sequence
   */
  private def sortInto(asset: Asset, destination: Folder, folderPath: Seq[String]): Asset =
    val folder =
      try app.service.folder.ensurePath(destination.persistedId, folderPath)
      catch
        case refused: ValidationException => throw uncreatable(refused.message)
        // A name taken meanwhile past the turn-taking, or held by the root. Never left a DuplicateException, which would settle
        // the file as a duplicate of content in the Library
        case _: DuplicateException => throw uncreatable(altitude.core.Const.Msg.Err.FOLDER_NAME_TAKEN)

    app.service.asset.updateById(
      asset.persistedId,
      Map(FieldConst.Asset.FOLDER_ID -> folder.persistedId, FieldConst.Asset.IS_TRIAGED -> false)): Unit
    logger.debug(s"Sorted asset [${asset.persistedId}] into folder [${folder.persistedId}]")
    asset.copy(folderId = folder.persistedId, isTriaged = false)

  /** The failure of a completion whose folder cannot be created, for the reason given */
  private def uncreatable(reason: String) =
    ImportDestinationException(altitude.core.Const.Msg.Err.DESTINATION_FOLDER_NAME.format(reason))

  /**
   * Undoes what an asset's import wrote, for an asset the pipeline dropped or a crash cut off; nothing for one never persisted or
   * already complete. In one transaction it locks the people of its faces, so a face another import gives one of them meanwhile
   * keeps that person; moves covers off its faces as a purge does; gives back its face counts; and deletes its row and any person
   * left with no face, whom only this import can have started. Its files go after the commit. Stats need nothing: an asset counts
   * once its import completes.
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

  /** A flat page, continued by cursor ([[continued]]) */
  def search(query: SearchQuery): SearchResult =
    continued(query)(app.service.search.search)

  /** The exact number of assets a search matches, scoped like `search` */
  def count(query: SearchQuery): Int =
    txManager.asReadOnly {
      app.service.search.count(app.service.search.withResolvedScope(query))
    }

  /** The exact counts of the Triage view and its two Triage subsets, read in one snapshot */
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

  /** How many assets a search matches up to the query's cap, scoped like `search`; one past the cap means more */
  def cappedCount(query: SearchQuery): Int =
    txManager.asReadOnly {
      app.service.search.cappedCount(app.service.search.withResolvedScope(query))
    }

  /** The map's cells and Locations for a viewport at a zoom, scoped like `search`; both aggregates read one snapshot */
  def mapCells(query: SearchQuery, bbox: BoundingBox, zoom: Int): MapCells =
    txManager.asReadOnly {
      app.service.search.mapCells(app.service.search.withResolvedScope(query), bbox, zoom)
    }

  /** The box around every point a search plots, scoped like `search`; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds] =
    txManager.asReadOnly {
      app.service.search.mapBounds(app.service.search.withResolvedScope(query))
    }

  /** A grouped page, continued by cursor ([[continued]]) */
  def searchGrouped(query: SearchQuery): GroupedSearchResult =
    continued(query)(app.service.search.searchGrouped)

  /**
   * Runs a page of a search that a cursor may continue, accepting a cursor only for the search it was issued for. The fingerprint
   * is of the request as given (the folder, the Search text as typed), since descendants and matched names resolve afresh per
   * page.
   */
  private def continued[T](query: SearchQuery)(page: (SearchQuery, String) => T): T =
    txManager.asReadOnly {
      val scope = SearchCursor.scopeFingerprint(query, RequestContext.getRepository.persistedId, app.dataSourceType)
      query.cursor.foreach(_.requireScope(scope))
      page(app.service.search.withResolvedScope(query), scope)
    }

  /**
   * Deletes a folder (never the root) and its descendants, recycling their assets, so a folder holding any takes Recycle. It
   * takes its turn with imports sorting into the tree (`FolderService.lockTree`), so no import leaves anything live under it.
   */
  def deleteFolderById(id: String): Unit =
    if app.service.folder.isRootFolder(id) then throw IllegalOperationException("Cannot delete the root folder")

    txManager.withTransaction {
      // Before any read: an import sorting into these folders has committed by then, so what it created is recycled here
      app.service.folder.lockTree()
      val folder: Folder = app.service.folder.getById(id)
      logger.debug(s"Deleting folder $folder")

      val children = app.service.folder.getChildrenRecursive(id)
      val allFoldersToDeleteIds = (children.map(_.persistedId) :+ folder.persistedId).toSet

      val folderQuery = new Query().add(FieldConst.ID -> Query.IN(allFoldersToDeleteIds.toSet[Any]))
      app.service.folder.updateByQuery(folderQuery, Map(FieldConst.Folder.IS_RECYCLED -> true))

      // IDs alone: recycling reads and locks the rows it changes
      recycleAssets(app.service.asset.getIdsInFolders(allFoldersToDeleteIds))
    }

  /**
   * Restores recycled assets in one transaction. An asset whose content is live again (imported anew since) stays in the Trash
   * and is reported; any other failure restores nothing. A live or purge-marked asset is ignored. Restoring takes what recycling
   * would, checked after the read, so another repository's asset is not found rather than refused.
   */
  def restoreRecycledAssets(assetIds: Set[String]): RestoreResult =
    logger.debug(s"Restoring recycled assets [${assetIds.mkString(",")}]")

    val result = txManager.withTransaction {
      // Before the reads and row locks, as a folder delete takes it: a delete under way is seen done, and restores take their
      // folders' name locks one at a time
      app.service.folder.lockTree()
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

    // An asset recycled from Triage has no folder
    if !asset.isTriaged then
      // Its ancestors top-down (from the top-level folder to the parent), then its own folder, so the tree stays consistent
      val ancestors: List[Folder] = app.service.folder.getAncestors(asset.folderId)
      ancestors.foreach(ancestor => app.service.folder.restoreRecycled(ancestor): Unit)

      app.service.folder.restoreRecycled(app.service.folder.getById(asset.folderId)): Unit

  /**
   * Moves assets into a folder, which also takes them out of the Trash or Triage. The update clears every moved asset's Sequence:
   * a sorted asset never carries one, so a Sequence whose last member moves is gone, and an asset recycled from Triage keeps its
   * Sequence until moved into a folder. An asset moved out of the Trash is restored, taking what recycling it would.
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
   * Recycles the live assets among the IDs in one transaction, leaving out the rest; one asset the account may not recycle
   * (`AssetRules.requireRecycle`) recycles nothing
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

  /** Hands assets marked for purging to the purge queue, with the context account, if any, as the pipeline's */
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
        val pending = app.service.asset.getPurgePending

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
   * Runs the operation in each repository's context, restoring the caller's after. The operation opens its own transactions, so
   * work it does after a commit, such as deleting files, follows that commit.
   */
  def forEachRepository(operation: Repository => Unit): Unit =
    app.service.repository.getAll.foreach {
      repository => RequestContext.repository.withValue(Some(repository))(operation(repository))
    }
