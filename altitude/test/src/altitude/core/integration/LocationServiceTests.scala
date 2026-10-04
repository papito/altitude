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

    /**
     * Setup:
     *
     * Location and category names that are empty, whitespace only, and padded with spaces; Locations are pinned at Paris.
     *
     * Assertions:
     *
     * Empty and blank names are rejected for both kinds, and a padded name is stored trimmed. A Location is stored with its pin,
     * a category without one.
     *
     * Edge cases:
     *
     * A name made only of spaces and a tab.
     */
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

    /**
     * Setup:
     *
     * A Location named "Paris" and a category named "France".
     *
     * Assertions:
     *
     * A name that differs from another row's only by case is a duplicate across the two kinds, whether added or renamed to, while
     * a row can be renamed to a different casing of its own name.
     *
     * Edge cases:
     *
     * A category taking a Location's name and the other way around, and renaming a row to its own name in another case.
     */
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

    /**
     * Setup:
     *
     * Locations pinned past each latitude and longitude bound, at NaN, and exactly at -90 / 180, plus Location models built
     * directly with the wrong fields for their kind.
     *
     * Assertions:
     *
     * Out-of-range and NaN pins are rejected while the bounds themselves are stored as given, and the model refuses a Location
     * without a pin, a category with a pin, and a category inside another category.
     *
     * Edge cases:
     *
     * Values just past the bounds, the exact bounds, and NaN.
     */
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

    /**
     * Setup:
     *
     * A category "France" and a top-level Location "Lyon"; "Paris" is added under the category.
     *
     * Assertions:
     *
     * A Location takes a category as its parent and lists with the category's name, a top-level Location lists without one, and a
     * Location or an unknown ID as the parent is refused.
     *
     * Edge cases:
     *
     * A Location as the parent, and a parent ID that does not exist.
     */
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

    val categoryNames = testApp.service.location.getAll.map(listed => listed.persistedId -> listed.categoryName).toMap
    categoryNames(location.persistedId) shouldEqual Some("France")
    categoryNames(sibling.persistedId) shouldEqual None
  }

  test("Move a Location to a category and back to the top level") {

    /**
     * Setup:
     *
     * Two categories, "France" and "Italy", and a top-level Location "Paris".
     *
     * Assertions:
     *
     * The Location moves into one category, across to the other and back to the top level. Categories stay one level deep: moving
     * a category, moving under a Location or under itself is refused, and unknown IDs on either side are not found.
     *
     * Edge cases:
     *
     * Moving a Location under itself, and unknown IDs for the row and for the target.
     */
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

    /**
     * Setup:
     *
     * A Location and a category, each renamed; the Location's new name is padded with spaces.
     *
     * Assertions:
     *
     * Both read back under their new names, trimmed, and renaming an unknown ID is not found.
     */
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

    /**
     * Setup:
     *
     * A category "France" holding the Locations "Paris" and "Lyon", with one imported asset in "Paris"; the category is deleted.
     *
     * Assertions:
     *
     * The category is gone, the same two Locations remain at the top level without a category, and they keep their asset
     * memberships.
     */
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
    remaining.map(_.persistedId) shouldEqual List(lyon.persistedId, paris.persistedId)
    remaining.map(_.categoryId) shouldEqual List(None, None)
    remaining.map(_.categoryName) shouldEqual List(None, None)
    // The Locations keep their memberships
    testApp.service.location.getAssetIds(paris.persistedId) shouldEqual Set(asset.persistedId)
  }

  test("A selection larger than a statement's parameter limit is added to and removed from a Location") {

    /**
     * Setup:
     *
     * A Location, one imported asset, and a selection of that asset among 70,000 unknown IDs, more than a PostgreSQL statement
     * takes parameters for. The selection is added to the Location, removed from it, added again and removed from every Location.
     *
     * Assertions:
     *
     * Each statement runs and counts the one asset.
     */
    val location: Location = addLocation("Paris")
    val asset: Asset = testContext.persistAsset()
    val selection = amongManyUnknownIds(Set(asset.persistedId))

    testApp.service.location.addAssets(location.persistedId, selection) shouldEqual 1
    testApp.service.location.removeAssets(location.persistedId, selection) shouldEqual 1
    testApp.service.location.addAssets(location.persistedId, selection) shouldEqual 1
    testApp.service.location.removeAssetsFromAllLocations(selection) shouldEqual 1
  }

  test("Deleting a Location removes its memberships and leaves the assets alone") {

    /**
     * Setup:
     *
     * Two Locations sharing one imported asset; the first Location is deleted.
     *
     * Assertions:
     *
     * The deleted Location is gone and deleting it again is not found, while the asset is neither recycled nor dropped from the
     * other Location.
     */
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

    /**
     * Setup:
     *
     * Two Locations and two imported assets in the root folder; one asset is added to the first Location twice and to the second
     * once.
     *
     * Assertions:
     *
     * Each add counts only the assets that were not already members, each Location holds exactly its own assets, and the asset
     * itself stays in its folder and is not recycled.
     *
     * Edge cases:
     *
     * Adding an asset that is already a member alongside a new one, and adding an empty set.
     */
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

    /**
     * Setup:
     *
     * A Location and a category, one live asset, one recycled asset and an asset ID that does not exist.
     *
     * Assertions:
     *
     * Only the live asset is added and counted, adding assets to a category is refused and leaves it empty, and adding to an
     * unknown Location is not found.
     */
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

    /**
     * Setup:
     *
     * A Location holding two imported assets; one of them is removed twice.
     *
     * Assertions:
     *
     * The first removal counts one, the Location keeps only the other asset, and the removed asset is not recycled.
     *
     * Edge cases:
     *
     * Removing an asset that is no longer a member counts zero.
     */
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

    /**
     * Setup:
     *
     * Two Locations: one holds the asset to recycle and a keeper, the other holds only the asset to recycle.
     *
     * Assertions:
     *
     * Recycling the asset drops it from both Locations, and restoring it from the recycle bin leaves both Locations as they were.
     */
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

    /**
     * Setup:
     *
     * A Location holding an asset in a folder and an asset at the root; the folder is then deleted.
     *
     * Assertions:
     *
     * Only the root asset stays in the Location.
     */
    val location: Location = addLocation("location")
    val folder: Folder = testApp.service.folder.add("folder")
    val inFolder: Asset = testContext.persistAsset(folder = Some(folder))
    val atRoot: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(inFolder.persistedId, atRoot.persistedId))

    testApp.service.library.deleteFolderById(folder.persistedId)

    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(atRoot.persistedId)
  }

  test("Deleting an asset row removes its Location memberships through the schema") {

    /**
     * Setup:
     *
     * A Location holding two assets; the row of one of them is deleted directly, the way the purge pipeline and dangling-asset
     * pruning do.
     *
     * Assertions:
     *
     * The Location drops the deleted asset by schema cascade alone, in both its asset IDs and its count.
     */
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

    /**
     * Setup:
     *
     * Categories "Italy" (holding "Rome" and "Alba") and "france" (holding "paris"), top-level Locations "Geneva" and "Zurich",
     * and two imported assets: both in "Rome", one in "Geneva".
     *
     * Assertions:
     *
     * Rows list in case-insensitive name order at the top level with each category directly followed by its own Locations, every
     * Location carries its category's name, and each row has its asset count.
     *
     * Edge cases:
     *
     * A Location whose name sorts before its category's ("Alba" under "Italy") still lists after it, lowercase names sort among
     * capitalized ones, and a category and an empty Location count zero.
     */
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

    /**
     * Setup:
     *
     * In the first repository, a category "shared name" holding a Location "Paris" with one asset. A second repository then gets
     * a category and a Location of the same names, a Location of its own under its category, and assets of its own.
     *
     * Assertions:
     *
     * Each repository sees only its own rows and counts. From the second repository, every read and mutation by the first
     * repository's IDs is not found and changes nothing, and a foreign asset mixed into a local batch is dropped. Back in the
     * first repository, its Location, category and membership are untouched.
     *
     * Edge cases:
     *
     * A foreign category as the parent of a new Location, a foreign Location with a local asset, and a foreign asset in a batch
     * of local ones.
     */
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
