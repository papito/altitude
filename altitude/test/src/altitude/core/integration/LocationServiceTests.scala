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

  private def addLocation(name: String, parentId: Option[String] = None, radiusM: Option[Int] = None): Location =
    testApp.service.location.addLocation(name, paris._1, paris._2, parentId, radiusM)

  private def locationCounts: Map[String, Int] =
    testApp.service.location.getAll.map(location => location.persistedId -> location.numOfAssets).toMap

  private def listedNames: List[String] = testApp.service.location.getAll.map(_.name)

  test("Names are trimmed and cannot be empty, for Locations and parents") {
    intercept[ValidationException] {
      addLocation("")
    }
    intercept[ValidationException] {
      addLocation(" \t ")
    }
    intercept[ValidationException] {
      testApp.service.location.addParent("  ")
    }

    val location: Location = addLocation("  Eiffel Tower  ")
    location.name shouldEqual "Eiffel Tower"
    location.kind shouldEqual LocationKind.Location
    location.latitude shouldEqual Some(paris._1)
    location.longitude shouldEqual Some(paris._2)
    location.radiusM shouldEqual None

    val parent: Location = testApp.service.location.addParent("  France  ")
    parent.name shouldEqual "France"
    parent.kind shouldEqual LocationKind.Parent
    parent.latitude shouldEqual None
    parent.longitude shouldEqual None
  }

  test("Parents and Locations share one case-insensitive name pool per repository") {
    val location: Location = addLocation("Paris")
    val parent: Location = testApp.service.location.addParent("France")

    // Same name as a Location, as a parent, and the other way around
    intercept[DuplicateException] {
      testApp.service.location.addParent("paris")
    }
    intercept[DuplicateException] {
      addLocation("FRANCE")
    }
    intercept[DuplicateException] {
      testApp.service.location.rename(location.persistedId, "france")
    }
    intercept[DuplicateException] {
      testApp.service.location.rename(parent.persistedId, "PARIS")
    }

    // Changing only the casing of a row's own name is allowed
    testApp.service.location.rename(location.persistedId, "PARIS")
    (testApp.service.location.getById(location.persistedId): Location).name shouldEqual "PARIS"
  }

  test("A Location needs a pin in range and a positive radius") {
    intercept[ValidationException] {
      testApp.service.location.addLocation("north", 90.001, 0, None, None)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("south", -91, 0, None, None)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("east", 0, 180.5, None, None)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("nan", Double.NaN, 0, None, None)
    }
    intercept[ValidationException] {
      testApp.service.location.addLocation("radius", 0, 0, None, Some(0))
    }

    // The edges of the range and a radius are fine
    val edge: Location = testApp.service.location.addLocation("edge", -90, 180, None, Some(250))
    val stored: Location = testApp.service.location.getById(edge.persistedId)
    stored.latitude shouldEqual Some(-90.0)
    stored.longitude shouldEqual Some(180.0)
    stored.radiusM shouldEqual Some(250)

    // The model itself keeps the two kinds honest
    intercept[ValidationException] {
      Location(name = "pinless", kind = LocationKind.Location)
    }
    intercept[ValidationException] {
      Location(name = "pinned parent", kind = LocationKind.Parent, latitude = Some(1), longitude = Some(1))
    }
    intercept[ValidationException] {
      Location(name = "nested parent", kind = LocationKind.Parent, parentId = Some("x"))
    }
  }

  test("A Location can be added under a parent, and only under a parent") {
    val parent: Location = testApp.service.location.addParent("France")
    val sibling: Location = addLocation("Lyon")

    val location: Location = addLocation("Paris", parentId = Some(parent.persistedId))
    location.parentId shouldEqual Some(parent.persistedId)

    intercept[IllegalOperationException] {
      addLocation("Nested", parentId = Some(sibling.persistedId))
    }
    intercept[NotFoundException] {
      addLocation("Orphan", parentId = Some("bogus"))
    }

    val listed = testApp.service.location.getAll.find(_.persistedId == location.persistedId).get
    listed.parentName shouldEqual Some("France")
    sibling.parentName shouldEqual None
  }

  test("Move a Location to a parent and back to the top level") {
    val parent: Location = testApp.service.location.addParent("France")
    val otherParent: Location = testApp.service.location.addParent("Italy")
    val location: Location = addLocation("Paris")

    testApp.service.location.moveToParent(location.persistedId, Some(parent.persistedId))
    (testApp.service.location.getById(location.persistedId): Location).parentId shouldEqual Some(parent.persistedId)

    testApp.service.location.moveToParent(location.persistedId, Some(otherParent.persistedId))
    (testApp.service.location.getById(location.persistedId): Location).parentId shouldEqual Some(otherParent.persistedId)

    testApp.service.location.moveToParent(location.persistedId, None)
    (testApp.service.location.getById(location.persistedId): Location).parentId shouldEqual None

    // Parents are one level deep: a parent cannot be moved, and nothing can be moved under a Location
    intercept[IllegalOperationException] {
      testApp.service.location.moveToParent(parent.persistedId, Some(otherParent.persistedId))
    }
    intercept[IllegalOperationException] {
      testApp.service.location.moveToParent(location.persistedId, Some(addLocation("Lyon").persistedId))
    }
    intercept[IllegalOperationException] {
      testApp.service.location.moveToParent(location.persistedId, Some(location.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.moveToParent(location.persistedId, Some("bogus"))
    }
    intercept[NotFoundException] {
      testApp.service.location.moveToParent("bogus", Some(parent.persistedId))
    }
  }

  test("Rename a Location and a parent") {
    val location: Location = addLocation("before")
    val parent: Location = testApp.service.location.addParent("old parent")

    testApp.service.location.rename(location.persistedId, " after ")
    testApp.service.location.rename(parent.persistedId, "new parent")

    (testApp.service.location.getById(location.persistedId): Location).name shouldEqual "after"
    (testApp.service.location.getById(parent.persistedId): Location).name shouldEqual "new parent"

    intercept[NotFoundException] {
      testApp.service.location.rename("bogus", "name")
    }
  }

  test("Deleting a parent moves its Locations to the top level") {
    val parent: Location = testApp.service.location.addParent("France")
    val paris: Location = addLocation("Paris", parentId = Some(parent.persistedId))
    val lyon: Location = addLocation("Lyon", parentId = Some(parent.persistedId))
    val asset: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(paris.persistedId, Set(asset.persistedId))

    testApp.service.location.deleteById(parent.persistedId)

    intercept[NotFoundException] {
      testApp.service.location.getById(parent.persistedId)
    }
    val remaining = testApp.service.location.getAll
    remaining.map(_.name) shouldEqual List("Lyon", "Paris")
    remaining.map(_.parentId) shouldEqual List(None, None)
    remaining.map(_.parentName) shouldEqual List(None, None)
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

  test("Recycled and unknown assets are not added, and a parent holds no assets") {
    val location: Location = addLocation("location")
    val parent: Location = testApp.service.location.addParent("parent")
    val asset: Asset = testContext.persistAsset()
    val recycled: Asset = testContext.persistAsset()
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val added =
      testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId, recycled.persistedId, "bogus"))

    added shouldEqual 1
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(asset.persistedId)

    intercept[IllegalOperationException] {
      testApp.service.location.addAssets(parent.persistedId, Set(asset.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.addAssets("bogus", Set(asset.persistedId))
    }
    testApp.service.location.getAssetIds(parent.persistedId) shouldEqual Set()
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

  test("Locations are listed by path, a parent before its Locations, with counts and parent names") {
    val italy: Location = testApp.service.location.addParent("Italy")
    val france: Location = testApp.service.location.addParent("france")
    val rome: Location = addLocation("Rome", parentId = Some(italy.persistedId))
    // Sorts before its parent by name alone, so the order has to put the parent first explicitly
    val alba: Location = addLocation("Alba", parentId = Some(italy.persistedId))
    val paris: Location = addLocation("paris", parentId = Some(france.persistedId))
    val geneva: Location = addLocation("Geneva")
    val zurich: Location = addLocation("Zurich")

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(rome.persistedId, Set(asset1.persistedId, asset2.persistedId))
    testApp.service.location.addAssets(geneva.persistedId, Set(asset1.persistedId))

    listedNames shouldEqual List("france", "paris", "Geneva", "Italy", "Alba", "Rome", "Zurich")

    val byId = testApp.service.location.getAll.map(location => location.persistedId -> location).toMap
    byId(rome.persistedId).parentName shouldEqual Some("Italy")
    byId(alba.persistedId).parentName shouldEqual Some("Italy")
    byId(paris.persistedId).parentName shouldEqual Some("france")
    byId(geneva.persistedId).parentName shouldEqual None
    byId(italy.persistedId).parentName shouldEqual None

    val counts = locationCounts
    counts(rome.persistedId) shouldEqual 2
    counts(geneva.persistedId) shouldEqual 1
    counts(zurich.persistedId) shouldEqual 0
    counts(italy.persistedId) shouldEqual 0
  }

  test("Locations are scoped to the repository and foreign IDs change nothing") {
    val firstRepo: Repository = testContext.repository
    val parent: Location = testApp.service.location.addParent("shared name")
    val location: Location = addLocation("Paris", parentId = Some(parent.persistedId))
    val asset: Asset = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)

    // Same names are fine in another repository, and the first repository's rows are not visible
    val ownParent: Location = testApp.service.location.addParent("shared name")
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
      testApp.service.location.moveToParent(location.persistedId, None)
    }
    intercept[NotFoundException] {
      // A foreign parent as the target
      addLocation("Under foreign", parentId = Some(parent.persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.deleteById(parent.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.location.addAssets(location.persistedId, Set(testContext.persistAsset(Some(secondRepo)).persistedId))
    }
    intercept[NotFoundException] {
      testApp.service.location.removeAssets(location.persistedId, Set(asset.persistedId))
    }
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set()

    // A foreign asset in an otherwise local batch is dropped
    val ownLocation: Location = addLocation("Own", parentId = Some(ownParent.persistedId))
    val ownAsset: Asset = testContext.persistAsset(Some(secondRepo))
    testApp.service.location.addAssets(ownLocation.persistedId, Set(ownAsset.persistedId, asset.persistedId)) shouldEqual 1
    testApp.service.location.removeAssetsFromAllLocations(Set(asset.persistedId)) shouldEqual 0

    switchContextRepo(firstRepo)
    listedNames shouldEqual List("shared name", "Paris")
    (testApp.service.location.getById(location.persistedId): Location).parentId shouldEqual Some(parent.persistedId)
    testApp.service.location.getAssetIds(location.persistedId) shouldEqual Set(asset.persistedId)
    locationCounts(location.persistedId) shouldEqual 1
  }
}
