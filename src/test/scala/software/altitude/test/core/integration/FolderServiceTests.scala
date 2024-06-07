package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.IllegalOperationException
import software.altitude.core.NotFoundException
import software.altitude.core.ValidationException
import software.altitude.core.models.Folder

@DoNotDiscover class FolderServiceTests (val config: Map[String, Any]) extends IntegrationTestCore {

  test("Folder hierarchy should contain all top level and child folders") {
    /*
      folder1
        folder1_1
        folder1_2
      */

    val folder1: Folder = altitude.service.library.addFolder("folder1")

    folder1.parentId shouldEqual ctx.repo.rootFolderId

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

        folder1_1.parentId should not be None

        val folder1_2: Folder = altitude.service.library.addFolder(
          name = "folder1_2", parentId = folder1.id)

        folder1.id should contain(folder1_2.parentId)

        /*
          folder2
            folder2_1
              folder2_1_1
            folder2_2
          */
        val folder2: Folder = altitude.service.library.addFolder(
          name = "folder2")

        folder2.parentId shouldEqual ctx.repo.rootFolderId

        val folder2_1: Folder = altitude.service.library.addFolder(
          name = "folder2_1", parentId = folder2.id)

        folder2.id should contain(folder2_1.parentId)

        val folder2_1_1: Folder = altitude.service.library.addFolder(
          name = "folder2_1_1", parentId = folder2_1.id)

        folder2_1.id should contain(folder2_1_1.parentId)

        val folder2_2: Folder = altitude.service.library.addFolder(
          name = "folder2_2", parentId = folder2.id)

        folder2.id should contain(folder2_2.parentId)

        val hierarchy = altitude.service.folder.hierarchy()
        hierarchy.length shouldBe 2
        hierarchy.head.children.length shouldBe 2
        hierarchy(1).children.length shouldBe 2
        hierarchy(1).children.head.children.length shouldBe 1
        hierarchy(1).children(1).children.length shouldBe 0

        // check immediate children of the second folder
        val immediateChildren = altitude.app.service.folder.immediateChildren(rootId = folder2_1.id.get)
        immediateChildren.length shouldBe 1

        // check breadcrumb
        val path = altitude.app.service.folder.pathComponents(folderId = folder2_1_1.id.get)
        path.length shouldBe 4
        path(1).id shouldBe folder2.id
        path.last.id shouldBe folder2_1_1.id

        SET_SECOND_REPO()

        val hierarchy2 = altitude.service.folder.hierarchy()
        hierarchy2 shouldBe empty
  }

  test("Hierarchy for a bad root ID should fail") {
    intercept[NotFoundException] {
      altitude.service.folder.hierarchy(rootId = Some("bogus"))
    }
  }

  test("Root folder path should be empty") {
    val path: List[Folder] = altitude.app.service.folder.pathComponents(folderId = ctx.repo.rootFolderId)
    path.length should equal(0)
  }

  test("Getting the path for a bad folder should fail") {
    intercept[NotFoundException] {
      altitude.app.service.folder.pathComponents(folderId = "bogus")
    }
  }

  test("Adding a duplicate folder under the same parent should fail") {
    val folder: Folder = altitude.service.library.addFolder("folder1")

    intercept[ValidationException] {
      altitude.service.library.addFolder(folder.name)
    }

    val folders = altitude.service.folder.hierarchy()
    folders.length shouldBe 1
  }

  test("Invalid folder names should fail") {
    intercept[ValidationException] {
      altitude.service.library.addFolder("")
    }
    intercept[ValidationException] {
      altitude.service.library.addFolder(" ")
    }
    intercept[ValidationException] {
      altitude.service.library.addFolder(" ")
    }
    intercept[ValidationException] {
      altitude.service.library.addFolder("\t \t   ")
    }
  }

  test("New folders  should be free of user-entered space characters") {
    val folder1: Folder = altitude.service.library.addFolder(" folder  ")
    folder1.name shouldEqual "folder"

    val folder2:Folder = altitude.service.library.addFolder(" Folder one \n")
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
    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1_1", parentId = folder1_1_1.id)

    val folder1_1_1_2: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1_2", parentId = folder1_1_1.id)

    val folder1_2: Folder = altitude.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    altitude.service.library.deleteFolderById(folder1.id.get)

    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1.id.get)
    }

    // children should be removed as well
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1_1.id.get)
    }
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1_1_1.id.get)
    }
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1_1_1_1.id.get)
    }
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1_1_1_2.id.get)
    }
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1_2.id.get)
    }
    intercept[NotFoundException] {
      altitude.service.folder.getById(folder1.id.get)
    }
  }

  test("Deleting a non-exisiting folder should fail with a NOT FOUND") {
    intercept[NotFoundException] {
      altitude.service.library.deleteFolderById("bogus")
    }
  }

  test("Deleting the root folder should fail") {
    intercept[IllegalOperationException] {
      altitude.service.library.deleteFolderById(ctx.repo.rootFolderId)
    }
  }

  test("Deleting a system folder should fail") {
    altitude.service.folder.systemFolders.foreach { sysFolder =>
      intercept[IllegalOperationException] {
        altitude.service.library.deleteFolderById(sysFolder.id.get)
      }
    }
  }

  test("Adding a child folder to a system folder shoul fail") {
    altitude.service.folder.systemFolders.foreach { sysFolder =>
      intercept[IllegalOperationException] {
        altitude.service.library.addFolder(name = "folder1", parentId = sysFolder.id)
       }
    }
  }

  test("Moving a folder") {
    /*
  folder1
    folder1_1
      folder1_1_1
    folder1_2
  folder2
  */
    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_2: Folder = altitude.service.library.addFolder(
      name = "folder1_2", parentId = folder1.id)

    val folder2: Folder = altitude.service.library.addFolder("folder2")

    // assert initial state
    // target
    altitude.app.service.folder.immediateChildren(rootId = folder2.id.get).length shouldBe 0
    // source
    altitude.app.service.folder.immediateChildren(rootId = folder1_1.id.get).length shouldBe 1

    // move folder1_1_1 to folder2
    altitude.service.library.moveFolder(folder1_1_1.id.get, folder2.id.get)
    // target
    altitude.app.service.folder.immediateChildren(rootId = folder2.id.get).length shouldBe 1
    // source
    altitude.app.service.folder.immediateChildren(rootId = folder1_1.id.get).length shouldBe 0
  }

  test("Moving a folder to repository root should work") {
    /*
  folder1
    folder1_1
      folder1_1_1
  folder2
  */
    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder2: Folder = altitude.service.library.addFolder("folder2")

    // assert initial state
    altitude.app.service.folder.immediateChildren(rootId = ctx.repo.rootFolderId).length shouldBe 2

    altitude.service.library.moveFolder(folder1_1_1.id.get, ctx.repo.rootFolderId)
    altitude.app.service.folder.immediateChildren(rootId = ctx.repo.rootFolderId).length shouldBe 3

    altitude.service.library.moveFolder(folder1_1.id.get, ctx.repo.rootFolderId)
    altitude.app.service.folder.immediateChildren(rootId = ctx.repo.rootFolderId).length shouldBe 4
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
    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1_1", parentId = folder1_1.id)

    val folder2: Folder = altitude.service.library.addFolder("folder2")

    // create folder1_1_1 as a duplicate under a different parent
    altitude.service.library.addFolder(
      name = "folder1_1_1", parentId = folder2.id)

    val folder3: Folder = altitude.service.library.addFolder("folder3")

    // create folder1_1_1 as a duplicate under a different parent
    altitude.service.library.addFolder(
      name = "FOLDER1_1_1", parentId = folder3.id)

    // move into itself
    intercept[IllegalOperationException] {
      altitude.service.library.moveFolder(folder1.id.get, folder1.id.get)
    }

    // move into a child
    intercept[IllegalOperationException] {
      altitude.service.library.moveFolder(folder1.id.get, folder1_1_1.id.get)
    }

    // move into a parent with the same immediate child name
    intercept[ValidationException] {
      altitude.service.library.moveFolder(folder1_1_1.id.get, folder2.id.get)
    }

    // move into a parent with the same immediate child name (different casing_
    intercept[ValidationException] {
      altitude.service.library.moveFolder(folder1_1_1.id.get, folder3.id.get)
    }

    // move into a folder that does not exist
    intercept[ValidationException] {
    altitude.service.library.moveFolder(folder1.id.get, "bogus")
    }
  }

  test("Rename a folder") {
    val folder1: Folder = altitude.service.library.addFolder("folder")

    altitude.service.library.renameFolder(folder1.id.get, "newName")

    val renamedFolder: Folder = altitude.service.folder.getById(folder1.id.get)
    renamedFolder.name shouldEqual "newName"
  }

  test("Folder name casing can be changed") {
    val folder1: Folder = altitude.service.library.addFolder("folder")

    altitude.service.library.renameFolder(folder1.id.get, "Folder")

    val renamedFolder: Folder = altitude.service.folder.getById(folder1.id.get)
    renamedFolder.name shouldEqual "Folder"
  }

  test("Illegal rename actions should throw") {
    val folder1: Folder = altitude.service.library.addFolder("folder")

    intercept[ValidationException] {
      altitude.service.library.renameFolder(folder1.id.get, folder1.name)
    }

    // rename a system folder
    intercept[IllegalOperationException] {
      altitude.service.library.renameFolder(ctx.repo.rootFolderId, folder1.name)
    }
  }
}
