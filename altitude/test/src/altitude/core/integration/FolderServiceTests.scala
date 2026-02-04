package altitude.core.integration

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.models.Folder
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.{ should, shouldBe, shouldEqual, shouldNot }

import scala.language.reflectiveCalls

@DoNotDiscover class FolderServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  /** Standard set of folders to start with, can be used by many tests here */
  def folderHierarchyFixture: Object {
    val folder1: Folder
    val folder1_1: Folder
    val folder1_1_1: Folder
    val folder1_1_1_1: Folder
    val folder1_1_1_2: Folder
    val folder1_2: Folder
    val folder2: Folder
    val folder2_1: Folder
  } = new {
    /*
    folder1
      folder1_1
        folder1_1_1
          folder1_1_1_1
          folder1_1_1_2
      folder1_2
    folder2
      folder2_1
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder2: Folder = testApp.service.folder.add("folder2")
    val folder2_1: Folder = testApp.service.folder.add("folder2_1", parentId = folder2.id)

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1_1", parentId = folder1_1_1.id)

    val folder1_1_1_2: Folder = testApp.service.folder.add(name = "folder1_1_1_2", parentId = folder1_1_1.id)

    val folder1_2: Folder = testApp.service.folder.add(name = "folder1_2", parentId = folder1.id)
  }

  test("Invalid folder names should fail") {
    intercept[ValidationException] {
      testApp.service.folder.add("")
    }
    intercept[ValidationException] {
      testApp.service.folder.add(" ")
    }
    intercept[ValidationException] {
      testApp.service.folder.add(" ")
    }
    intercept[ValidationException] {
      testApp.service.folder.add("\t \t   ")
    }
  }

  test("New folders  should be free of user-entered space characters") {
    val folder1: Folder = testApp.service.folder.add(" folder  ")
    folder1.name shouldEqual "folder"

    val folder2: Folder = testApp.service.folder.add(" Folder one \n")
    folder2.name shouldEqual "Folder one"
  }

  test("Deleting a folder should also remove all children") {
    // see folderHierarchyFixture for folder hierarchy breakdown
    val f = folderHierarchyFixture

    testApp.service.library.deleteFolderById(f.folder1.persistedId)

    List(
      f.folder1.persistedId,
      f.folder1_1.persistedId,
      f.folder1_1_1.persistedId,
      f.folder1_1_1_1.persistedId,
      f.folder1_1_1_2.persistedId,
      f.folder1_2.persistedId
    ).foreach {
      id =>
        val recycledFolder: Folder = testApp.service.folder.getById(id)
        recycledFolder.isRecycled shouldBe true
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
    // see folderHierarchyFixture for folder hierarchy breakdown
    val f = folderHierarchyFixture

    // assert initial state
    // target
    testApp.app.service.folder.getChildren(rootId = f.folder2.persistedId).length shouldBe 1
    // source
    testApp.app.service.folder.getChildren(rootId = f.folder1_1.persistedId).length shouldBe 1

    // move folder1_1_1 to folder2
    testApp.service.folder.move(f.folder1_1_1.persistedId, f.folder2.persistedId)
    // target
    testApp.app.service.folder.getChildren(rootId = f.folder2.persistedId).length shouldBe 2
    // source
    testApp.app.service.folder.getChildren(rootId = f.folder1_1.persistedId).length shouldBe 0
  }

  test("Moving a folder to repository root should work") {
    // see folderHierarchyFixture for folder hierarchy breakdown
    val f = folderHierarchyFixture

    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 2

    testApp.service.folder.move(f.folder1_1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 3

    testApp.service.folder.move(f.folder1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 4
  }

  test("Can traverse the folder hierarchy") {
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

    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1", parentId = folder1_1_1.id)

    val folder2: Folder = testApp.service.folder.add("folder2")
    val folder2_4: Folder = testApp.service.folder.add("folder2_4", parentId = folder2.id)
    val folder2_3: Folder = testApp.service.folder.add("folder2_3", parentId = folder2.id)
    val folder2_2: Folder = testApp.service.folder.add("folder2_2", parentId = folder2.id)
    val folder2_1: Folder = testApp.service.folder.add("folder2_1", parentId = folder2.id)

    val folder3: Folder = testApp.service.folder.add("folder3")
    testApp.service.folder.add("folder3_1", parentId = folder3.id)

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
    (ancestorsOf1_1_1_1.map(_.persistedId) should contain)
      .allOf(folder1.persistedId, folder1_1.persistedId, folder1_1_1.persistedId)

    val allChildrenOf1: List[Folder] = testApp.service.folder.getChildrenRecursive(folder1.persistedId)
    allChildrenOf1.size shouldEqual (3)
    val allChildrenOf1Ids = allChildrenOf1.map(_.persistedId)

    (allChildrenOf1Ids should contain).allOf(folder1_1.persistedId, folder1_1_1.persistedId, folder1_1_1_1.persistedId)
  }

  test("Folder counts should be accurate") {
    // see folderHierarchyFixture for folder hierarchy breakdown
    val f = folderHierarchyFixture

    val rootFolderChildren = testApp.service.folder.getChildren(RequestContext.getRepository.rootFolderId)
    rootFolderChildren.size shouldEqual 2

    val rootFolder: Folder = testApp.service.folder.getById(RequestContext.getRepository.rootFolderId)
    rootFolder.numOfChildren shouldEqual 2

    val folder1Children = testApp.service.folder.getChildren(f.folder1.persistedId)
    folder1Children.size shouldEqual 2
    // the first subfolder should have 1 child
    folder1Children.head.numOfChildren shouldEqual 1
    // the second subfolder should have no children
    folder1Children.last.numOfChildren shouldEqual 0
  }

  test("Illegal folder move actions should throw") {
    // see folderHierarchyFixture for folder hierarchy breakdown
    val f = folderHierarchyFixture

    testApp.service.folder.add("folder3")

    // create folder1_1_1 as a duplicate under a different parent
    testApp.service.folder.add(name = "folder1_1_1", parentId = f.folder2.id)

    // move into itself
    intercept[IllegalOperationException] {
      testApp.service.folder.move(f.folder1.persistedId, f.folder1.persistedId)
    }

    // move into a child
    intercept[DuplicateException] {
      testApp.service.folder.move(f.folder1.persistedId, f.folder1_1_1.persistedId)
    }

    // move into a parent with the same immediate child name
    intercept[DuplicateException] {
      testApp.service.folder.move(f.folder1_1_1.persistedId, f.folder2.persistedId)
    }
  }

  test("Duplicate folder name moves should throw") {
    /*
    folder1
      child
    folder2
        CHILD
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "child", parentId = folder1.id)

    val folder2: Folder = testApp.service.folder.add("folder2")

    testApp.service.folder.add(name = "CHILD", parentId = folder2.id)

    // move into a parent with the same immediate child name (different casing)
    intercept[DuplicateException] {
      testApp.service.folder.move(folder1_1.persistedId, folder2.persistedId)
    }
  }

  test("Moving into a folder that doe not exist should throw") {
    val folder1: Folder = testApp.service.folder.add("folder1")

    // move into a folder that does not exist
    intercept[ValidationException] {
      testApp.service.folder.move(folder1.persistedId, "bogus-id")
    }
  }

  test("Rename a folder") {
    val folder1: Folder = testApp.service.folder.add("folder")

    testApp.service.folder.rename(folder1.persistedId, "newName")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "newName"
  }

  test("Folder name casing can be changed") {
    val folder1: Folder = testApp.service.folder.add("folder")

    testApp.service.folder.rename(folder1.persistedId, "Folder")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "Folder"
  }

  test("Duplicate folder rename actions should thrown") {
    val folder1: Folder = testApp.service.folder.add("folder1")
    val folder2: Folder = testApp.service.folder.add("folder2")

    intercept[DuplicateException] {
      testApp.service.folder.rename(folder1.persistedId, folder2.name)
    }
  }

  test("Illegal folder rename actions should throw") {
    val folder1: Folder = testApp.service.folder.add("folder")

    // rename a system folder
    intercept[IllegalOperationException] {
      testApp.service.folder.rename(RequestContext.getRepository.rootFolderId, folder1.name)
    }
  }
}
