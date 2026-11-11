package altitude.core.integration

import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.*
import altitude.core.models.*
import altitude.core.util.Query

/**
 * The rules that depend on the asset, which the services check in the transaction of the write: Curate own against the owner,
 * recycling and restoring against a triaged or sorted asset
 */
@DoNotDiscover class AssetPermissionTests(override val testApp: Altitude) extends IntegrationTestCore {

  /** The context's account, which every fixture asset but `theirs` belongs to */
  private def me: User = testContext.users.head

  /** Another account of the instance, owning the assets `theirs` makes */
  private lazy val other: User = testContext.persistUser()

  private def mine(isTriaged: Boolean): Asset =
    testContext.insertAsset(testContext.makeAsset(user = Some(me), isTriaged = isTriaged))

  private def theirs(isTriaged: Boolean): Asset =
    testContext.insertAsset(testContext.makeAsset(user = Some(other), isTriaged = isTriaged))

  private def ids(assets: Asset*): Set[String] = assets.map(_.persistedId).toSet

  private def recycledIds: Set[String] = testApp.service.asset.queryRecycled(new Query()).records.map(_.persistedId).toSet

  private def as[T](permissions: Permission*)(f: => T): T =
    RequestContext.permissions.withValue(Permissions.of(permissions*))(f)

  test("Recycling a sorted asset takes Recycle, and a triaged one a Curate Permission") {

    /**
     * Setup:
     *
     * The context's account, a sorted asset and a triaged one of its own, and a triaged asset of another account.
     *
     * Assertions:
     *
     * Holding Recycle alone, the account recycles the sorted asset but not the triaged one; holding Curate own, it recycles its
     * own triaged asset but not the other account's; holding Curate all, it recycles the other account's triaged asset but not a
     * sorted one. A refusal is a `ForbiddenException`.
     *
     * Edge cases:
     *
     * A refused asset fails the whole recycle: the assets sent with it that the account could recycle stay live.
     */
    val sorted = mine(isTriaged = false)
    val triaged = mine(isTriaged = true)
    val othersTriaged = theirs(isTriaged = true)
    val sorted2 = mine(isTriaged = false)

    an[ForbiddenException] should be thrownBy as(Permission.Recycle)(testApp.service.library.recycleAssets(ids(sorted, triaged)))
    recycledIds shouldBe empty

    as(Permission.Recycle)(testApp.service.library.recycleAssets(ids(sorted)))
    recycledIds shouldBe ids(sorted)

    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(
      testApp.service.library.recycleAssets(ids(triaged, othersTriaged)))
    recycledIds shouldBe ids(sorted)

    as(Permission.CurateOwn)(testApp.service.library.recycleAssets(ids(triaged)))
    recycledIds shouldBe ids(sorted, triaged)

    an[ForbiddenException] should be thrownBy as(Permission.CurateAll)(
      testApp.service.library.recycleAssets(ids(othersTriaged, sorted2)))
    recycledIds shouldBe ids(sorted, triaged)

    as(Permission.CurateAll)(testApp.service.library.recycleAssets(ids(othersTriaged)))
    recycledIds shouldBe ids(sorted, triaged, othersTriaged)
  }

  test("Restoring an asset takes what recycling it would") {

    /**
     * Setup:
     *
     * The context's account and, recycled, a sorted asset of its own, a triaged one of its own and a triaged one of another
     * account.
     *
     * Assertions:
     *
     * Holding Curate own, the account restores its own asset recycled from triage but neither the other account's nor the one
     * recycled from a folder; holding Recycle, it restores the one recycled from a folder; holding Curate all, the other
     * account's.
     *
     * Edge cases:
     *
     * A refused asset fails the whole restore: the assets sent with it stay in the trash. Moving an asset out of the trash into a
     * folder restores it, and holding Organize alone is refused it.
     */
    val sorted = mine(isTriaged = false)
    val triaged = mine(isTriaged = true)
    val othersTriaged = theirs(isTriaged = true)
    testApp.service.library.recycleAssets(ids(sorted, triaged, othersTriaged))

    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(
      testApp.service.library.restoreRecycledAssets(ids(triaged, othersTriaged)))
    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn, Permission.CurateAll)(
      testApp.service.library.restoreRecycledAssets(ids(triaged, sorted)))
    an[ForbiddenException] should be thrownBy as(Permission.Organize)(
      testApp.service.library.moveAssetsToFolder(ids(sorted), RequestContext.getRepository.rootFolderId))
    recycledIds shouldBe ids(sorted, triaged, othersTriaged)

    as(Permission.CurateOwn)(testApp.service.library.restoreRecycledAssets(ids(triaged))).restored shouldBe ids(triaged)
    as(Permission.Recycle)(testApp.service.library.restoreRecycledAssets(ids(sorted))).restored shouldBe ids(sorted)
    as(Permission.CurateAll)(testApp.service.library.restoreRecycledAssets(ids(othersTriaged))).restored shouldBe
      ids(othersTriaged)
    recycledIds shouldBe empty
  }

  test("Another Library's asset is not found by a recycle or a restore, whatever the account holds") {

    /**
     * Setup:
     *
     * The context's account holding no Permission, and a recycled asset and a live one in another Library.
     *
     * Assertions:
     *
     * Restoring the other Library's recycled asset is not found (`NotFoundException`), as an unknown asset is, never refused for
     * a Permission, and recycling the other Library's live asset changes nothing and is not refused either: the assets are read
     * in the context Library before any is checked.
     */
    val home = RequestContext.getRepository
    val otherLibrary = testContext.persistRepository()
    switchContextRepo(otherLibrary)
    val foreignRecycled = testContext.insertAsset(testContext.makeAsset(repository = Some(otherLibrary), user = Some(me)))
    val foreignLive = testContext.insertAsset(testContext.makeAsset(repository = Some(otherLibrary), user = Some(me)))
    testApp.service.library.recycleAssets(ids(foreignRecycled))
    switchContextRepo(home)

    an[NotFoundException] should be thrownBy as()(testApp.service.library.restoreRecycledAssets(ids(foreignRecycled)))
    an[NotFoundException] should be thrownBy as()(testApp.service.library.restoreRecycledAssets(Set(UUID.randomUUID.toString)))
    as()(testApp.service.library.recycleAssets(ids(foreignLive)))

    switchContextRepo(otherLibrary)
    recycledIds shouldBe ids(foreignRecycled)
  }

  test("Deleting a folder that holds assets takes Recycle") {

    /**
     * Setup:
     *
     * The context's account, a folder holding a sorted asset, and an empty folder.
     *
     * Assertions:
     *
     * Without Recycle, deleting the folder that holds the asset is refused (`ForbiddenException`) and leaves the folder and the
     * asset as they were; with Recycle it recycles both.
     *
     * Edge cases:
     *
     * The empty folder is deleted without Recycle: there is nothing in it to recycle.
     */
    val folder = testApp.service.folder.add("Full")
    val emptyFolder = testApp.service.folder.add("Empty")
    val asset = testContext.insertAsset(testContext.makeAsset(user = Some(me), folder = Some(folder)))

    an[ForbiddenException] should be thrownBy as(Permission.Organize)(
      testApp.service.library.deleteFolderById(folder.persistedId))
    testApp.service.folder.getById(folder.persistedId).isRecycled shouldBe false
    recycledIds shouldBe empty

    as(Permission.Organize)(testApp.service.library.deleteFolderById(emptyFolder.persistedId))
    testApp.service.folder.getById(emptyFolder.persistedId).isRecycled shouldBe true

    as(Permission.Organize, Permission.Recycle)(testApp.service.library.deleteFolderById(folder.persistedId))
    recycledIds shouldBe ids(asset)
  }

  test("Curate own resolves and reopens a Curation set only of the account's own frames") {

    /**
     * Setup:
     *
     * The context's account holding Curate own, two triaged frames of its own and one of another account.
     *
     * Assertions:
     *
     * Resolving a set with the other account's frame among the recycled ones, or kept, is refused (`ForbiddenException`) and
     * recycles nothing; a set of its own frames resolves. Reopening it with the other account's frame named as a Pick is refused
     * and restores nothing; reopened as resolved, it restores the recycled frame.
     *
     * Edge cases:
     *
     * Curate all resolves the set with the other account's frame.
     */
    val keep = mine(isTriaged = true)
    val drop = mine(isTriaged = true)
    val others = theirs(isTriaged = true)
    val curation = testApp.service.curation

    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(curation.resolve(ids(keep), ids(drop, others)))
    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(curation.resolve(ids(others), ids(drop)))
    recycledIds shouldBe empty

    as(Permission.CurateOwn)(curation.resolve(ids(keep), ids(drop)))
    recycledIds shouldBe ids(drop)

    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(curation.reopen(ids(keep, others), ids(drop), Map.empty))
    recycledIds shouldBe ids(drop)

    as(Permission.CurateOwn)(curation.reopen(ids(keep), ids(drop), Map.empty)).restored shouldBe ids(drop)

    as(Permission.CurateAll)(curation.resolve(ids(others), ids(drop)))
    recycledIds shouldBe ids(drop)
  }

  test("Reopening a Curation set checks every triaged Pick of the Library, one in the trash included") {

    /**
     * Setup:
     *
     * The context's account holding Curate own, and a triaged frame of another account, in a Sequence and in the trash.
     *
     * Assertions:
     *
     * Reopening a set with that frame as its Pick, to be put back in its Sequence, is refused (`ForbiddenException`): the rule is
     * checked over every triaged asset of the Library among the Picks, live or not, as `setSequence` writes each of them.
     */
    val others = theirs(isTriaged = true)
    val sequenceId = UUID.randomUUID.toString
    testContext.setAssetSequence(List(others), sequenceId)
    testApp.service.library.recycleAssets(ids(others))

    an[ForbiddenException] should be thrownBy as(Permission.CurateOwn)(
      testApp.service.curation.reopen(ids(others), Set.empty, Map(others.persistedId -> sequenceId)))
  }

  test("Curate own is shown only its own frames in Curation") {

    /**
     * Setup:
     *
     * The context's account, a Sequence of two triaged frames of its own and one of another account, and a Sequence of one frame
     * of its own and two of the other account.
     *
     * Assertions:
     *
     * Holding Curate own alone, the account's queue holds the first Sequence alone, counted by its own two frames; the first
     * Sequence's set holds its two frames, the second has too few to compare, a selection of all six frames holds its three, and
     * the Detail panel's set size of its frame in the first Sequence is two.
     *
     * Edge cases:
     *
     * Holding Curate all, the queue holds both Sequences with all their frames.
     */
    val first = UUID.randomUUID.toString
    val second = UUID.randomUUID.toString
    val firstFrames = List(mine(isTriaged = true), mine(isTriaged = true), theirs(isTriaged = true))
    val secondFrames = List(mine(isTriaged = true), theirs(isTriaged = true), theirs(isTriaged = true))
    testContext.setAssetSequence(firstFrames, first)
    testContext.setAssetSequence(secondFrames, second)
    val curation = testApp.service.curation
    def frameIds(set: Option[CurationSet]) = set.toList.flatMap(_.frames).map(_.asset.persistedId).toSet

    as(Permission.CurateOwn) {
      curation.queue().map(queued => queued.sequenceId -> queued.members) shouldBe List(first -> 2)
      curation.countQueue() shouldBe 1
      frameIds(curation.sequenceSet(first)) shouldBe ids(firstFrames.take(2)*)
      curation.sequenceSet(second) shouldBe None
      frameIds(curation.selectionSet(ids(firstFrames ++ secondFrames*))) shouldBe ids(firstFrames.take(2) :+ secondFrames.head*)
      curation.setSizeOf(testApp.service.asset.getById(firstFrames.head.persistedId)) shouldBe Some(2)
    }

    as(Permission.CurateAll) {
      curation.queue().map(_.members).toSet shouldBe Set(3)
      curation.countQueue() shouldBe 2
    }
  }
}
