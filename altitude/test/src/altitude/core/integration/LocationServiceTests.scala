package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ shouldBe, shouldEqual }

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Location
import altitude.core.models.LocationKind
import altitude.core.models.Repository

@DoNotDiscover class LocationServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val paris = (48.8566, 2.3522)

  private def addLocation(name: String, categoryId: Option[String] = None): Location =
    testApp.service.location.addLocation(name, paris._1, paris._2, categoryId)

  private def locationCounts: Map[String, Int] =
    testApp.service.location.getAll.map(location => location.persistedId -> location.numOfAssets).toMap

  private def listedNames: List[String] = testApp.service.location.getAll.map(_.name)

  test("Names are trimmed and cannot be empty, for Locations and categories") {
    intercept[ValidationException] {
      addLocation("")
    }
    intercept[ValidationException] {
      addLocation(" \t ")
    }
    intercept[ValidationException] {
      testApp.service.location.addCategory("  ")
    }

    val location: Location = addLocation("  Eiffel Tower  ")
    location.name shouldEqual "Eiffel Tower"
    location.kind shouldEqual LocationKind.Location
    location.latitude shouldEqual Some(paris._1)
    location.longitude shouldEqual Some(paris._2)

    val category: Location = testApp.service.location.addCategory("  France  ")
    category.name shouldEqual "France"
    category.kind shouldEqual LocationKind.Category
    category.latitude shouldEqual None
    category.longitude shouldEqual None
  }

  test("Categories and Locations share one case-insensitive name pool per repository") {
    val location: Location = addLocation("Paris")
    val category: Location = testApp.service.location.addCategory("France")

    // Same name as a Location, as a category, and the other way around
    intercept[DuplicateException] {
      testApp.service.location.addCategory("paris")
    }
    intercept[DuplicateException] {
      addLocation("FRANCE")
    }
    intercept[DuplicateException] {
      testApp.service.location.rename(location.persistedId, "france")
    }
    intercept[DuplicateException] {
      testApp.service.location.rename(category.persistedId, "PARIS")
    }

    // Changing only the casing of a row's own name is allowed
    testApp.service.location.rename(location.persistedId, "PARIS")
    (testApp.service.location.getById(location.persistedId): Location).name shouldEqual "PARIS"
  }

  test("A Location needs a pin in range") {
    intercept[ValidationException] {
      testApp.service.location.addLocation("north", 90.001, 0)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("south", -91, 0)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("east", 0, 180.5)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("nan", Double.NaN, 0)
    }

    // The edges of the range are fine
    val edge: Location = testApp.service.location.addLocation("edge", -90, 180)
    val stored: Location = testApp.service.location.getById(edge.persistedId)
    stored.latitude shouldEqual Some(-90.0)
    stored.longitude shouldEqual Some(180.0)

    // The model itself keeps the two kinds honest
    intercept[ValidationException] {
      Location(name = "pinless", kind = LocationKind.Location)
    }
    intercept[ValidationException] {
      Location(name = "pinned category", kind = LocationKind.Category, latitude = Some(1), longitude = Some(1))
    }
    intercept[ValidationException] {
      Location(name = "nested category", kind = LocationKind.Category, categoryId = Some("x"))
    }
  }

  test("A Location can be added under a category, and only under a category") {
    val category: Location = testApp.service.location.addCategory("France")
    val sibling: Location = addLocation("Lyon")

    val location: Location = addLocation("Paris", categoryId = Some(category.persistedId))
    location.categoryId shouldEqual Some(category.persistedId)

    intercept[IllegalOperationException] {
      addLocation("Nested", categoryId = Some(sibling.persistedId))
    }
    intercept[NotFoundException] {
      addLocation("Orphan", categoryId = Some("bogus"))
    }

    val listed = testApp.service.location.getAll.find(_.persistedId == location.persistedId).get
    listed.categoryName shouldEqual Some("France")
    sibling.categoryName shouldEqual None
  }

  test("Move a Location to a category and back to the top level") {
    val category: Location = testApp.service.location.addCategory("France")
    val otherCategory: Location = testApp.service.location.addCategory("Italy")
    val location: Location = addLocation("Paris")

    testApp.service.location.moveToCategory(location.persistedId, Some(category.persistedId))
    (testApp.service.location.getById(location.persistedId): Location).categoryId shouldEqual Some(category.persistedId)

    testApp.service.location.moveToCategory(location.persistedId, Some(otherCategory.persistedId))
    (testApp.service.location.getById(location.persistedId): Location).categoryId shouldEqual Some(otherCategory.persistedId)

    testApp.service.location.moveToCategory(location.persistedId, None)
    (testApp.service.location.getById(location.persistedId): Location).categoryId shouldEqual None

    // Categories are one level deep: a category cannot be moved, and nothing can be moved under a Location
    intercept[IllegalOperationException] {
      testApp.service.location.moveToCategory(category.persistedId, Some(otherCategory.persistedId))
    }
    intercept[IllegalOperationException] {
      testApp.service.location.moveToCategory(location.persistedId, Some(addLocation("Lyon").persistedId))
    }
    intercept[IllegalOperationException] {
      testApp.service.location.moveToCategory(location.persistedId, Some(location.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.moveToCategory(location.persistedId, Some("bogus"))
    }
    intercept[NotFoundException] {
      testApp.service.location.moveToCategory("bogus", Some(category.persistedId))
    }
  }

  test("Rename a Location and a category") {
    val location: Location = addLocation("before")
    val category: Location = testApp.service.location.addCategory("old category")

    testApp.service.location.rename(location.persistedId, " after ")
    testApp.service.location.rename(category.persistedId, "new category")

    (testApp.service.location.getById(location.persistedId): Location).name shouldEqual "after"
    (testApp.service.location.getById(category.persistedId): Location).name shouldEqual "new category"

    intercept[NotFoundException] {
      testApp.service.location.rename("bogus", "name")
    }
  }

  test("Deleting a category moves its Locations to the top level") {
    val category: Location = testApp.service.location.addCategory("France")
    val paris: Location = addLocation("Paris", categoryId = Some(category.persistedId))
    val lyon: Location = addLocation("Lyon", categoryId = Some(category.persistedId))
    val asset: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(paris.persistedId, Set(asset.persistedId))

    testApp.service.location.deleteById(category.persistedId)

    intercept[NotFoundException] {
      testApp.service.location.getById(category.persistedId)
    }
    val remaining = testApp.service.location.getAll
    remaining.map(_.name) shouldEqual List("Lyon", "Paris")
    remaining.map(_.categoryId) shouldEqual List(None, None)
    remaining.map(_.categoryName) shouldEqual List(None, None)
    // The Locations keep their memberships
    testApp.service.location.getAssetIds(paris.persistedId) shouldEqual Set(asset.persistedId)
    lyon.persistedId.nonEmpty shouldBe true
  }

  test("Deleting a Location removes its memberships and leaves the assets alone") {
    val location: Location = addLocation("doomed")
    val other: Location = addLocation("other")
    val asset: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))
    testApp.service.location.addAssets(other.persistedId, Set(asset.persistedId))

    testApp.service.location.deleteById(location.persistedId)

    intercept[NotFoundException] {
      testApp.service.location.getById(location.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.location.deleteById(location.persistedId)
    }

    val stillThere: Asset = testApp.service.asset.getById(asset.persistedId)
    stillThere.isRecycled shouldBe false
    testApp.service.location.getAssetIds(other.persistedId) shouldEqual Set(asset.persistedId)
  }

  test("Adding assets to a Location is idempotent and an asset can be in many Locations") {
    val location1: Location = addLocation("one")
    val location2: Location = addLocation("two")
    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()

    testApp.service.location.addAssets(location1.persistedId, Set(asset1.persistedId)) shouldEqual 1
    // asset1 is already there - only asset2 is new
    testApp.service.location.addAssets(location1.persistedId, Set(asset1.persistedId, asset2.persistedId)) shouldEqual 1
    testApp.service.location.addAssets(location2.persistedId, Set(asset1.persistedId)) shouldEqual 1
    testApp.service.location.addAssets(location2.persistedId, Set()) shouldEqual 0

    testApp.service.location.getAssetIds(location1.persistedId) shouldEqual Set(asset1.persistedId, asset2.persistedId)
    testApp.service.location.getAssetIds(location2.persistedId) shouldEqual Set(asset1.persistedId)

    // The asset itself is untouched: still in its folder, still not recycled
    val asset: Asset = testApp.service.asset.getById(asset1.persistedId)
    asset.folderId shouldEqual testContext.repository.rootFolderId
    asset.isRecycled shouldBe false
  }

  test("Recycled and unknown assets are not added, and a category holds no assets") {
    val location: Location = addLocation("location")
    val category: Location = testApp.service.location.addCategory("category")
    val asset: Asset = testContext.persistAsset()
    val recycled: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val added =
      testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId, recycled.persistedId, "bogus"))

    added shouldEqual 1
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(asset.persistedId)

    intercept[IllegalOperationException] {
      testApp.service.location.addAssets(category.persistedId, Set(asset.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.addAssets("bogus", Set(asset.persistedId))
    }
    testApp.service.location.getAssetIds(category.persistedId) shouldEqual Set()
  }

  test("Removing assets from a Location leaves the assets alone") {
    val location: Location = addLocation("location")
    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(asset1.persistedId, asset2.persistedId))

    testApp.service.location.removeAssets(location.persistedId, Set(asset1.persistedId)) shouldEqual 1
    // Removing an asset that is not there is a no-op
    testApp.service.location.removeAssets(location.persistedId, Set(asset1.persistedId)) shouldEqual 0

    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(asset2.persistedId)
    (testApp.service.asset.getById(asset1.persistedId): Asset).isRecycled shouldBe false
  }

  test("Recycling an asset removes it from every Location and restoring does not add it back") {
    val location1: Location = addLocation("one")
    val location2: Location = addLocation("two")
    val asset: Asset = testContext.persistAsset()
    val keeper: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location1.persistedId, Set(asset.persistedId, keeper.persistedId))
    testApp.service.location.addAssets(location2.persistedId, Set(asset.persistedId))

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    testApp.service.location.getAssetIds(location1.persistedId) shouldEqual Set(keeper.persistedId)
    testApp.service.location.getAssetIds(location2.persistedId) shouldEqual Set()

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    testApp.service.location.getAssetIds(location1.persistedId) shouldEqual Set(keeper.persistedId)
    testApp.service.location.getAssetIds(location2.persistedId) shouldEqual Set()
  }

  test("Deleting a folder removes its assets from Locations") {
    val location: Location = addLocation("location")
    val folder: Folder = testApp.service.folder.add("folder")
    val inFolder: Asset = testContext.persistAsset(folder = Some(folder))
    val atRoot: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(inFolder.persistedId, atRoot.persistedId))

    testApp.service.library.deleteFolderById(folder.persistedId)

    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(atRoot.persistedId)
  }

  test("Deleting an asset row removes its Location memberships through the schema") {
    val location: Location = addLocation("location")
    val asset: Asset = testContext.persistAsset()
    val keeper: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId, keeper.persistedId))

    // What the purge pipeline and dangling-asset pruning do: delete the row itself
    testApp.service.asset.deleteById(asset.persistedId)

    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(keeper.persistedId)
    locationCounts(location.persistedId) shouldEqual 1
  }

  test("Locations are listed by path, a category before its Locations, with counts and category names") {
    val italy: Location = testApp.service.location.addCategory("Italy")
    val france: Location = testApp.service.location.addCategory("france")
    val rome: Location = addLocation("Rome", categoryId = Some(italy.persistedId))
    // Sorts before its category by name alone, so the order has to put the category first explicitly
    val alba: Location = addLocation("Alba", categoryId = Some(italy.persistedId))
    val paris: Location = addLocation("paris", categoryId = Some(france.persistedId))
    val geneva: Location = addLocation("Geneva")
    val zurich: Location = addLocation("Zurich")

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(rome.persistedId, Set(asset1.persistedId, asset2.persistedId))
    testApp.service.location.addAssets(geneva.persistedId, Set(asset1.persistedId))

    listedNames shouldEqual List("france", "paris", "Geneva", "Italy", "Alba", "Rome", "Zurich")

    val byId = testApp.service.location.getAll.map(location => location.persistedId -> location).toMap
    byId(rome.persistedId).categoryName shouldEqual Some("Italy")
    byId(alba.persistedId).categoryName shouldEqual Some("Italy")
    byId(paris.persistedId).categoryName shouldEqual Some("france")
    byId(geneva.persistedId).categoryName shouldEqual None
    byId(italy.persistedId).categoryName shouldEqual None

    val counts = locationCounts
    counts(rome.persistedId) shouldEqual 2
    counts(geneva.persistedId) shouldEqual 1
    counts(zurich.persistedId) shouldEqual 0
    counts(italy.persistedId) shouldEqual 0
  }

  test("Locations are scoped to the repository and foreign IDs change nothing") {
    val firstRepo: Repository = testContext.repository
    val category: Location = testApp.service.location.addCategory("shared name")
    val location: Location = addLocation("Paris", categoryId = Some(category.persistedId))
    val asset: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)

    // Same names are fine in another repository, and the first repository's rows are not visible
    val ownCategory: Location = testApp.service.location.addCategory("shared name")
    addLocation("Paris")
    listedNames shouldEqual List("Paris", "shared name")
    locationCounts.values.sum shouldEqual 0

    // Every read and mutation by a foreign ID is "not found"
    intercept[NotFoundException] {
      testApp.service.location.getById(location.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.location.rename(location.persistedId, "renamed")
    }
    intercept[NotFoundException] {
      testApp.service.location.moveToCategory(location.persistedId, None)
    }
    intercept[NotFoundException] {
      // A foreign category as the target
      addLocation("Under foreign", categoryId = Some(category.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.deleteById(category.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.location.addAssets(location.persistedId, Set(testContext.persistAsset(Some(secondRepo)).persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.removeAssets(location.persistedId, Set(asset.persistedId))
    }
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set()

    // A foreign asset in an otherwise local batch is dropped
    val ownLocation: Location = addLocation("Own", categoryId = Some(ownCategory.persistedId))
    val ownAsset: Asset = testContext.persistAsset(Some(secondRepo))
    testApp.service.location.addAssets(ownLocation.persistedId, Set(ownAsset.persistedId, asset.persistedId)) shouldEqual 1
    testApp.service.location.removeAssetsFromAllLocations(Set(asset.persistedId)) shouldEqual 0

    switchContextRepo(firstRepo)
    listedNames shouldEqual List("shared name", "Paris")
    (testApp.service.location.getById(location.persistedId): Location).categoryId shouldEqual Some(category.persistedId)
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(asset.persistedId)
    locationCounts(location.persistedId) shouldEqual 1
  }
}
