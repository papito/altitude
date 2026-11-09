package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, contain, empty, not, should, shouldBe }

import scala.util.Random
import scala.util.Try

import altitude.core.Altitude
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.service.PersonService.FaceTarget

/** The Face corrections of Asset detail: `PersonService.removeFace` and `moveFace`, with the reads the Detail panel makes */
@DoNotDiscover class FaceCorrectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val VIDEO = AssetType("video", "mp4", "video/mp4")

  private def features: Array[Float] = Array.fill(512)(Random.nextFloat())

  private def person(name: Option[String] = None): Person = testApp.service.person.addPerson(Person(name = name))

  private def getPerson(id: String): Person = testApp.service.person.getById(id)

  /** A Face of the person as stored, so the cover stays their first Face as an import leaves it, whatever model the test holds */
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
     * Removing one Face of the shared asset deletes its row and leaves the count at two, since the other Face still finds the
     * asset; removing that other Face lowers the count to one. The Person stays, with the Face on the second asset.
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
     * A Person with three Faces on three assets, each with its files: the first (score 0.9) is the cover, the others score 0.5
     * and 0.7.
     *
     * Assertions:
     *
     * Removing the cover makes the 0.7 Face the cover, deletes the removed Face's four files, and leaves the other two Faces'
     * files in place.
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

  test("Removing a person's last Face deletes the person, named or not, and their cover's files") {

    /**
     * Setup:
     *
     * A Person named "Alice" and an unnamed Person, each with one Face with its files.
     *
     * Assertions:
     *
     * Removing either Face deletes the Face, its files, cover though it is, and the Person, who is reported deleted with the name
     * they had.
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
     * Removing the live Face lowers the count to zero and keeps the Person: the Face on the trashed asset is a row, and becomes
     * the cover.
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
     * Removing the Face and moving it to the other Person are both refused as illegal operations; the Face, its files and the
     * counts are as they were.
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
     * Removing the removed Face again, or moving it, is a `NotFoundException`.
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
     * A source Person with two Faces on one asset (the first, best-scored, is the cover) and one on a second asset, counting two;
     * a target Person with one Face on an asset of their own, counting one.
     *
     * Assertions:
     *
     * Moving the cover Face to the target makes it the target's, raises the target's count to two and leaves the source's at two,
     * since the source's other Face still finds the asset; the source's cover becomes their best remaining Face and the target
     * keeps their cover. Moving the other Face of that asset lowers the source's count to one and leaves the target's at two,
     * since the target already had the asset.
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
     * The move reports the source deleted; the source is gone, the Face is the target's with its files, and the target counts two
     * assets.
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
     * Moving one Face to the other Person leaves them with two Faces on the image, both read back, and their count at two: the
     * image was already theirs.
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
     * The hidden Person gets the Face, counts two and is still hidden; the Detail panel's read of the asset marks the Face's
     * person hidden.
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
     * Two Videos. In the first, the target's Face (quality 18, their cover) and a better Face of the source (quality 25). In the
     * second, the target's Face (quality 22) and a worse Face of the source (quality 15). The source has another Face elsewhere
     * and both source Faces have their files.
     *
     * Assertions:
     *
     * First Video: the moved Face becomes the target's, the target's own Face is deleted with its files, the target's cover moves
     * to the moved Face, and the target's count stays. Second Video: the moved Face is deleted with its files instead, the target
     * keeps their Face and cover, and the source's count is lowered. Either way one Face per Person per Video.
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
     * A source Person with two Faces on one image and a target Person with a Face elsewhere. The two moves run at once: on
     * PostgreSQL the second waits at the people's row locks until the first commits, on SQLite for the write connection.
     *
     * Assertions:
     *
     * The target counts two assets, not three: the second move sees the first's Face already on the asset. The source, left with
     * no Face, is deleted.
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
     * A Person with two Faces and a target Person. A removal of one Face holds its transaction until a move of the same Face
     * waits for it.
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

  test("A Face is not moved onto its own person, a merged-away person or a bad match") {

    /**
     * Setup:
     *
     * A Person with a Face, a Person merged into them, and a bad-match Person.
     *
     * Assertions:
     *
     * Moving the Face to its own Person is a validation error; to the merged-away or the bad-match Person, not found. The Face
     * stays where it was.
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
     * Moving a Face to "Alice" creates a live Person named Alice, with the name key as sort name, the moved Face as cover and one
     * asset counted; the source counts one.
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
     * Moving a Face to "ALICE" gives it to Alice, who keeps her name and cover and counts two; no second Alice exists. Moving to
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
     * Two People with a Face each and nobody named "Alice". Both Faces are moved to the name at once: on PostgreSQL the second
     * waits at the name's lock until the first commits, on SQLite for the write connection.
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

  test("The Detail panel's Faces of an asset leave out bad matches and come in reading order, or by Frame time") {

    /**
     * Setup:
     *
     * An image with Faces of three People at x 300, 100 and 200, the third Person a bad match and the second hidden; a Video with
     * two Faces at 5000 ms and 2000 ms.
     *
     * Assertions:
     *
     * The image's rows are the two live people's Faces by x, each with its person, the hidden one marked; the bad match is left
     * out. The Video's rows are by Frame time.
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
     * People named "Zoe", "adam" and a hidden "Mia", three unnamed People, one of them merged away and one a bad match, and an
     * unnamed Person with every Face on a recycled asset.
     *
     * Assertions:
     *
     * The list is adam, Mia, Zoe (named, by name key), then the unnamed people by sort name, the one with no live asset included;
     * the merged-away and the bad-match people are left out.
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
}
