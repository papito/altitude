package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.models.Person

@DoNotDiscover class FaceRecognitionServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Recognize a person") {
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    val importedAsset1: Asset = testApp.service.library.addImportAsset(importAsset1)
    val (face1, faceImages) = testApp.service.faceDetection.extractFaces(importAsset1.bytes).head
    faceImages.image should not be empty
    faceImages.displayImage should not be empty
    faceImages.alignedImage should not be empty
    faceImages.alignedImageGs should not be empty
    val recognizedPerson: Person = testApp.service.faceRecognition.recognizeFace(face1, importedAsset1)

    // Recognize
    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben3.png")
    val importedAsset2: Asset = testApp.service.library.addImportAsset(importAsset2)
    val (face2, _) = testApp.service.faceDetection.extractFaces(importAsset2.bytes).head

    val samePerson: Person = testApp.service.faceRecognition.recognizeFace(face2, importedAsset2)
    samePerson.persistedId shouldBe recognizedPerson.persistedId

    val persistedPerson = testApp.service.person.getPersonById(recognizedPerson.persistedId)
    persistedPerson.numOfFaces should be(2)
  }

  test("One person in a clip gives one Face, with a Frame time inside the clip") {
    val clip: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.oneFace.toFile))

    val faces = testApp.service.person.getAssetFaces(clip.persistedId)
    faces.size should be(1)
    faces.head.frameTimeMs.value should ((be >= 0L).and(be <= clip.durationMs.value))
    testApp.service.person.getPeopleForAsset(clip.persistedId).size should be(1)
  }

  test("Two people one after the other in a clip give two Faces of two people") {
    val clip: Asset =
      testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.twoPeopleInSequence.toFile))

    val faces = testApp.service.person.getAssetFaces(clip.persistedId)
    faces.size should be(2)
    faces.map(_.frameTimeMs.value).sorted match
      case List(first, second) =>
        // Each Face is pinned to a frame of its own person's span: the first three seconds, then the next three
        first should be < 3000L
        second should be >= 3000L
    testApp.service.person.getPeopleForAsset(clip.persistedId).map(_.persistedId).distinct.size should be(2)
  }

  test("The same person in a second clip, and in a photo, is one Person") {
    val photo: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/affleck.jpg"))
    val clip: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.oneFace.toFile))
    val secondClip: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.person("affleck.jpg", 2))).toFile))

    val people = List(photo, clip, secondClip).map(asset => testApp.service.person.getPeopleForAsset(asset.persistedId))
    people.foreach(_.size should be(1))
    people.map(_.head.persistedId).distinct.size should be(1)
    testApp.service.person.getPersonById(people.head.head.persistedId).numOfFaces should be(3)
  }

  test("Recognize two new people") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    people.size should be(2)
  }
}
