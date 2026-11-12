package altitude.core.service

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.Validators
import altitude.core.dao.LocationDao
import altitude.core.models.Location
import altitude.core.models.LocationKind

/**
 * Categories and Locations, one table and one name pool per repository (see [[Location]]). Like albums, nothing here touches an
 * asset: membership rows are all that change. The reverse direction lives in `LibraryService.recycleAssets`, which drops a
 * recycled asset from every Location; purging deletes the asset row and the schema cascades the memberships. Every lookup is
 * scoped to the context repository, so a foreign ID is simply not found.
 */
class LocationService(val app: Altitude) extends BaseService[Location]:
  override protected val dao: LocationDao = app.DAO.location

  /** A Location or category name as stored, once it passes the name rule (`Validators.validName`) */
  private def validName(name: String): String =
    Validators.validName(name, Api.Field.Location.NAME, Api.Constraints.MAX_LOCATION_NAME_LENGTH)

  def addLocation(name: String, latitude: Double, longitude: Double, categoryId: Option[String] = None): Location =
    txManager.withTransaction {
      categoryId.foreach(requireCategory)
      logger.debug(s"Adding location [$name] at [$latitude, $longitude]" + categoryId.fold("")(id => s" under [$id]"))
      add(
        Location(
          name = validName(name.trim),
          kind = LocationKind.Location,
          categoryId = categoryId,
          latitude = Some(latitude),
          longitude = Some(longitude)))
    }

  def addCategory(name: String): Location =
    txManager.withTransaction {
      logger.debug(s"Adding location category [$name]")
      add(Location(name = validName(name.trim), kind = LocationKind.Category))
    }

  def getAll: List[Location] =
    txManager.asReadOnly {
      dao.getAll
    }

  /**
   * The Locations of the IDs, each with its category's name, in no order: an ID of a category, of another repository's row or of
   * none has no entry
   */
  def getLocationsByIds(ids: Set[String]): List[Location] =
    txManager.asReadOnly {
      val locations = dao.getByIds(ids).filter(_.kind == LocationKind.Location)
      val categoryNames =
        dao.getByIds(locations.flatMap(_.categoryId).toSet).map(category => category.persistedId -> category.name).toMap
      locations.map(location => location.copy(categoryName = location.categoryId.flatMap(categoryNames.get)))
    }

  def rename(id: String, newName: String): Location =
    txManager.withTransaction {
      val location: Location = getById(id)
      // The copy validates the new name the same way a new row is validated
      val renamed = location.copy(name = validName(newName.trim))

      logger.debug(s"Renaming location [${location.name}] to [${renamed.name}]")
      updateById(id, Map(FieldConst.Location.NAME -> renamed.name, FieldConst.Location.NAME_KEY -> renamed.nameKey))
      renamed
    }

  /** Moves a Location under a category, or to the top level with `None`. Categories stay where they are: one level only. */
  def moveToCategory(id: String, categoryId: Option[String]): Location =
    txManager.withTransaction {
      val location: Location = getById(id)
      if location.kind != LocationKind.Location then throw IllegalOperationException(s"Only a Location can be moved: $id")
      categoryId.foreach(requireCategory)

      logger.debug(s"Moving location [${location.name}] to " + categoryId.fold("the top level")(id => s"category [$id]"))
      updateById(id, Map(FieldConst.Location.CATEGORY_ID -> categoryId))
      location.copy(categoryId = categoryId)
    }

  /** A hard delete of either kind. A category's Locations move to the top level first, in the same transaction. */
  override def deleteById(id: String): Int =
    txManager.withTransaction {
      val location: Location = getById(id)

      if location.kind == LocationKind.Category then
        val moved = dao.moveChildrenToRoot(id)
        logger.debug(s"Deleting location category [${location.name}], moving $moved location(s) to the top level")
      else logger.debug(s"Deleting location [${location.name}]")

      super.deleteById(id)
    }

  def addAssets(locationId: String, assetIds: Set[String]): Int =
    txManager.withTransaction {
      requireLocation(locationId)
      if assetIds.isEmpty then 0
      else
        val added = dao.addAssets(locationId, assetIds)
        logger.debug(s"Added $added of ${assetIds.size} asset(s) to location [$locationId]")
        added
    }

  def removeAssets(locationId: String, assetIds: Set[String]): Int =
    txManager.withTransaction {
      requireLocation(locationId)
      if assetIds.isEmpty then 0
      else
        val removed = dao.removeAssets(locationId, assetIds)
        logger.debug(s"Removed $removed asset(s) from location [$locationId]")
        removed
    }

  def removeAssetsFromAllLocations(assetIds: Set[String]): Int =
    if assetIds.isEmpty then return 0

    txManager.withTransaction {
      val removed = dao.removeAssetsFromAllLocations(assetIds)
      logger.trace(s"Removed $removed location membership(s) for assets [${assetIds.mkString(",")}]")
      removed
    }

  def getAssetIds(locationId: String): Set[String] =
    txManager.asReadOnly {
      dao.getAssetIds(locationId)
    }

  def getAssetsInAnyLocation(assetIds: Set[String]): Set[String] =
    txManager.asReadOnly {
      dao.getAssetsInAnyLocation(assetIds)
    }

  /**
   * The Locations holding the asset, in path order with their category names, for the Detail panel; see
   * `LocationDao.getLocationsOfAsset`
   */
  def getLocationsOfAsset(assetId: String): List[Location] =
    txManager.asReadOnly {
      dao.getLocationsOfAsset(assetId)
    }

  /** The row must exist in this repository and be a category - the only thing a Location can be put under */
  private def requireCategory(id: String): Unit =
    if (getById(id): Location).kind != LocationKind.Category then throw IllegalOperationException(s"Not a location category: $id")

  /** The row must exist in this repository and be a Location - categories hold no assets */
  private def requireLocation(id: String): Unit =
    if (getById(id): Location).kind != LocationKind.Location then
      throw IllegalOperationException(s"Not a location, cannot hold assets: $id")
