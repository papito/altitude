package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService
import altitude.core.service.FaceRecognitionService.Cluster
import altitude.core.service.FaceRecognitionService.cosineDistance
import altitude.core.service.FaceRecognitionService.meanNormalized

/** The pure clustering behind a Video's Faces, on unit vectors in a plane of the embedding space */
@DoNotDiscover class FaceClusteringTests extends AnyFunSuite {

  /** A detection stand-in: a unit vector at `degrees` from the first axis, with a quality */
  case class Detection(degrees: Double, quality: Double)

  private def features(d: Detection): Array[Float] =
    val v = new Array[Float](FaceDetectionService.EMBEDDING_DIMENSIONS)
    v(0) = Math.cos(Math.toRadians(d.degrees)).toFloat
    v(1) = Math.sin(Math.toRadians(d.degrees)).toFloat
    v

  private val threshold = 0.55

  test("The normalized mean of two unit vectors is a unit vector equidistant from both, and of one vector is itself") {

    /**
     * Setup:
     *
     * Two orthogonal unit vectors, at 0 and 90 degrees in a plane of the embedding space.
     *
     * Assertions:
     *
     * Their normalized mean has unit length and lies at 45 degrees, the same cosine distance from both.
     *
     * Edge cases:
     *
     * The mean of a single vector is that vector unchanged.
     */
    val a = features(Detection(0, 1))
    val b = features(Detection(90, 1))
    val mean = meanNormalized(Seq(a, b))
    Math.sqrt(mean.map(x => x.toDouble * x).sum) shouldBe 1.0 +- 1e-6
    cosineDistance(mean, a) shouldBe cosineDistance(mean, b) +- 1e-6
    cosineDistance(mean, a) shouldBe (1 - Math.cos(Math.toRadians(45))) +- 1e-6
    meanNormalized(Seq(a)).toSeq shouldBe a.toSeq
  }

  test("A detection joins the first cluster within the threshold, in descending quality, else starts one") {

    /**
     * Setup:
     *
     * Detections at 0, 40 and 80 degrees with qualities 3, 2 and 1, passed in out of quality order and clustered at a 0.55
     * threshold. 0 and 40 degrees are 0.23 apart, 0 and 80 degrees 0.83.
     *
     * Assertions:
     *
     * Clustering goes in descending quality and compares with each cluster's leader: the 40 degree detection joins the 0 degree
     * one, and the 80 degree one is too far from that leader and starts its own cluster.
     */
    val a = Detection(0, 3)
    val b = Detection(40, 2)
    val c = Detection(80, 1)
    val clusters = FaceRecognitionService.cluster(Seq(c, a, b), features, _.quality, threshold)
    clusters shouldBe List(Cluster(a, List(a, b)), Cluster(c, List(c)))
  }

  test("The representative is the highest-quality member, whatever the order of arrival") {

    /**
     * Setup:
     *
     * Detections at 0, 40 and 80 degrees with qualities 1, 3 and 2, the best one arriving last.
     *
     * Assertions:
     *
     * The 40 degree detection leads, and being within the threshold of both others, gathers all three into one cluster ordered by
     * quality.
     */
    val a = Detection(0, 1)
    val b = Detection(40, 3)
    val c = Detection(80, 2)
    // b is within the threshold of both others, so with b leading all three are one cluster
    FaceRecognitionService.cluster(Seq(a, c, b), features, _.quality, threshold) shouldBe List(Cluster(b, List(b, c, a)))
  }

  test("Clusters whose centroids are within the threshold are merged, the higher-quality one absorbing the other") {

    /**
     * Setup:
     *
     * Two clusters as clustering leaves them, 0 and 40 degrees led by quality 3 and 80 degrees alone; then two single clusters at
     * 0 and 120 degrees.
     *
     * Assertions:
     *
     * The first pair's centroids are within the threshold, so the higher-quality cluster absorbs the other's members; the
     * far-apart pair is left as it is.
     */
    val a = Detection(0, 3)
    val b = Detection(40, 2)
    val c = Detection(80, 1)
    val split = List(Cluster(a, List(a, b)), Cluster(c, List(c)))
    // The centroid of a and b is at 20 degrees, 0.5 from c
    FaceRecognitionService.mergeClusters(split, features, threshold) shouldBe List(Cluster(a, List(a, b, c)))

    val far = List(Cluster(a, List(a)), Cluster(Detection(120, 1), List(Detection(120, 1))))
    FaceRecognitionService.mergeClusters(far, features, threshold) shouldBe far
  }
}
