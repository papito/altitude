package altitude.core.service

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.dao.LocationDao
import altitude.core.models.Location
import altitude.core.models.LocationKind
import altitude.core.util.Query

/**
 * Parents and Locations, one table and one name pool per repository (see [[Location]]). Like albums, nothing here touches an
 * asset: membership rows are all that change. The reverse direction lives in `LibraryService.recycleAssets`, which drops a
 * recycled asset from every Location; purging deletes the asset row and the schema cascades the memberships. Every lookup is
 * scoped to the context repository, so a foreign ID is simply not found.
 */
class LocationService(val app: Altitude) extends BaseService[Location]:
  override protected val dao: LocationDao = app.DAO.location

  def addLocation(
      name: String,
      latitude: Double,
      longitude: Double,
      parentId: Option[String] = None,
      radiusM: Option[Int] = None): Location =
    txManager.withTransaction {
      parentId.foreach(requireParent)
      logger.info(s"Adding location [$name] at [$latitude, $longitude]" + parentId.fold("")(id => s" under [$id]"))
      add(
        Location(
          name = name.trim,
          kind = LocationKind.Location,
          parentId = parentId,
          latitude = Some(latitude),
          longitude = Some(longitude),
          radiusM = radiusM))
    }

  def addParent(name: String): Location =
    txManager.withTransaction {
      logger.info(s"Adding location parent [$name]")
      add(Location(name = name.trim, kind = LocationKind.Parent))
    }

  /** Scoped to the context repository: another repository's row is not found */
  override def getById(id: String): Location =
    getOneByQuery(new Query().add(FieldConst.ID -> id).withRepository())

  def getAll: List[Location] =
    txManager.asReadOnly {
      dao.getAll
    }

  def rename(id: String, newName: String): Location =
    txManager.withTransaction {
      val location: Location = getById(id)
      // The copy validates the new name the same way a new row is validated
      val renamed = location.copy(name = newName.trim)

      logger.info(s"Renaming location [${location.name}] to [${renamed.name}]")
      updateById(id, Map(FieldConst.Location.NAME -> renamed.name, FieldConst.Location.NAME_LC -> renamed.nameLowercase))
      renamed
    }

  /** Moves a Location under a parent, or to the top level with `None`. Parents stay where they are: one level only. */
  def moveToParent(id: String, parentId: Option[String]): Location =
    txManager.withTransaction {
      val location: Location = getById(id)
      if location.kind != LocationKind.Location then throw IllegalOperationException(s"Only a Location can be moved: $id")
      parentId.foreach(requireParent)

      logger.info(s"Moving location [${location.name}] to " + parentId.fold("the top level")(id => s"parent [$id]"))
      updateById(id, Map(FieldConst.Location.PARENT_ID -> parentId))
      location.copy(parentId = parentId)
    }

  /** A hard delete of either kind. A parent's Locations move to the top level first, in the same transaction. */
  override def deleteById(id: String): Int =
    txManager.withTransaction {
      val location: Location = getById(id)

      if location.kind == LocationKind.Parent then
        val moved = dao.moveChildrenToRoot(id)
        logger.info(s"Deleting location parent [${location.name}], moving $moved location(s) to the top level")
      else logger.info(s"Deleting location [${location.name}]")

      val deleted = super.deleteById(id)
      if deleted == 0 then throw NotFoundException(s"Location $id not found")
      deleted
    }

  def addAssets(locationId: String, assetIds: Set[String]): Int =
    txManager.withTransaction {
      requireLocation(locationId)
      if assetIds.isEmpty then 0
      else
        val added = dao.addAssets(locationId, assetIds)
        logger.info(s"Added $added of ${assetIds.size} asset(s) to location [$locationId]")
        added
    }

  def removeAssets(locationId: String, assetIds: Set[String]): Int =
    txManager.withTransaction {
      requireLocation(locationId)
      if assetIds.isEmpty then 0
      else
        val removed = dao.removeAssets(locationId, assetIds)
        logger.info(s"Removed $removed asset(s) from location [$locationId]")
        removed
    }

  def removeAssetsFromAllLocations(assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      val removed = dao.removeAssetsFromAllLocations(assetIds)
      logger.debug(s"Removed $removed location membership(s) for assets [${assetIds.mkString(",")}]")
      removed
    }

  def getAssetIds(locationId: String): Set[String] =
    txManager.asReadOnly {
      dao.getAssetIds(locationId)
    }

  /** The row must exist in this repository and be a parent - the only thing a Location can be put under */
  private def requireParent(id: String): Unit =
    if (getById(id): Location).kind != LocationKind.Parent then throw IllegalOperationException(s"Not a location parent: $id")

  /** The row must exist in this repository and be a Location - parents hold no assets */
  private def requireLocation(id: String): Unit =
    if (getById(id): Location).kind != LocationKind.Location then
      throw IllegalOperationException(s"Not a location, cannot hold assets: $id")
