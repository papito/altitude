package altitude.core.service

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.Validators
import altitude.core.dao.AlbumDao
import altitude.core.models.Album

/**
 * Albums are flat, repository-scoped lists of pointers to assets. Nothing here touches an asset: adding to, removing from, or
 * deleting an album changes membership rows only. The reverse direction lives in `LibraryService.recycleAssets`, which drops a
 * recycled asset from every album; purging deletes the asset row and the schema cascades the memberships.
 */
class AlbumService(val app: Altitude) extends BaseService[Album]:
  override protected val dao: AlbumDao = app.DAO.album

  /** An album name as stored, once it passes the name rule (`Validators.validName`) */
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
      // The copy validates the new name the same way a new album is validated
      val renamed = album.copy(name = validName(newName.trim))

      logger.debug(s"Renaming album [${album.name}] to [${renamed.name}]")
      updateById(albumId, Map(FieldConst.Album.NAME -> renamed.name, FieldConst.Album.NAME_KEY -> renamed.nameKey))
      renamed
    }

  def addAssets(albumId: String, assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      // Fails with NotFoundException for an unknown album, or another repository's
      getById(albumId)

      val added = dao.addAssets(albumId, assetIds)
      logger.debug(s"Added $added of ${assetIds.size} asset(s) to album [$albumId]")
      added
    }

  /**
   * Creates the album `name` holding `assetIds`, in one transaction: a duplicate name fails with `DuplicateException` before any
   * membership is written, and a failed add leaves no empty album behind. Returns the album and how many assets it added.
   */
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
      // Fails with NotFoundException for an unknown album, or another repository's
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

  /** The albums holding the asset, by name, for the Detail panel; see `AlbumDao.getAlbumsOfAsset` */
  def getAlbumsOfAsset(assetId: String): List[Album] =
    txManager.asReadOnly {
      dao.getAlbumsOfAsset(assetId)
    }
