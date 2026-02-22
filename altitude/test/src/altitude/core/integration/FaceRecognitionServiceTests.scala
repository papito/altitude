package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.models.Person
import altitude.test.IntegrationTestUtil
import org.scalatest.matchers.should.Matchers.{should, shouldBe}

@DoNotDiscover class FaceRecognitionServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Recognize a person twice", Focused) {
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset1: Asset = testApp.service.library.addImportAsset(importAsset1)
    val (face1, faceImages) = testApp.service.faceDetection.extractFaces(importAsset1.data).head
    faceImages.image should not be empty
    faceImages.displayImage should not be empty
    faceImages.alignedImage should not be empty
    faceImages.alignedImageGs should not be empty
    // val recognizedPerson: Person = testApp.service.faceRecognition.recognizeFace(face1, importedAsset1)

    // Recognize again
    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    val importedAsset2: Asset = testApp.service.library.addImportAsset(importAsset2)
    val (face2, _) = testApp.service.faceDetection.extractFaces(importAsset2.data).head

    // val samePerson: Person = testApp.service.faceRecognition.recognizeFace(face2, importedAsset2)
//    samePerson.persistedId shouldBe recognizedPerson.persistedId
//
    // Recognize a second time
    val importAsset3 = IntegrationTestUtil.getImportAsset("people/meme-ben3.png")
    val importedAsset3: Asset = testApp.service.library.addImportAsset(importAsset3)
    val (face3, _) = testApp.service.faceDetection.extractFaces(importAsset3.data).head

    // val samePersonAgain: Person = testApp.service.faceRecognition.recognizeFace(face3, importedAsset3)
//    samePersonAgain.persistedId shouldBe recognizedPerson.persistedId
//
//    val persistedPerson = testApp.service.person.getPersonById(recognizedPerson.persistedId)
//    persistedPerson.numOfFaces should be(3)
  }

  test("Recognize two new people") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

//    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
//    people.size should be(2)
  }
}
