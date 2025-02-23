package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.IllegalOperationException
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.ValidationException
import software.altitude.core.models.Folder
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class FolderServiceTests (override val testApp: Altitude) extends IntegrationTestCore {

  test("Invalid folder names should fail") {
    intercept[ValidationException] {
      testApp.service.library.addFolder("")
    }
    intercept[ValidationException] {
      testApp.service.library.addFolder(" ")
    }
    intercept[ValidationException] {
      testApp.service.library.addFolder(" ")
    }
    intercept[ValidationException] {
      testApp.service.library.addFolder("\t \t   ")
    }
  }

  test("New folders  should be free of user-entered space characters") {
    val folder1: Folder = testApp.service.library.addFolder(" folder  ")
    folder1.name shouldEqual "folder"

    val folder2:Folder = testApp.service.library.addFolder(" Folder one \n")
    folder2.name shouldEqual "Folder one"
  }

  test("Deleting a folder should also remove all children") {
    /*
  folder1
    folder1_1
      folder1_1_1
        folder1_1_1_1
        folder1_1_1_2
    folder1_2
  */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1_1", parentId = folder1_1_1.id)

    val folder1_1_1_2: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1_2", parentId = folder1_1_1.id)

    val folder1_2: Folder = testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    testApp.service.library.deleteFolderById(folder1.persistedId)

    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1.persistedId)
    }

    // children should be removed as well
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1_1.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1_1_1.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_1_1_2.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1_2.persistedId)
    }
    intercept[NotFoundException] {
      testApp.service.folder.getById(folder1.persistedId)
    }
  }

  test("Deleting a non-existing folder should fail with a NOT FOUND") {
    intercept[NotFoundException] {
      testApp.service.library.deleteFolderById("bogus")
    }
  }

  test("Deleting the root folder should fail") {
    intercept[IllegalOperationException] {
      testApp.service.library.deleteFolderById(RequestContext.getRepository.rootFolderId)
    }
  }

  test("Moving a folder to another folder should work") {
    /*
    folder1
      folder1_1
        folder1_1_1
      folder1_2
    folder2
    */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    // assert initial state
    // target
    testApp.app.service.folder.getChildren(rootId = folder2.persistedId).length shouldBe 0
    // source
    testApp.app.service.folder.getChildren(rootId = folder1_1.persistedId).length shouldBe 1

    // move folder1_1_1 to folder2
    testApp.service.library.moveFolder(folder1_1_1.persistedId, folder2.persistedId)
    // target
    testApp.app.service.folder.getChildren(rootId = folder2.persistedId).length shouldBe 1
    // source
    testApp.app.service.folder.getChildren(rootId = folder1_1.persistedId).length shouldBe 0
  }

  test("Moving a folder to repository root should work") {
    /*
  folder1
    folder1_1
      folder1_1_1
  folder2
  */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    testApp.service.library.addFolder("folder2")

    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 2

    testApp.service.library.moveFolder(folder1_1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 3

    testApp.service.library.moveFolder(folder1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 4
  }

  test("Can traverse the folder hierarchy", Focused) {
    /*
    folder1
      folder1_1
        folder1_1_1
          folder1_1_1_1
    folder2
        folder2_1
        folder2_2
        folder2_3
        folder2_4
    folder3
        folder_3_1
    */

    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1_1.id)

    val folder2: Folder = testApp.service.library.addFolder("folder2")
    val folder2_4: Folder = testApp.service.library.addFolder("folder2_4", parentId = folder2.id)
    val folder2_3: Folder = testApp.service.library.addFolder("folder2_3", parentId = folder2.id)
    val folder2_2: Folder = testApp.service.library.addFolder("folder2_2", parentId = folder2.id)
    val folder2_1: Folder = testApp.service.library.addFolder("folder2_1", parentId = folder2.id)

    val folder3: Folder = testApp.service.library.addFolder("folder3")
    testApp.service.library.addFolder("folder3_1", parentId = folder3.id)

    // getting immediate children of root should not include the root folder itself
    val childrenOfRoot = testApp.service.folder.getChildren(RequestContext.getRepository.rootFolderId)
    childrenOfRoot.size shouldEqual 3
    childrenOfRoot.map(_.persistedId) shouldNot contain(RequestContext.getRepository.rootFolderId)

    val childrenOf1: List[Folder] = testApp.service.folder.getChildren(folder1.persistedId)
    childrenOf1.size shouldEqual 1
    childrenOf1.head.name shouldEqual folder1_1.name

    val childrenOf2: List[Folder] = testApp.service.folder.getChildren(folder2.persistedId)
    childrenOf2.size shouldEqual 4

    childrenOf2.map(_.persistedId) shouldEqual List(
      folder2_1.persistedId,
      folder2_2.persistedId,
      folder2_3.persistedId,
      folder2_4.persistedId)

    val ancestorsOf1_1_1_1: List[Folder] = testApp.service.folder.getAncestors(folder1_1_1_1.persistedId)
    ancestorsOf1_1_1_1.size shouldEqual 3
    ancestorsOf1_1_1_1.map(_.persistedId) should contain allOf (
      folder1.persistedId,
      folder1_1.persistedId,
      folder1_1_1.persistedId)

    val allChildrenOf1: List[Folder] = testApp.service.folder.getChildrenRecursive(folder1.persistedId)
    allChildrenOf1.size shouldEqual(3)
    val allChildrenOf1Ids = allChildrenOf1.map(_.persistedId)

    allChildrenOf1Ids should contain allOf (
      folder1_1.persistedId,
      folder1_1_1.persistedId,
      folder1_1_1_1.persistedId)
  }

  test("Illegal folder move actions should throw") {
    /*
    folder1
      folder1_1
        folder1_1_1
    folder2
        folder1_1_1
    folder3
        FOLDER1_1_1
    */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    // create folder1_1_1 as a duplicate under a different parent
    testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder2.id)

    val folder3: Folder = testApp.service.library.addFolder("folder3")

    // create folder1_1_1 as a duplicate under a different parent
    testApp.service.library.addFolder(
      name = "FOLDER1_1_1", parentId = folder3.id)

    // move into itself
    intercept[IllegalOperationException] {
      testApp.service.library.moveFolder(folder1.persistedId, folder1.persistedId)
    }

    // move into a child
    intercept[DuplicateException] {
      testApp.service.library.moveFolder(folder1.persistedId, folder1_1_1.persistedId)
    }

    // move into a parent with the same immediate child name
    intercept[DuplicateException] {
      testApp.service.library.moveFolder(folder1_1_1.persistedId, folder2.persistedId)
    }
  }

  test("Duplicate folder name moves should throw") {
    /*
    folder1
      child
    folder2
        CHILD
    */
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "child", parentId = folder1.id)

    val folder2: Folder = testApp.service.library.addFolder("folder2")

    testApp.service.library.addFolder(name = "CHILD", parentId = folder2.id)

    // move into a parent with the same immediate child name (different casing)
    intercept[DuplicateException] {
      testApp.service.library.moveFolder(folder1_1.persistedId, folder2.persistedId)
    }
  }

  test("Moving into a folder that doe not exist should throw") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")

    // move into a folder that does not exist
    intercept[ValidationException] {
      testApp.service.library.moveFolder(folder1.persistedId, "bogus-id")
    }
  }

  test("Rename a folder") {
    val folder1: Folder = testApp.service.library.addFolder("folder")

    testApp.service.library.renameFolder(folder1.persistedId, "newName")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "newName"
  }

  test("Folder name casing can be changed") {
    val folder1: Folder = testApp.service.library.addFolder("folder")

    testApp.service.library.renameFolder(folder1.persistedId, "Folder")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "Folder"
  }

  test("Duplicate folder rename actions should thrown") {
    val folder1: Folder = testApp.service.library.addFolder("folder1")
    val folder2: Folder = testApp.service.library.addFolder("folder2")

    intercept[DuplicateException] {
      testApp.service.library.renameFolder(folder1.persistedId, folder2.name)
    }
  }

  test("Illegal folder rename actions should throw") {
    val folder1: Folder = testApp.service.library.addFolder("folder")

    // rename a system folder
    intercept[IllegalOperationException] {
      testApp.service.library.renameFolder(RequestContext.getRepository.rootFolderId, folder1.name)
    }
  }

/*
  test("Folders child count should be correct after addition") {
    /*
      folder1
        folder1_1
        folder1_2
      */

    var folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_2: Folder = testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfChildren shouldBe 2

    testApp.service.library.deleteFolderById(folder1_1.persistedId)
    testApp.service.library.deleteFolderById(folder1_2.persistedId)

    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfChildren shouldBe 0
  }

  test("Folders child count should be correct after moving") {
    /*
    folder1
      folder1_1
        folder1_1_1
      folder1_2
    folder2
    */
    var folder1: Folder = testApp.service.library.addFolder("folder1")

    var folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    var folder1_1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    var folder2: Folder = testApp.service.library.addFolder("folder2")

    // before any actions, check the baseline
    folder1 = testApp.service.folder.getById(folder1.persistedId)
    folder1.numOfChildren shouldBe 2
    var rootFolder: Folder = testApp.service.folder.getById(testContext.repository.rootFolderId)
    rootFolder.numOfChildren shouldBe 2

    folder1_1 = testApp.service.folder.getById(folder1_1.persistedId)
    folder1_1.numOfChildren shouldBe 1

    //
    // move folder1_1_1 to folder1
    //
    testApp.service.library.moveFolder(folder1_1_1.persistedId, folder1.persistedId)

    // target
    folder1 = testApp.service.folder.getById(folder1.persistedId)
    // source
    folder1_1 = testApp.service.folder.getById(folder1_1.persistedId)

    folder1.numOfChildren shouldBe 3
    folder1_1.numOfChildren shouldBe 0

    rootFolder = testApp.service.folder.getById(testContext.repository.rootFolderId)
    rootFolder.numOfChildren shouldBe 2

    //
    // move folder1_1 to root
    //
    testApp.service.library.moveFolder(folder1_1.persistedId, testContext.repository.rootFolderId)

    // target
    rootFolder = testApp.service.folder.getById(testContext.repository.rootFolderId)
    // source
    folder1 = testApp.service.folder.getById(folder1.persistedId)

    folder1.numOfChildren shouldBe 2
    rootFolder.numOfChildren shouldBe 3
  }
*/
}
