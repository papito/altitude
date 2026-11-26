package altitude.core.service

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.Validators
import altitude.core.dao.AlbumDao
import altitude.core.models.Album

/**
 * Nothing here writes an asset: album writes change membership rows only, an add share-locking the assets it adds. A recycle
 * (`LibraryService.recycleAssets`) drops a recycled asset from every album, and purging cascades the memberships.
 */
class AlbumService(val app: Altitude) extends BaseService[Album]:
  override protected val dao: AlbumDao = app.DAO.album

  private def validName(name: String): String =
    Validators.validName(name, Api.Field.Album.NAME, Api.Constraints.MAX_ALBUM_NAME_LENGTH)

  def add(name: String): Album =
    txManager.withTransaction {
      logger.debug(s"Adding album [$name]")
      add(Album(name = validName(name.trim)))
    }

  def getAll: List[Album] =
    txManager.asReadOnly {
      dao.getAll
    }

  def rename(albumId: String, newName: String): Album =
    txManager.withTransaction {
      val album: Album = getById(albumId)
      // The new name passes the same rule as a new album's, and the copy derives its name key
      val renamed = album.copy(name = validName(newName.trim))

      logger.debug(s"Renaming album [${album.name}] to [${renamed.name}]")
      updateById(albumId, Map(FieldConst.Album.NAME -> renamed.name, FieldConst.Album.NAME_KEY -> renamed.nameKey))
      renamed
    }

  def addAssets(albumId: String, assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      // Scopes the album to the repository
      getById(albumId)

      // A recycle of one of them meanwhile either waits for this add and then drops the member, or is waited for, and the asset
      // is then left out
      app.service.asset.shareLockLive(assetIds)
      val added = dao.addAssets(albumId, assetIds)
      logger.debug(s"Added $added of ${assetIds.size} asset(s) to album [$albumId]")
      added
    }

  /** In one transaction, so a failed add leaves no empty album behind; returns the album and how many assets it added */
  def addAssetsToNewAlbum(name: String, assetIds: Set[String]): (Album, Int) =
    txManager.withTransaction {
      val album: Album = add(name)
      val added = addAssets(album.persistedId, assetIds)
      logger.info(s"Created album [${album.name}] with $added asset(s)")
      (album, added)
    }

  def removeAssets(albumId: String, assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      // Scopes the album to the repository
      getById(albumId)

      val removed = dao.removeAssets(albumId, assetIds)
      logger.debug(s"Removed $removed asset(s) from album [$albumId]")
      removed
    }

  def removeAssetsFromAllAlbums(assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      val removed = dao.removeAssetsFromAllAlbums(assetIds)
      logger.trace(s"Removed $removed album membership(s) for assets [${assetIds.mkString(",")}]")
      removed
    }

  def getAssetIds(albumId: String): Set[String] =
    txManager.asReadOnly {
      dao.getAssetIds(albumId)
    }

  def getAssetsInAnyAlbum(assetIds: Set[String]): Set[String] =
    txManager.asReadOnly {
      dao.getAssetsInAnyAlbum(assetIds)
    }

  def getAlbumsOfAsset(assetId: String): List[Album] =
    txManager.asReadOnly {
      dao.getAlbumsOfAsset(assetId)
    }
