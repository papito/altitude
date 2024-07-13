package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
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

  test("Folder hierarchy should contain all top level and child folders") {
    /*
      folder1
        folder1_1
        folder1_2
      */

    val folder1: Folder = testApp.service.library.addFolder("folder1")

    folder1.parentId shouldEqual RequestContext.repository.value.get.rootFolderId

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    folder1_1.parentId should not be None

    val folder1_2: Folder = testApp.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    folder1.id should contain(folder1_2.parentId)

    /*
      folder2
        folder2_1
          folder2_1_1
        folder2_2
      */
    val folder2: Folder = testApp.service.library.addFolder(
      name = "folder2")

    folder2.parentId shouldEqual RequestContext.repository.value.get.rootFolderId

    val folder2_1: Folder = testApp.service.library.addFolder(
      name = "folder2_1", parentId = folder2.id)

    folder2.id should contain(folder2_1.parentId)

    val folder2_1_1: Folder = testApp.service.library.addFolder(
      name = "folder2_1_1", parentId = folder2_1.id)

    folder2_1.id should contain(folder2_1_1.parentId)

    val folder2_2: Folder = testApp.service.library.addFolder(
      name = "folder2_2", parentId = folder2.id)

    folder2.id should contain(folder2_2.parentId)

    val hierarchy = testApp.service.folder.hierarchy()
    hierarchy.length shouldBe 2
    hierarchy.head.children.length shouldBe 2
    hierarchy(1).children.length shouldBe 2
    hierarchy(1).children.head.children.length shouldBe 1
    hierarchy(1).children(1).children.length shouldBe 0

    // check immediate children of the second folder
    val immediateChildren = testApp.app.service.folder.immediateChildren(rootId = folder2_1.persistedId)
    immediateChildren.length shouldBe 1

    // check breadcrumb
    val path = testApp.app.service.folder.pathComponents(folderId = folder2_1_1.persistedId)
    path.length shouldBe 4
    path(1).id shouldBe folder2.id
    path.last.id shouldBe folder2_1_1.id

    // SECOND REPO
    val repo2 = testContext.persistRepository()
    switchContextRepo(repo2)

    val hierarchy2 = testApp.service.folder.hierarchy()
    hierarchy2 shouldBe empty
  }

  test("Hierarchy for a bad root ID should fail") {
    intercept[NotFoundException] {
      testApp.service.folder.hierarchy(rootId = Some("bogus"))
    }
  }

  test("Root folder path should be empty") {
    val path: List[Folder] = testApp.app.service.folder.pathComponents(folderId = RequestContext.repository.value.get.rootFolderId)
    path.length should equal(0)
  }

  test("Getting the path for a bad folder should fail") {
    intercept[NotFoundException] {
      testApp.app.service.folder.pathComponents(folderId = "bogus")
    }
  }

  test("Adding a duplicate folder under the same parent should fail") {
    val folder: Folder = testApp.service.library.addFolder("folder1")

    commit()

    intercept[DuplicateException] {
      testApp.service.library.addFolder(folder.name)
    }

    rollback()

    val folders = testApp.service.folder.hierarchy()
    folders.length shouldBe 1
  }

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
      testApp.service.library.deleteFolderById(RequestContext.repository.value.get.rootFolderId)
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
    testApp.app.service.folder.immediateChildren(rootId = folder2.persistedId).length shouldBe 0
    // source
    testApp.app.service.folder.immediateChildren(rootId = folder1_1.persistedId).length shouldBe 1

    // move folder1_1_1 to folder2
    testApp.service.library.moveFolder(folder1_1_1.persistedId, folder2.persistedId)
    // target
    testApp.app.service.folder.immediateChildren(rootId = folder2.persistedId).length shouldBe 1
    // source
    testApp.app.service.folder.immediateChildren(rootId = folder1_1.persistedId).length shouldBe 0
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

    // assert initial state
    testApp.app.service.folder.immediateChildren(rootId = RequestContext.repository.value.get.rootFolderId).length shouldBe 2

    testApp.service.library.moveFolder(folder1_1_1.persistedId, RequestContext.repository.value.get.rootFolderId)
    testApp.app.service.folder.immediateChildren(rootId = RequestContext.repository.value.get.rootFolderId).length shouldBe 3

    testApp.service.library.moveFolder(folder1_1.persistedId, RequestContext.repository.value.get.rootFolderId)
    testApp.app.service.folder.immediateChildren(rootId = RequestContext.repository.value.get.rootFolderId).length shouldBe 4
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

    // move into a parent with the same immediate child name (different casing)
    intercept[DuplicateException] {
      testApp.service.library.moveFolder(folder1_1_1.persistedId, folder3.persistedId)
    }

    // move into a folder that does not exist
    intercept[ValidationException] {
      testApp.service.library.moveFolder(folder1.persistedId, "bogus")
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

  test("Illegal folder rename actions should throw") {
    val folder1: Folder = testApp.service.library.addFolder("folder")

    intercept[DuplicateException] {
      testApp.service.library.renameFolder(folder1.persistedId, folder1.name)
    }

    // rename a system folder
    intercept[IllegalOperationException] {
      testApp.service.library.renameFolder(RequestContext.repository.value.get.rootFolderId, folder1.name)
    }
  }

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

  test("Folders child count should be correct after moving", Focused) {
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
}
