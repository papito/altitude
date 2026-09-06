package altitude.core.service

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.dao.AlbumDao
import altitude.core.models.Album

/**
 * Albums are flat, repository-scoped lists of pointers to assets. Nothing here touches an asset: adding to, removing from, or
 * deleting an album changes membership rows only. The reverse direction lives in `LibraryService.recycleAssets`, which drops a
 * recycled asset from every album; purging deletes the asset row and the schema cascades the memberships.
 */
class AlbumService(val app: Altitude) extends BaseService[Album]:
  override protected val dao: AlbumDao = app.DAO.album

  def add(name: String): Album =
    txManager.withTransaction {
      logger.info(s"Adding album [$name]")
      add(Album(name = name.trim))
    }

  def getAll: List[Album] =
    txManager.asReadOnly {
      dao.getAll
    }

  def rename(albumId: String, newName: String): Album =
    txManager.withTransaction {
      val album: Album = getById(albumId)
      // The copy validates the new name the same way a new album is validated
      val renamed = album.copy(name = newName.trim)

      logger.info(s"Renaming album [${album.name}] to [${renamed.name}]")
      updateById(albumId, Map(FieldConst.Album.NAME -> renamed.name, FieldConst.Album.NAME_LC -> renamed.nameLowercase))
      renamed
    }

  override def deleteById(id: String): Int =
    txManager.withTransaction {
      logger.info(s"Deleting album [$id]")
      val deleted = super.deleteById(id)
      if deleted == 0 then throw NotFoundException(s"Album $id not found")
      deleted
    }

  def addAssets(albumId: String, assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      // Fails with NotFoundException for an unknown album
      getById(albumId)

      val added = dao.addAssets(albumId, assetIds)
      logger.info(s"Added $added of ${assetIds.size} asset(s) to album [$albumId]")
      added
    }

  def removeAssets(albumId: String, assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      val removed = dao.removeAssets(albumId, assetIds)
      logger.info(s"Removed $removed asset(s) from album [$albumId]")
      removed
    }

  def removeAssetsFromAllAlbums(assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      val removed = dao.removeAssetsFromAllAlbums(assetIds)
      logger.debug(s"Removed $removed album membership(s) for assets [${assetIds.mkString(",")}]")
      removed
    }

  def getAssetIds(albumId: String): Set[String] =
    txManager.asReadOnly {
      dao.getAssetIds(albumId)
    }
