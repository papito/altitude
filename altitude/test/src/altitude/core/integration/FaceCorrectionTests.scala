package altitude.core.integration

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, contain, empty, not, should, shouldBe }

import scala.util.Random
import scala.util.Try

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.service.PersonService.FaceTarget

/**
 * The Face corrections of Asset detail (`PersonService.removeFace`, `moveFace`, `nameFacePerson`, `mergeFacePerson`) and the
 * reads behind them
 */
@DoNotDiscover class FaceCorrectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val VIDEO = AssetType("video", "mp4", "video/mp4")

  private def features: Array[Float] = Array.fill(512)(Random.nextFloat())

  private def person(name: Option[String] = None): Person = testApp.service.person.addPerson(Person(name = name))

  private def getPerson(id: String): Person = testApp.service.person.getById(id)

  /** A Face of the person as stored, not the test's possibly stale model, so their cover stays their first Face, as on import */
  private def face(
      person: Person,
      asset: Asset,
      detectionScore: Double = 0.9,
      quality: Double = 20.0,
      x1: Int = 1,
      frameTimeMs: Option[Long] = None,
      withFiles: Boolean = false): Face =
    testContext.addTestFace(
      getPerson(person.persistedId),
      asset,
      features,
      detectionScore = detectionScore,
      quality = quality,
      x1 = x1,
      frameTimeMs = frameTimeMs,
      withFiles = withFiles)

  private def faceExists(faceId: String): Boolean = Try(testApp.service.person.getFaceById(faceId)).isSuccess

  private def personExists(personId: String): Boolean = Try(getPerson(personId)).isSuccess

  /** Whether the Face's four images are in the file store; a Face with some of them is a failure of its own */
  private def hasFiles(faceId: String): Boolean = {
    val store = testApp.service.fileStore
    val present = List(
      Try(store.getDisplayFaceById(faceId)),
      Try(store.getDetectedFaceById(faceId)),
      Try(store.getAlignedFaceById(faceId)),
      Try(store.getAlignedGreyscaleFaceById(faceId))
    ).count(_.isSuccess)
    withClue(s"face [$faceId] has $present of 4 files")(present should (be(0).or(be(4))))
    present == 4
  }

  test("Removing a Face takes the asset from the person's count only when they have no other Face on it") {

    /**
     * Setup:
     *
     * A Person with two Faces on one asset and a third on another, counting two assets.
     *
     * Assertions:
     *
     * Removing one Face of the shared asset leaves the count at two, since the other Face is still on it; removing the other
     * lowers it to one. The Person stays.
     */
    val p = person()
    val a1 = testContext.persistAsset()
    val a2 = testContext.persistAsset()
    val f1 = face(p, a1)
    val f2 = face(p, a1)
    val f3 = face(p, a2)
    getPerson(p.persistedId).numOfFaces shouldBe 2

    val first = testApp.service.person.removeFace(f1.persistedId)
    first.face.persistedId shouldBe f1.persistedId
    first.personDeleted shouldBe false
    faceExists(f1.persistedId) shouldBe false
    faceExists(f2.persistedId) shouldBe true
    getPerson(p.persistedId).numOfFaces shouldBe 2

    testApp.service.person.removeFace(f2.persistedId)
    getPerson(p.persistedId).numOfFaces shouldBe 1
    testApp.service.person.getAssetFaces(a1.persistedId) shouldBe empty
    testApp.service.person.getAssetFaces(a2.persistedId).map(_.persistedId) shouldBe List(f3.persistedId)
  }

  test("Removing the cover Face repicks the cover by detection score, and the removed cover's files go") {

    /**
     * Setup:
     *
     * A Person with three Faces with their files, scored 0.9 (the cover), 0.5 and 0.7.
     *
     * Assertions:
     *
     * Removing the cover makes the 0.7 Face the cover and deletes only the removed Face's files.
     */
    val p = person()
    val cover = face(p, testContext.persistAsset(), detectionScore = 0.9, withFiles = true)
    val lower = face(p, testContext.persistAsset(), detectionScore = 0.5, withFiles = true)
    val higher = face(p, testContext.persistAsset(), detectionScore = 0.7, withFiles = true)
    getPerson(p.persistedId).coverFaceId shouldBe Some(cover.persistedId)

    val removal = testApp.service.person.removeFace(cover.persistedId)

    removal.person.coverFaceId shouldBe Some(higher.persistedId)
    getPerson(p.persistedId).coverFaceId shouldBe Some(higher.persistedId)
    hasFiles(cover.persistedId) shouldBe false
    hasFiles(lower.persistedId) shouldBe true
    hasFiles(higher.persistedId) shouldBe true
  }

  test("Removing the cover Face repicks no Face on an asset marked for purging") {

    /**
     * Setup:
     *
     * A Person with a cover Face scored 0.5, one scored 0.9 on an asset marked for purging, and one scored 0.3.
     *
     * Assertions:
     *
     * Removing the cover makes the 0.3 Face the cover.
     */
    val p = person()
    val purging = testContext.insertAsset()
    val cover = face(p, testContext.insertAsset(), detectionScore = 0.5)
    face(p, purging, detectionScore = 0.9)
    val left = face(p, testContext.insertAsset(), detectionScore = 0.3)
    testApp.service.library.recycleAssets(Set(purging.persistedId))
    testApp.service.asset.updateById(purging.persistedId, Map(FieldConst.Asset.IS_PURGED -> true))

    testApp.service.person.removeFace(cover.persistedId)

    getPerson(p.persistedId).coverFaceId shouldBe Some(left.persistedId)
  }

  test("Removing a person's last Face deletes the person, named or not, and their cover's files") {

    /**
     * Setup:
     *
     * A Person named "Alice" and an unnamed Person, each with one Face with its files.
     *
     * Assertions:
     *
     * Removing either Face deletes it, its files and the Person, who is reported deleted with the name they had.
     */
    val named = person(Some("Alice"))
    val unnamed = person()
    val namedFace = face(named, testContext.persistAsset(), withFiles = true)
    val unnamedFace = face(unnamed, testContext.persistAsset(), withFiles = true)

    val namedRemoval = testApp.service.person.removeFace(namedFace.persistedId)
    namedRemoval.personDeleted shouldBe true
    namedRemoval.person.name shouldBe Some("Alice")
    personExists(named.persistedId) shouldBe false
    faceExists(namedFace.persistedId) shouldBe false
    hasFiles(namedFace.persistedId) shouldBe false

    val unnamedRemoval = testApp.service.person.removeFace(unnamedFace.persistedId)
    unnamedRemoval.personDeleted shouldBe true
    personExists(unnamed.persistedId) shouldBe false
    hasFiles(unnamedFace.persistedId) shouldBe false
  }

  test("A Face on a trashed asset keeps its person when their last live Face is removed") {

    /**
     * Setup:
     *
     * A Person with the cover Face on one asset and another Face on a second asset, which is recycled.
     *
     * Assertions:
     *
     * Removing the live Face lowers the count to zero but keeps the Person, whose Face in the trash becomes the cover.
     */
    val p = person()
    val live = face(p, testContext.persistAsset(), withFiles = true)
    val trashed = testContext.persistAsset()
    val inTrash = face(p, trashed)
    testApp.service.library.recycleAssets(Set(trashed.persistedId))
    getPerson(p.persistedId).numOfFaces shouldBe 1

    val removal = testApp.service.person.removeFace(live.persistedId)

    removal.personDeleted shouldBe false
    val kept = getPerson(p.persistedId)
    kept.numOfFaces shouldBe 0
    kept.coverFaceId shouldBe Some(inTrash.persistedId)
    hasFiles(live.persistedId) shouldBe false
  }

  test("A Face of a recycled asset is neither removed nor moved") {

    /**
     * Setup:
     *
     * A Person with a Face on a recycled asset, and another Person.
     *
     * Assertions:
     *
     * Removing or moving the Face is an illegal operation; the Face, its files and the counts are unchanged.
     */
    val p = person()
    val other = person()
    val asset = testContext.persistAsset()
    val f = face(p, asset, withFiles = true)
    face(other, testContext.persistAsset())
    testApp.service.library.recycleAssets(Set(asset.persistedId))

    intercept[IllegalOperationException](testApp.service.person.removeFace(f.persistedId))
    intercept[IllegalOperationException](
      testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(other.persistedId)))

    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(p.persistedId)
    hasFiles(f.persistedId) shouldBe true
    getPerson(p.persistedId).numOfFaces shouldBe 0
    getPerson(other.persistedId).numOfFaces shouldBe 1
  }

  test("A Face already gone is not found by either correction") {

    /**
     * Setup:
     *
     * A Person with two Faces, one of them removed, and another Person.
     *
     * Assertions:
     *
     * Removing it again, or moving it, is not found.
     */
    val p = person()
    val other = person()
    val gone = face(p, testContext.persistAsset())
    face(p, testContext.persistAsset())
    testApp.service.person.removeFace(gone.persistedId)

    intercept[NotFoundException](testApp.service.person.removeFace(gone.persistedId))
    intercept[NotFoundException](testApp.service.person.moveFace(gone.persistedId, FaceTarget.ExistingPerson(other.persistedId)))
  }

  test("Moving a Face adjusts both counts, each only when the asset is gained or lost, and repicks the source's cover") {

    /**
     * Setup:
     *
     * A source Person with two Faces on a shared asset (the best-scored one their cover) and one elsewhere; a target Person with
     * one Face elsewhere.
     *
     * Assertions:
     *
     * Moving the cover Face raises the target's count to two and leaves the source's at two, as their other Face is still on the
     * asset; the source's cover becomes their best remaining Face and the target keeps theirs. Moving the other Face lowers the
     * source's count to one and leaves the target's at two.
     */
    val source = person()
    val target = person()
    val shared = testContext.persistAsset()
    val f1 = face(source, shared, detectionScore = 0.9)
    val f2 = face(source, shared, detectionScore = 0.6)
    val f3 = face(source, testContext.persistAsset(), detectionScore = 0.8)
    val t1 = face(target, testContext.persistAsset())

    val move = testApp.service.person.moveFace(f1.persistedId, FaceTarget.ExistingPerson(target.persistedId))

    move.sourceDeleted shouldBe false
    move.target.persistedId shouldBe target.persistedId
    testApp.service.person.getFaceById(f1.persistedId).personId shouldBe Some(target.persistedId)
    getPerson(target.persistedId).numOfFaces shouldBe 2
    getPerson(target.persistedId).coverFaceId shouldBe Some(t1.persistedId)
    getPerson(source.persistedId).numOfFaces shouldBe 2
    getPerson(source.persistedId).coverFaceId shouldBe Some(f3.persistedId)

    testApp.service.person.moveFace(f2.persistedId, FaceTarget.ExistingPerson(target.persistedId))
    getPerson(source.persistedId).numOfFaces shouldBe 1
    getPerson(target.persistedId).numOfFaces shouldBe 2
    testApp.service.person.getAssetFaces(shared.persistedId).map(_.personId.get).toSet shouldBe Set(target.persistedId)
  }

  test("Moving a person's last Face deletes them, and the Face keeps its files") {

    /**
     * Setup:
     *
     * A source Person with one Face, with its files, and a target Person with one Face.
     *
     * Assertions:
     *
     * The move reports the source deleted, and the Face is the target's with its files; the target counts two assets.
     */
    val source = person(Some("Alice"))
    val target = person()
    val f = face(source, testContext.persistAsset(), withFiles = true)
    face(target, testContext.persistAsset())

    val move = testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(target.persistedId))

    move.sourceDeleted shouldBe true
    move.source.name shouldBe Some("Alice")
    personExists(source.persistedId) shouldBe false
    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(target.persistedId)
    hasFiles(f.persistedId) shouldBe true
    getPerson(target.persistedId).numOfFaces shouldBe 2
  }

  test("On an image, a move may give a person a second Face, counting the asset once") {

    /**
     * Setup:
     *
     * An image with a Face of each of two People, each with another Face elsewhere.
     *
     * Assertions:
     *
     * Moving one Face to the other Person gives them two Faces on the image, and their count stays at two.
     */
    val source = person()
    val target = person()
    val image = testContext.persistAsset()
    val moving = face(source, image)
    val own = face(target, image)
    face(source, testContext.persistAsset())
    face(target, testContext.persistAsset())

    testApp.service.person.moveFace(moving.persistedId, FaceTarget.ExistingPerson(target.persistedId))

    testApp.service.person.getAssetFaces(image.persistedId).map(_.persistedId).toSet shouldBe Set(
      moving.persistedId,
      own.persistedId)
    testApp.service.person.getAssetFaces(image.persistedId).map(_.personId.get).toSet shouldBe Set(target.persistedId)
    getPerson(target.persistedId).numOfFaces shouldBe 2
    getPerson(source.persistedId).numOfFaces shouldBe 1
  }

  test("A Hidden person takes a moved Face and stays hidden") {

    /**
     * Setup:
     *
     * A hidden Person with one Face and a source Person with two Faces on two assets.
     *
     * Assertions:
     *
     * The hidden Person gets the Face, counts two and stays hidden; the asset's Detail read marks the Face's person hidden.
     */
    val source = person()
    val hidden = person()
    face(hidden, testContext.persistAsset())
    testApp.service.person.setVisibility(hidden, isHidden = true)
    val asset = testContext.persistAsset()
    val f = face(source, asset)
    face(source, testContext.persistAsset())

    testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(hidden.persistedId))

    val after = getPerson(hidden.persistedId)
    after.numOfFaces shouldBe 2
    after.isHidden shouldBe true
    val rows = testApp.service.person.getAssetFacesForDetail(asset.persistedId)
    rows.map { case (face, person) => (face.persistedId, person.persistedId, person.isHidden) } shouldBe
      List((f.persistedId, hidden.persistedId, true))
  }

  test("On a Video, a move onto a person already in it keeps the better Face, whichever it is, and the cover follows") {

    /**
     * Setup:
     *
     * Two Videos with Faces of a target and a source: on the first the target's (quality 18, their cover) is worse than the
     * source's (25), on the second better (22 against 15). The Faces have their files.
     *
     * Assertions:
     *
     * First Video: the moved Face is kept, the target's own is deleted with its files, and the cover moves to the moved Face.
     * Second Video: the moved Face is deleted with its files instead, and the source's count drops. Either way each Video holds
     * one Face of the target.
     */
    val source = person()
    val target = person()
    face(source, testContext.persistAsset())

    val betterVideo = testContext.persistAsset()
    testContext.setAssetType(betterVideo.persistedId, VIDEO)
    val targetWorse = face(target, betterVideo, quality = 18.0, frameTimeMs = Some(1000L), withFiles = true)
    val sourceBetter = face(source, betterVideo, quality = 25.0, frameTimeMs = Some(2000L), withFiles = true)
    getPerson(target.persistedId).coverFaceId shouldBe Some(targetWorse.persistedId)

    testApp.service.person.moveFace(sourceBetter.persistedId, FaceTarget.ExistingPerson(target.persistedId))

    faceExists(targetWorse.persistedId) shouldBe false
    hasFiles(targetWorse.persistedId) shouldBe false
    testApp.service.person.getFaceById(sourceBetter.persistedId).personId shouldBe Some(target.persistedId)
    hasFiles(sourceBetter.persistedId) shouldBe true
    getPerson(target.persistedId).coverFaceId shouldBe Some(sourceBetter.persistedId)
    getPerson(target.persistedId).numOfFaces shouldBe 1
    getPerson(source.persistedId).numOfFaces shouldBe 1

    val worseVideo = testContext.persistAsset()
    testContext.setAssetType(worseVideo.persistedId, VIDEO)
    val targetBetter = face(target, worseVideo, quality = 22.0, frameTimeMs = Some(1000L), withFiles = true)
    val sourceWorse = face(source, worseVideo, quality = 15.0, frameTimeMs = Some(3000L), withFiles = true)
    getPerson(source.persistedId).numOfFaces shouldBe 2

    testApp.service.person.moveFace(sourceWorse.persistedId, FaceTarget.ExistingPerson(target.persistedId))

    faceExists(sourceWorse.persistedId) shouldBe false
    hasFiles(sourceWorse.persistedId) shouldBe false
    testApp.service.person.getFaceById(targetBetter.persistedId).personId shouldBe Some(target.persistedId)
    hasFiles(targetBetter.persistedId) shouldBe true
    getPerson(target.persistedId).coverFaceId shouldBe Some(sourceBetter.persistedId)
    getPerson(target.persistedId).numOfFaces shouldBe 2
    getPerson(source.persistedId).numOfFaces shouldBe 1
    testApp.service.person.getAssetFaces(worseVideo.persistedId).map(_.persistedId) shouldBe List(targetBetter.persistedId)
  }

  test("Two concurrent moves of a person's two Faces on one asset count the asset once for the target") {

    /**
     * Setup:
     *
     * A source Person with two Faces on one image and a target Person with a Face elsewhere. Both Faces are moved at once.
     *
     * Assertions:
     *
     * The target counts two assets, not three, and the source, left with no Face, is deleted.
     *
     * Edge cases:
     *
     * Without the lock the second move reads the asset as not yet the target's and counts it again.
     */
    val source = person()
    val target = person()
    val image = testContext.persistAsset()
    val f1 = face(source, image)
    val f2 = face(source, image)
    face(target, testContext.persistAsset())

    contend(
      testApp.service.person.moveFace(f1.persistedId, FaceTarget.ExistingPerson(target.persistedId)): Unit,
      testApp.service.person.moveFace(f2.persistedId, FaceTarget.ExistingPerson(target.persistedId)): Unit
    )

    getPerson(target.persistedId).numOfFaces shouldBe 2
    personExists(source.persistedId) shouldBe false
    testApp.service.person.getAssetFaces(image.persistedId).map(_.personId.get) shouldBe List(
      target.persistedId,
      target.persistedId)
  }

  test("A move of a Face removed by a concurrent request is not found") {

    /**
     * Setup:
     *
     * A Person with two Faces and a target Person. A removal of one Face holds its transaction until a move of it waits.
     *
     * Assertions:
     *
     * The move fails as not found once the removal commits, and the target's count is unchanged.
     */
    val source = person()
    val target = person()
    val f = face(source, testContext.persistAsset())
    face(source, testContext.persistAsset())
    face(target, testContext.persistAsset())

    intercept[NotFoundException] {
      contend(
        testApp.service.person.removeFace(f.persistedId): Unit,
        testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(target.persistedId)): Unit
      )
    }

    getPerson(target.persistedId).numOfFaces shouldBe 1
    getPerson(source.persistedId).numOfFaces shouldBe 1
  }

  test("Recycling an asset while a removal of a Face on it holds its person counts the asset once") {

    /**
     * Setup:
     *
     * A Person with a Face on each of two images. A removal of the first image's Face holds the Person's row lock until a recycle
     * of that image waits for it.
     *
     * Assertions:
     *
     * The Person counts one asset: the recycle finds no Face of theirs left on the image and takes nothing more.
     *
     * Edge cases:
     *
     * Without the recycle locking the asset's people before it counts, it would see the removed Face in its snapshot and take the
     * asset from the count twice.
     */
    val p = person()
    val first = testContext.persistAsset()
    val f = face(p, first)
    face(p, testContext.persistAsset())
    getPerson(p.persistedId).numOfFaces shouldBe 2

    contend(
      testApp.service.person.removeFace(f.persistedId): Unit,
      testApp.service.library.recycleAssets(Set(first.persistedId))
    )

    getPerson(p.persistedId).numOfFaces shouldBe 1
    faceExists(f.persistedId) shouldBe false
  }

  test("A Face is not moved onto its own person, a merged-away person or a bad match") {

    /**
     * Setup:
     *
     * A Person with a Face, a Person merged into them, and a bad-match Person.
     *
     * Assertions:
     *
     * Moving the Face to its own Person is a validation error, to either other Person not found. The Face stays where it was.
     */
    val owner = person()
    val mergedAway = person()
    val badMatch = person()
    val f = face(owner, testContext.persistAsset())
    face(mergedAway, testContext.persistAsset())
    face(badMatch, testContext.persistAsset())
    testApp.service.person.merge(dest = owner, source = mergedAway)
    testApp.service.person.markAsBadMatch(badMatch)

    intercept[ValidationException](testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(owner.persistedId)))
    intercept[NotFoundException](
      testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(mergedAway.persistedId)))
    intercept[NotFoundException](testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(badMatch.persistedId)))

    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(owner.persistedId)
  }

  test("Moving a Face to a name creates a named person whose cover is the Face") {

    /**
     * Setup:
     *
     * A Person with two Faces, and nobody named "Alice".
     *
     * Assertions:
     *
     * Moving a Face to "Alice" creates a live, named Alice, found by name key, with the Face as cover and one asset; the source
     * counts one.
     */
    val source = person()
    val f = face(source, testContext.persistAsset())
    face(source, testContext.persistAsset())

    val move = testApp.service.person.moveFace(f.persistedId, FaceTarget.Named("Alice"))

    val alice = getPerson(move.target.persistedId)
    alice.name shouldBe Some("Alice")
    alice.isNamed shouldBe true
    alice.coverFaceId shouldBe Some(f.persistedId)
    alice.numOfFaces shouldBe 1
    testApp.service.person.getLiveByNameKey("alice").map(_.persistedId) shouldBe Some(alice.persistedId)
    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(alice.persistedId)
    getPerson(source.persistedId).numOfFaces shouldBe 1
  }

  test("A name in any case resolves to the live person holding it, and a reserved name is refused") {

    /**
     * Setup:
     *
     * A Person named "Alice" with a Face, and a source Person with two Faces.
     *
     * Assertions:
     *
     * Moving a Face to "ALICE" gives it to Alice, who keeps her name and cover and counts two; no second Alice is made. Moving to
     * "Unknown 7" is a validation error.
     */
    val alice = person(Some("Alice"))
    val aliceFace = face(alice, testContext.persistAsset())
    val source = person()
    val f = face(source, testContext.persistAsset())
    face(source, testContext.persistAsset())

    val move = testApp.service.person.moveFace(f.persistedId, FaceTarget.Named("ALICE"))

    move.target.persistedId shouldBe alice.persistedId
    val after = getPerson(alice.persistedId)
    after.name shouldBe Some("Alice")
    after.coverFaceId shouldBe Some(aliceFace.persistedId)
    after.numOfFaces shouldBe 2
    testApp.service.person.getAll.count(_.name.exists(_.equalsIgnoreCase("alice"))) shouldBe 1

    intercept[ValidationException](testApp.service.person.moveFace(f.persistedId, FaceTarget.Named("Unknown 7")))
  }

  test("Two concurrent moves to one new name make one person") {

    /**
     * Setup:
     *
     * Two People with a Face each and nobody named "Alice". Both Faces are moved to the name at once.
     *
     * Assertions:
     *
     * One live Person named Alice holds both Faces and counts two assets; neither move fails.
     */
    val first = person()
    val second = person()
    val f1 = face(first, testContext.persistAsset())
    val f2 = face(second, testContext.persistAsset())

    contend(
      testApp.service.person.moveFace(f1.persistedId, FaceTarget.Named("Alice")): Unit,
      testApp.service.person.moveFace(f2.persistedId, FaceTarget.Named("alice")): Unit
    )

    val alice = testApp.service.person.getLiveByNameKey("alice").value
    testApp.service.person.getAll.count(_.name.exists(_.equalsIgnoreCase("alice"))) shouldBe 1
    alice.numOfFaces shouldBe 2
    testApp.service.person.getFaceById(f1.persistedId).personId shouldBe Some(alice.persistedId)
    testApp.service.person.getFaceById(f2.persistedId).personId shouldBe Some(alice.persistedId)
  }

  /** Whether the Person is live in the context repository: neither merged away nor a bad match */
  private def isLive(personId: String): Boolean = Try(testApp.service.person.getLiveInRepository(personId)).isSuccess

  /** The Person IDs of the assets' Faces, as stored, hidden people's included */
  private def faceOwners(assets: List[Asset]): Set[String] =
    assets.flatMap(asset => testApp.service.person.getAssetFacesWithPeople(asset.persistedId)).map(_._2.persistedId).toSet

  test("Naming a Face's Person names them as a whole, every asset of theirs following") {

    /**
     * Setup:
     *
     * A Person with no name and a Face on each of three assets.
     *
     * Assertions:
     *
     * Naming them "Andreea" through one Face names the same Person, sort name included, and all three Faces stay theirs. The
     * answer holds the Face and the Person before and after.
     */
    val p = person()
    val assets = List.fill(3)(testContext.persistAsset())
    val faces = assets.map(face(p, _))
    val unknownName = getPerson(p.persistedId).name.get

    val naming = testApp.service.person.nameFacePerson(faces(1).persistedId, p.persistedId, "Andreea")

    naming.face.persistedId shouldBe faces(1).persistedId
    naming.before.name shouldBe Some(unknownName)
    naming.after.persistedId shouldBe p.persistedId
    naming.after.name shouldBe Some("Andreea")
    val named = getPerson(p.persistedId)
    named.name shouldBe Some("Andreea")
    named.isNamed shouldBe true
    named.numOfFaces shouldBe 3
    testApp.txManager.asReadOnly(query("SELECT name_for_sort FROM person WHERE id = ?", p.persistedId).head)(
      "name_for_sort") shouldBe "andreea"
    faceOwners(assets) shouldBe Set(p.persistedId)
  }

  test("Naming a Face's Person is refused when they have a name by now, or the name is held or reserved") {

    /**
     * Setup:
     *
     * Andrei, and two People with no name, each with a Face; the first is named "Bob" after the panel was shown.
     *
     * Assertions:
     *
     * Naming the first is an illegal operation, as the panel is stale, and they keep "Bob". Naming the second "ANDREI" is a
     * duplicate and "Unknown 7" a validation error; the second keeps no name.
     */
    person(Some("Andrei"))
    val namedSince = person()
    val namedSinceFace = face(namedSince, testContext.persistAsset())
    testApp.service.person.updateName(getPerson(namedSince.persistedId), "Bob")
    val nameless = person()
    val namelessFace = face(nameless, testContext.persistAsset())

    intercept[IllegalOperationException](
      testApp.service.person.nameFacePerson(namedSinceFace.persistedId, namedSince.persistedId, "Carol")).getMessage shouldBe
      Const.Msg.Err.FACE_PERSON_NAMED_SINCE
    getPerson(namedSince.persistedId).name shouldBe Some("Bob")

    intercept[DuplicateException](testApp.service.person.nameFacePerson(namelessFace.persistedId, nameless.persistedId, "ANDREI"))
    intercept[ValidationException](
      testApp.service.person.nameFacePerson(namelessFace.persistedId, nameless.persistedId, "Unknown 7"))
    getPerson(nameless.persistedId).isNamed shouldBe false
  }

  test("A Face given to someone else, or on an asset in the trash, has its Person neither named nor merged") {

    /**
     * Setup:
     *
     * Andrei with a Face, and a Person with no name with two Faces: one moved to a third Person after the panel was shown, the
     * other's asset recycled.
     *
     * Assertions:
     *
     * Naming or merging the Person through the moved Face is not found, through the Face in the trash an illegal operation.
     * Nothing changes.
     */
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    val p = person()
    val moved = face(p, testContext.persistAsset())
    val trashed = testContext.persistAsset()
    val inTrash = face(p, trashed)
    val third = person()
    face(third, testContext.persistAsset())
    testApp.service.person.moveFace(moved.persistedId, FaceTarget.ExistingPerson(third.persistedId))
    testApp.service.library.recycleAssets(Set(trashed.persistedId))

    intercept[NotFoundException](testApp.service.person.nameFacePerson(moved.persistedId, p.persistedId, "Carol"))
    intercept[NotFoundException](testApp.service.person.mergeFacePerson(moved.persistedId, p.persistedId, andrei.persistedId))
    intercept[IllegalOperationException](testApp.service.person.nameFacePerson(inTrash.persistedId, p.persistedId, "Carol"))
    intercept[IllegalOperationException](
      testApp.service.person.mergeFacePerson(inTrash.persistedId, p.persistedId, andrei.persistedId))

    getPerson(p.persistedId).isNamed shouldBe false
    isLive(p.persistedId) shouldBe true
    getPerson(andrei.persistedId).numOfFaces shouldBe 1
  }

  test("Merging a Face's Person gives every Face of theirs to a named Person, whose name and Hidden state stay") {

    /**
     * Setup:
     *
     * A Person with no name and a Face on each of three assets, and Andrei, named and hidden, with a Face on each of two others.
     *
     * Assertions:
     *
     * Merging the Person into Andrei through one Face merges them away and gives Andrei every Face, counting five; he keeps his
     * name and stays hidden. The answer holds the Face, the Person before and Andrei after.
     */
    val source = person()
    val sourceAssets = List.fill(3)(testContext.persistAsset())
    val sourceFaces = sourceAssets.map(face(source, _))
    val unknownName = getPerson(source.persistedId).name.get
    val andrei = person(Some("Andrei"))
    val andreiAssets = List.fill(2)(testContext.persistAsset())
    andreiAssets.foreach(face(andrei, _))
    testApp.service.person.setVisibility(andrei, isHidden = true)

    val merge = testApp.service.person.mergeFacePerson(sourceFaces.head.persistedId, source.persistedId, andrei.persistedId)

    merge.face.persistedId shouldBe sourceFaces.head.persistedId
    merge.before.persistedId shouldBe source.persistedId
    merge.before.name shouldBe Some(unknownName)
    merge.after.persistedId shouldBe andrei.persistedId
    merge.after.name shouldBe Some("Andrei")
    isLive(source.persistedId) shouldBe false
    faceOwners(sourceAssets ++ andreiAssets) shouldBe Set(andrei.persistedId)
    val after = getPerson(andrei.persistedId)
    after.numOfFaces shouldBe 5
    after.name shouldBe Some("Andrei")
    after.isNamed shouldBe true
    after.isHidden shouldBe true
  }

  test("A Face's Person is not merged when they have a name by now, into a Person with no name, or into themselves") {

    /**
     * Setup:
     *
     * Andrei, two People with no name, and a third named "Bob" after the panel was shown, each with a Face.
     *
     * Assertions:
     *
     * Merging Bob into Andrei is an illegal operation, as the panel is stale; merging one nameless Person into the other, or a
     * Person into themselves, is a validation error. Nothing changes.
     */
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    val nameless = person()
    val namelessFace = face(nameless, testContext.persistAsset())
    val namedSince = person()
    val namedSinceFace = face(namedSince, testContext.persistAsset())
    val otherNameless = person()
    face(otherNameless, testContext.persistAsset())
    testApp.service.person.updateName(getPerson(namedSince.persistedId), "Bob")

    intercept[IllegalOperationException](
      testApp.service.person
        .mergeFacePerson(namedSinceFace.persistedId, namedSince.persistedId, andrei.persistedId)).getMessage shouldBe
      Const.Msg.Err.FACE_PERSON_NAMED_SINCE
    intercept[ValidationException](
      testApp.service.person.mergeFacePerson(
        namelessFace.persistedId,
        nameless.persistedId,
        otherNameless.persistedId)).message shouldBe Const.Msg.Err.MERGE_INTO_NAMELESS
    intercept[ValidationException](
      testApp.service.person.mergeFacePerson(namelessFace.persistedId, nameless.persistedId, nameless.persistedId))

    List(andrei, nameless, namedSince, otherNameless).foreach(p => isLive(p.persistedId) shouldBe true)
    testApp.service.person.getFaceById(namelessFace.persistedId).personId shouldBe Some(nameless.persistedId)
    testApp.service.person.getFaceById(namedSinceFace.persistedId).personId shouldBe Some(namedSince.persistedId)
    getPerson(andrei.persistedId).numOfFaces shouldBe 1
  }

  test("A Face's Person is not merged into a Person merged away, discarded or of another Library") {

    /**
     * Setup:
     *
     * A Person with no name; Tessa, merged into Andrei; Carl, a bad match; and Eve, in another Library.
     *
     * Assertions:
     *
     * Merging the Person into Tessa, Carl, Eve or an unknown ID is not found, and the Person stays live with their Face.
     */
    val repo = testContext.repository
    val p = person()
    val f = face(p, testContext.persistAsset())
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    val tessa = person(Some("Tessa"))
    face(tessa, testContext.persistAsset())
    testApp.service.person.merge(dest = andrei, source = tessa)
    val carl = person(Some("Carl"))
    face(carl, testContext.persistAsset())
    testApp.service.person.markAsBadMatch(carl)
    val otherRepo = testContext.persistRepository()
    testApp.service.repository.switchContextToRepository(otherRepo)
    val eve = person(Some("Eve"))
    testApp.service.repository.switchContextToRepository(repo)

    List(tessa.persistedId, carl.persistedId, eve.persistedId, java.util.UUID.randomUUID.toString).foreach {
      destId => intercept[NotFoundException](testApp.service.person.mergeFacePerson(f.persistedId, p.persistedId, destId))
    }

    isLive(p.persistedId) shouldBe true
    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(p.persistedId)
  }

  test("A Face's Person who is a bad match by now is neither named nor merged") {

    /**
     * Setup:
     *
     * A Person with no name, marked a bad match after the panel was shown, and Andrei with a Face.
     *
     * Assertions:
     *
     * Naming the Person or merging them into Andrei is not found, and nothing changes.
     */
    val p = person()
    val f = face(p, testContext.persistAsset())
    face(p, testContext.persistAsset())
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    testApp.service.person.markAsBadMatch(getPerson(p.persistedId))

    intercept[NotFoundException](testApp.service.person.nameFacePerson(f.persistedId, p.persistedId, "Carol"))
    intercept[NotFoundException](testApp.service.person.mergeFacePerson(f.persistedId, p.persistedId, andrei.persistedId))

    getPerson(p.persistedId).isNamed shouldBe false
    testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(p.persistedId)
    getPerson(andrei.persistedId).numOfFaces shouldBe 1
  }

  test("A Hidden Person with no name is named and merged as any other") {

    /**
     * Setup:
     *
     * Two hidden People with no name, each with Faces on two assets, and Andrei, named and not hidden, with a Face.
     *
     * Assertions:
     *
     * Naming the first "Hedda" names them, still hidden. Merging the second into Andrei gives him their Faces, counting three,
     * and he stays visible.
     */
    val first = person()
    val firstFace = face(first, testContext.persistAsset())
    face(first, testContext.persistAsset())
    testApp.service.person.setVisibility(getPerson(first.persistedId), isHidden = true)
    val second = person()
    val secondAssets = List.fill(2)(testContext.persistAsset())
    val secondFaces = secondAssets.map(face(second, _))
    testApp.service.person.setVisibility(getPerson(second.persistedId), isHidden = true)
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())

    testApp.service.person.nameFacePerson(firstFace.persistedId, first.persistedId, "Hedda")
    testApp.service.person.mergeFacePerson(secondFaces.head.persistedId, second.persistedId, andrei.persistedId)

    val named = getPerson(first.persistedId)
    named.name shouldBe Some("Hedda")
    named.isNamed shouldBe true
    named.isHidden shouldBe true
    named.numOfFaces shouldBe 2
    isLive(second.persistedId) shouldBe false
    faceOwners(secondAssets) shouldBe Set(andrei.persistedId)
    getPerson(andrei.persistedId).numOfFaces shouldBe 3
    getPerson(andrei.persistedId).isHidden shouldBe false
  }

  test("Merging a Face's Person into a Person on one of the same images counts that image once") {

    /**
     * Setup:
     *
     * A Person with no name and Andrei, each with a Face on a shared image and one on an image of their own.
     *
     * Assertions:
     *
     * After the merge Andrei counts three assets, and the shared image keeps both Faces, now both his: an image may hold two
     * Faces of one Person.
     */
    val source = person()
    val shared = testContext.persistAsset()
    val f = face(source, shared, x1 = 1)
    face(source, testContext.persistAsset())
    val andrei = person(Some("Andrei"))
    face(andrei, shared, x1 = 2)
    face(andrei, testContext.persistAsset())

    testApp.service.person.mergeFacePerson(f.persistedId, source.persistedId, andrei.persistedId)

    getPerson(andrei.persistedId).numOfFaces shouldBe 3
    testApp.service.person.getAssetFaces(shared.persistedId).map(_.personId.get) shouldBe List(
      andrei.persistedId,
      andrei.persistedId)
  }

  test("A merge keeps one Face per Person per Video, the better one or the destination's on a tie, and the cover follows") {

    /**
     * Setup:
     *
     * Andrei and a Person with no name, each with a Face, with its files, on three Videos and one image. On the first Video
     * Andrei's Face is his cover and the worse (quality 18 against 25), on the second the better (22 against 15), on the third
     * they tie (20).
     *
     * Assertions:
     *
     * After the merge each Video holds one Face of Andrei's: the source's on the first, his own on the others. The losing Faces
     * are deleted with their files, his cover moves to the source's Face on the first Video, the image keeps both Faces, and he
     * counts four assets.
     */
    val andrei = person(Some("Andrei"))
    val source = person()
    val videos = List.fill(3)(testContext.persistAsset())
    videos.foreach(video => testContext.setAssetType(video.persistedId, VIDEO))
    val image = testContext.persistAsset()

    def videoFace(p: Person, video: Asset, quality: Double, frameTimeMs: Long): Face =
      face(p, video, quality = quality, frameTimeMs = Some(frameTimeMs), withFiles = true)

    val andreiWorse = videoFace(andrei, videos.head, 18.0, 1000L)
    val sourceBetter = videoFace(source, videos.head, 25.0, 2000L)
    val andreiBetter = videoFace(andrei, videos(1), 22.0, 1000L)
    val sourceWorse = videoFace(source, videos(1), 15.0, 2000L)
    val andreiTied = videoFace(andrei, videos(2), 20.0, 1000L)
    val sourceTied = videoFace(source, videos(2), 20.0, 2000L)
    val andreiOnImage = face(andrei, image, x1 = 1, withFiles = true)
    val sourceOnImage = face(source, image, x1 = 2, withFiles = true)
    getPerson(andrei.persistedId).coverFaceId shouldBe Some(andreiWorse.persistedId)

    testApp.service.person.merge(dest = getPerson(andrei.persistedId), source = getPerson(source.persistedId))

    List(andreiWorse, sourceWorse, sourceTied).foreach {
      lost =>
        faceExists(lost.persistedId) shouldBe false
        hasFiles(lost.persistedId) shouldBe false
    }
    List(sourceBetter, andreiBetter, andreiTied, andreiOnImage, sourceOnImage).foreach {
      kept =>
        testApp.service.person.getFaceById(kept.persistedId).personId shouldBe Some(andrei.persistedId)
        hasFiles(kept.persistedId) shouldBe true
    }
    videos.map(video => testApp.service.person.getAssetFaces(video.persistedId).map(_.persistedId)) shouldBe List(
      List(sourceBetter.persistedId),
      List(andreiBetter.persistedId),
      List(andreiTied.persistedId))
    val after = getPerson(andrei.persistedId)
    after.coverFaceId shouldBe Some(sourceBetter.persistedId)
    after.numOfFaces shouldBe 4
  }

  test("A name or a merge of a Face's Person waits for a concurrent move of that Face, and finds it gone") {

    /**
     * Setup:
     *
     * A Person with no name with three Faces, and Tom and Andrei with a Face each. A move of one Face to Tom holds its
     * transaction until a name of the Person through that Face waits; then the same with another Face and a merge into Andrei.
     *
     * Assertions:
     *
     * The name and the merge are not found, as the Face is Tom's by then: the Person keeps no name, stays live with their last
     * Face, and Andrei counts one asset.
     *
     * Edge cases:
     *
     * Without the people's lock before the Face is re-read, the name or merge would act through a Face no longer theirs.
     */
    val p = person()
    val named = face(p, testContext.persistAsset())
    val merged = face(p, testContext.persistAsset())
    val last = face(p, testContext.persistAsset())
    val tom = person(Some("Tom"))
    face(tom, testContext.persistAsset())
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())

    intercept[NotFoundException] {
      contend(
        testApp.service.person.moveFace(named.persistedId, FaceTarget.ExistingPerson(tom.persistedId)): Unit,
        testApp.service.person.nameFacePerson(named.persistedId, p.persistedId, "Carol"): Unit
      )
    }
    intercept[NotFoundException] {
      contend(
        testApp.service.person.moveFace(merged.persistedId, FaceTarget.ExistingPerson(tom.persistedId)): Unit,
        testApp.service.person.mergeFacePerson(merged.persistedId, p.persistedId, andrei.persistedId): Unit
      )
    }

    getPerson(p.persistedId).isNamed shouldBe false
    isLive(p.persistedId) shouldBe true
    testApp.service.person.getFaceById(last.persistedId).personId shouldBe Some(p.persistedId)
    getPerson(tom.persistedId).numOfFaces shouldBe 3
    getPerson(andrei.persistedId).numOfFaces shouldBe 1
  }

  test("A move of a Face during a merge of its Person moves it only from the Person who has it once the merge is done") {

    /**
     * Setup:
     *
     * A Person with no name with two Faces, and Andrei and Tom with a Face each. A merge of the Person into Andrei holds its
     * transaction until a move of one of the Person's Faces to Tom waits.
     *
     * Assertions:
     *
     * On PostgreSQL the move read the merged Person before it waited at the row locks, so it is not found and Andrei keeps the
     * Face, counting three. On SQLite the move waits before it reads anything, so it moves the Face from Andrei to Tom, two each.
     * Either way the counts follow the Face.
     *
     * Edge cases:
     *
     * Without the Face re-read under the lock, the move on PostgreSQL would take the Face from Andrei as the merged Person's, and
     * Andrei would count an asset he has no Face on.
     */
    val p = person()
    val f = face(p, testContext.persistAsset())
    face(p, testContext.persistAsset())
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    val tom = person(Some("Tom"))
    face(tom, testContext.persistAsset())

    val moved = Try(
      contend(
        testApp.service.person.mergeFacePerson(f.persistedId, p.persistedId, andrei.persistedId): Unit,
        testApp.service.person.moveFace(f.persistedId, FaceTarget.ExistingPerson(tom.persistedId)): Unit
      ))

    isLive(p.persistedId) shouldBe false
    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
      intercept[NotFoundException](moved.get)
      testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(andrei.persistedId)
      getPerson(andrei.persistedId).numOfFaces shouldBe 3
      getPerson(tom.persistedId).numOfFaces shouldBe 1
    } else {
      moved.get
      testApp.service.person.getFaceById(f.persistedId).personId shouldBe Some(tom.persistedId)
      getPerson(andrei.persistedId).numOfFaces shouldBe 2
      getPerson(tom.persistedId).numOfFaces shouldBe 2
    }
  }

  test("Two concurrent merges of a Face's Person into different people merge them into the first alone") {

    /**
     * Setup:
     *
     * A Person with no name with two Faces, and Andrei and Bob with a Face each. A merge of the Person into Andrei holds its
     * transaction until a merge into Bob through the same Face waits.
     *
     * Assertions:
     *
     * The second merge is not found, as the Person is merged away by then: their Faces are Andrei's, who counts three, and Bob
     * counts one.
     *
     * Edge cases:
     *
     * Without the people's lock before the Person is checked, the second merge would silently merge a Person already merged away.
     */
    val p = person()
    val assets = List.fill(2)(testContext.persistAsset())
    val faces = assets.map(face(p, _))
    val andrei = person(Some("Andrei"))
    face(andrei, testContext.persistAsset())
    val bob = person(Some("Bob"))
    face(bob, testContext.persistAsset())

    intercept[NotFoundException] {
      contend(
        testApp.service.person.mergeFacePerson(faces.head.persistedId, p.persistedId, andrei.persistedId): Unit,
        testApp.service.person.mergeFacePerson(faces.head.persistedId, p.persistedId, bob.persistedId): Unit
      )
    }

    isLive(p.persistedId) shouldBe false
    faceOwners(assets) shouldBe Set(andrei.persistedId)
    getPerson(andrei.persistedId).numOfFaces shouldBe 3
    getPerson(bob.persistedId).numOfFaces shouldBe 1
  }

  test("A move to a new name waits for a concurrent name of a Face's Person to it, and joins them") {

    /**
     * Setup:
     *
     * Two People with no name, each with a Face. Naming the first "Andreea" holds its transaction until a move of the second's
     * Face to "andreea" waits.
     *
     * Assertions:
     *
     * Neither fails: the move finds the first Person holding the name, who then holds both Faces and counts two assets.
     *
     * Edge cases:
     *
     * Without the name's lock in the naming, the move would create a second Person with the name and fail on `person_01`.
     */
    val first = person()
    val firstFace = face(first, testContext.persistAsset())
    val second = person()
    val secondFace = face(second, testContext.persistAsset())

    contend(
      testApp.service.person.nameFacePerson(firstFace.persistedId, first.persistedId, "Andreea"): Unit,
      testApp.service.person.moveFace(secondFace.persistedId, FaceTarget.Named("andreea")): Unit
    )

    testApp.service.person.getLiveByNameKey("andreea").map(_.persistedId) shouldBe Some(first.persistedId)
    testApp.service.person.getAll.count(_.name.exists(_.equalsIgnoreCase("andreea"))) shouldBe 1
    testApp.service.person.getFaceById(secondFace.persistedId).personId shouldBe Some(first.persistedId)
    getPerson(first.persistedId).numOfFaces shouldBe 2
  }

  test("A move to a new name waits for a concurrent rename of a Person to it, and joins them") {

    /**
     * Setup:
     *
     * A Person named "Bogdan" and a Person with no name, each with a Face. Renaming Bogdan "Andreea", as the People tab does,
     * holds its transaction until a move of the second's Face to "andreea" waits.
     *
     * Assertions:
     *
     * Neither fails: the move finds the renamed Person holding the name, who then holds both Faces and counts two assets.
     *
     * Edge cases:
     *
     * Without the name's lock in the rename, the move would create a second Person with the name and fail on `person_01` with a
     * raw `SQLException`.
     */
    val first = person(Some("Bogdan"))
    face(first, testContext.persistAsset())
    val second = person()
    val secondFace = face(second, testContext.persistAsset())

    contend(
      testApp.service.person.updateName(getPerson(first.persistedId), "Andreea"): Unit,
      testApp.service.person.moveFace(secondFace.persistedId, FaceTarget.Named("andreea")): Unit
    )

    testApp.service.person.getLiveByNameKey("andreea").map(_.persistedId) shouldBe Some(first.persistedId)
    testApp.service.person.getFaceById(secondFace.persistedId).personId shouldBe Some(first.persistedId)
    getPerson(first.persistedId).numOfFaces shouldBe 2
  }

  test("The Detail panel's Faces of an asset leave out bad matches and come in reading order, or by Frame time") {

    /**
     * Setup:
     *
     * An image with Faces at x 300, 100 and 200, the second's Person hidden and the third's a bad match; a Video with Faces at
     * 5000 and 2000 ms.
     *
     * Assertions:
     *
     * The image's rows are by x, the hidden person marked and the bad match left out. The Video's rows are by Frame time.
     */
    val a = person()
    val hidden = person()
    val badMatch = person()
    val image = testContext.persistAsset()
    val right = face(a, image, x1 = 300)
    val left = face(hidden, image, x1 = 100)
    face(badMatch, image, x1 = 200)
    testApp.service.person.setVisibility(hidden, isHidden = true)
    testApp.service.person.markAsBadMatch(badMatch)

    val rows = testApp.service.person.getAssetFacesForDetail(image.persistedId)
    rows.map { case (face, person) => (face.persistedId, person.persistedId, person.isHidden) } shouldBe
      List((left.persistedId, hidden.persistedId, true), (right.persistedId, a.persistedId, false))

    val video = testContext.persistAsset()
    testContext.setAssetType(video.persistedId, VIDEO)
    val later = face(a, video, x1 = 10, frameTimeMs = Some(5000L))
    val earlier = face(hidden, video, x1 = 500, frameTimeMs = Some(2000L))

    testApp.service.person.getAssetFacesForDetail(video.persistedId).map(_._1.persistedId) shouldBe
      List(earlier.persistedId, later.persistedId)
  }

  test("The assignable people are the live people who are not a bad match, named first, then by sort name") {

    /**
     * Setup:
     *
     * People named "Zoe", "adam" and a hidden "Mia"; four unnamed People, one merged away, one a bad match and one with every
     * Face on a recycled asset.
     *
     * Assertions:
     *
     * The list is adam, Mia, Zoe, then the unnamed people by sort name, the one with no live asset included; the merged-away and
     * bad-match people are left out.
     */
    val zoe = person(Some("Zoe"))
    val adam = person(Some("adam"))
    val mia = person(Some("Mia"))
    val unknown1 = person()
    val mergedAway = person()
    val unknown2 = person()
    val badMatch = person()
    val inTrash = person()
    List(zoe, adam, mia, unknown1, mergedAway, unknown2, badMatch).foreach(p => face(p, testContext.persistAsset()))
    val trashed = testContext.persistAsset()
    face(inTrash, trashed)
    testApp.service.library.recycleAssets(Set(trashed.persistedId))
    testApp.service.person.setVisibility(mia, isHidden = true)
    testApp.service.person.merge(dest = unknown1, source = mergedAway)
    testApp.service.person.markAsBadMatch(badMatch)

    val assignable = testApp.service.person.getAssignable

    assignable.map(_.persistedId) shouldBe
      List(adam, mia, zoe, unknown1, unknown2, inTrash).map(_.persistedId)
    assignable.find(_.persistedId == mia.persistedId).value.isHidden shouldBe true
    (assignable.map(_.persistedId) should not).contain(badMatch.persistedId)
  }

  test("The suggested people of a Face are the first three distinct people among its nearest Faces, closest first") {

    /**
     * Setup:
     *
     * A Person's Face, and Faces at known distances from it: A's at about 0.02 and 0.11, B's at 0.03, C's at 0.05, D's at 0.29,
     * another of the Person's own at 0.02, and a far Person's at about 1 from every other, past the threshold.
     *
     * Assertions:
     *
     * The suggested people are A, B and C, closest first: A once, D left out by the count of three and the Person's own Faces
     * left out however close.
     *
     * Edge cases:
     *
     * A Face whose only neighbours are past the threshold suggests nobody, and a Face that is gone is not found.
     */
    val vector = TestContext.unitVector(21)
    val other = TestContext.unitVector(22)
    val p = person()
    val f = testContext.addTestFace(p, testContext.persistAsset(), vector)
    val asset = testContext.persistAsset()
    testContext.addTestFace(p, asset, TestContext.between(vector, other, 5))
    val a = person(Some("Alice"))
    testContext.addTestFace(a, asset, TestContext.between(vector, other, 5))
    testContext.addTestFace(a, asset, TestContext.between(vector, other, 2))
    val b = person()
    testContext.addTestFace(b, asset, TestContext.between(vector, other, 4))
    val c = person()
    testContext.addTestFace(c, asset, TestContext.between(vector, other, 3))
    val d = person()
    testContext.addTestFace(d, asset, TestContext.between(vector, other, 1))
    val far = person()
    val farFace = testContext.addTestFace(far, asset, TestContext.unitVector(23))

    val suggested = testApp.service.person.suggestedPeople(f.persistedId)

    suggested.map(_.persistedId) shouldBe List(a, b, c).map(_.persistedId)
    suggested.head.name shouldBe Some("Alice")

    testApp.service.person.suggestedPeople(farFace.persistedId) shouldBe empty
    testApp.service.person.removeFace(f.persistedId)
    intercept[NotFoundException](testApp.service.person.suggestedPeople(f.persistedId))
  }
}
