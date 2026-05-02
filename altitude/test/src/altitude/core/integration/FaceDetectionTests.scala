package altitude.core.integration

import altitude.core.Altitude
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.should

@DoNotDiscover class FaceDetectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Face is detected in an image (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face1.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face2.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/affleck.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (3)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/damon.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.data, Some(importAsset.fileName))
    faces.length should be(1)
  }
}
