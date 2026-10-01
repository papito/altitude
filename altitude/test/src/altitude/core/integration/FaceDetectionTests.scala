package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import org.opencv.core.Mat
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.util.ImageUtil.matFromBytes

@DoNotDiscover class FaceDetectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Face is detected in an image (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face1.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face2.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/affleck.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (3)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/damon.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("A blurred copy of a portrait has a lower embedding norm than the sharp one") {
    // The same letterboxed frame sharp and blurred; a blur small against the face disappears in the 112 px alignment
    embeddingNorm(TestVideos.personFrame("affleck.jpg")) should be > embeddingNorm(
      TestVideos.personFrame("affleck.jpg", TestVideos.FRAME_BLUR))
  }

  test("A sharp portrait is enrolled") {
    val face = testApp.service.faceDetection.extractFaces(IntegrationTestUtil.getImportAsset("people/affleck.jpg").bytes).head._1
    face.quality should be > 0.0
    face.isEnrolled should be(true)
  }

  test("A blurred face is kept as match-only") {
    val bytes = Files.readAllBytes(TestVideos.frameStill("affleck.jpg", TestVideos.FRAME_BLUR))
    testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(bytes)).size should be(1)

    val faces = testApp.service.faceDetection.extractFaces(bytes)
    faces.size should be(1)
    faces.head._1.isEnrolled should be(false)
  }

  test("A heavily blurred face is detected but not kept") {
    val bytes = Files.readAllBytes(TestVideos.still("meme-ben.jpg", TestVideos.HEAVY_BLUR))
    // Detected, so an empty result below is the quality floor at work rather than YuNet giving up
    testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(bytes)).size should be(1)

    testApp.service.faceDetection.extractFaces(bytes) shouldBe empty
  }

  /** The norm of the raw ArcFace embedding of the first face YuNet finds in the image */
  private def embeddingNorm(image: Mat): Float =
    val detection = testApp.service.faceDetection.detectFacesWithYunet(image).head
    val aligned = testApp.service.faceDetection.alignCropFaceFromDetection(image, detection)
    testApp.service.faceDetection.getArcFaceEmbedding(aligned).norm
}
