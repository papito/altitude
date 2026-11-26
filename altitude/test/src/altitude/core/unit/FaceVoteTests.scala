package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.Face
import altitude.core.models.FaceMatch
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService.Owner
import altitude.core.service.FaceRecognitionService.Recognition
import altitude.core.service.FaceRecognitionService.assign
import altitude.core.service.FaceRecognitionService.vote

/** The pure vote behind recognition: who each of an asset's detections goes to, from its matches and the detections before it */
@DoNotDiscover class FaceVoteTests extends AnyFunSuite {

  /** An unsaved detection at `degrees` from the first axis, in a plane of the embedding space */
  private def detection(degrees: Double, isEnrolled: Boolean = true): Face = {
    val v = new Array[Float](FaceDetectionService.EMBEDDING_DIMENSIONS)
    v(0) = Math.cos(Math.toRadians(degrees)).toFloat
    v(1) = Math.sin(Math.toRadians(degrees)).toFloat
    Face(
      x1 = 0,
      y1 = 0,
      width = 10,
      height = 10,
      detectionScore = 0.9,
      checksum = "",
      features = v,
      quality = 20.0,
      isEnrolled = isEnrolled)
  }

  private val recognition = Recognition(matchCount = 5, distanceThreshold = 0.55)

  test("Most votes win, a tie goes to the owner of the nearest candidate, and no candidate is no owner") {

    /**
     * Setup:
     *
     * Candidates in distance order: two of one Person after one of another; one each of two people; none.
     *
     * Assertions:
     *
     * The Person with two votes wins though the other's is nearer; of one vote each, the nearer wins; no candidate is None.
     */
    vote(List("near" -> 0.1, "many" -> 0.2, "many" -> 0.3)) shouldBe Some("many")
    vote(List("near" -> 0.1, "far" -> 0.2)) shouldBe Some("near")
    vote(List.empty[(String, Double)]) shouldBe None
  }

  test("A detection votes with the stored Faces and the asset's enrolled detections before it, nearest first") {

    /**
     * Setup:
     *
     * Three detections at 0, 20 and 40 degrees. The first has no match and is enrolled; the second matches a stored Face of P at
     * 0.3; the third matches P at 0.2, and is 0.24 from the first and 0.06 from the second. One Face votes.
     *
     * Assertions:
     *
     * The first starts a Person; the second goes to that Person, at 0.06 nearer than P's Face; the third to the second's owner,
     * the nearest of all.
     *
     * Edge cases:
     *
     * With every candidate voting the third goes to the Person the first started, who has two votes to P's one.
     */
    val detections = List(detection(0), detection(20), detection(40))
    val matches = List(Nil, List(FaceMatch("P", 0.3)), List(FaceMatch("P", 0.2)))

    assign(detections, matches, recognition.copy(matchCount = 1), oneFacePerPerson = false) shouldBe
      List(Some(Owner.Started(0)), Some(Owner.Started(0)), Some(Owner.Started(0)))
    assign(detections, matches, recognition, oneFacePerPerson = false).last shouldBe Some(Owner.Started(0))
  }

  test("A match-only detection joins a match or nobody, and votes for no detection after it") {

    /**
     * Setup:
     *
     * A match-only detection matching P, a match-only one matching nobody, and an enrolled one at the same angle as both.
     *
     * Assertions:
     *
     * The first is P's, the second nobody's, and the third, with no stored match, starts the first Person of the asset.
     */
    val detections = List(detection(0, isEnrolled = false), detection(0, isEnrolled = false), detection(0))
    val matches = List(List(FaceMatch("P", 0.1)), Nil, Nil)

    assign(detections, matches, recognition, oneFacePerPerson = false) shouldBe
      List(Some(Owner.Known("P")), None, Some(Owner.Started(0)))
  }

  test("On a Video, a later detection of a Person already given one is nobody's, and each new Person is numbered") {

    /**
     * Setup:
     *
     * Four detections of a Video: one matching P, a second matching P, and two with no match, 90 degrees apart and far from the
     * first two.
     *
     * Assertions:
     *
     * The first is P's, the second dropped, and the last two start the asset's Persons 0 and 1.
     */
    val detections = List(detection(0), detection(0), detection(100), detection(190))
    val matches = List(List(FaceMatch("P", 0.1)), List(FaceMatch("P", 0.1)), Nil, Nil)

    assign(detections, matches, recognition, oneFacePerPerson = true) shouldBe
      List(Some(Owner.Known("P")), None, Some(Owner.Started(0)), Some(Owner.Started(1)))
  }
}
