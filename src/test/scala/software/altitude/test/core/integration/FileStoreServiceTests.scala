package software.altitude.test.core.integration

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.equal
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.NotFoundException
import software.altitude.core.StorageException
import software.altitude.core.Util
import software.altitude.core.models.Asset
import software.altitude.core.models.Folder
import software.altitude.core.{Const => C}
import software.altitude.test.core.IntegrationTestCore
import software.altitude.test.core.IntegrationTestCore.fileToImportAsset

import java.io.File

@DoNotDiscover class FileStoreServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("move asset") {
    val asset = importFile("images/1.jpg")
    var relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    val folder: Folder = altitude.service.library.addFolder("folder1")
    relAssetPath = new File(folder.path.get, "1.jpg")
    val movedAsset = altitude.service.library.moveAssetToFolder(asset.id.get, folder.id.get)
    checkRepositoryFilePath(relAssetPath.getPath)
    movedAsset.path contains relAssetPath.getPath shouldBe true
  }

  test("move folder") {
    var folder1: Folder = altitude.service.library.addFolder("folder1")
    var asset1: Asset = testContext.persistAsset(folder = Some(folder1))
    val folder2: Folder = altitude.service.library.addFolder("folder2")
    testContext.persistAsset(folder = Some(folder2))

    altitude.service.library.moveFolder(folder1.id.get, folder2.id.get)
    checkNoRepositoryDirPath(folder1.path.get)

    folder1 = altitude.service.folder.getById(folder1.id.get)
    checkRepositoryDirPath(folder1.path.get)

    asset1 = altitude.app.service.library.getById(asset1.id.get)
    checkRepositoryFilePath(asset1.path.get)
  }

  test("move asset into conflicting file") {
    val asset = importFile("images/1.jpg")
    var relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    var folder: Folder = altitude.service.library.addFolder("folder1")
    relAssetPath = new File(folder.path.get, "1.jpg")
    val movedAsset = altitude.service.library.moveAssetToFolder(asset.id.get, folder.id.get)

    // create a dummy file in place of destination
    folder  = altitude.service.library.addFolder("folder2")
    relAssetPath = new File(folder.path.get, "1.jpg")
    FileUtils.writeByteArrayToFile(getAbsoluteFile(relAssetPath.getPath), new Array[Byte](0))

    // move the file again - into an existing file
    intercept[StorageException] {
      altitude.service.library.moveAssetToFolder(movedAsset.id.get, folder.id.get)
    }
  }

  test("rename directory") {
    var folder1: Folder = altitude.service.library.addFolder("folder1")

    var asset1: Asset = testContext.persistAsset(folder = Some(folder1))
    val asset2: Asset = testContext.persistAsset(folder = Some(folder1))

    asset1.path should not be None
    asset2.path should not be None

    checkRepositoryFilePath(asset1.path.get)
    checkRepositoryFilePath(asset2.path.get)

    folder1 = altitude.service.library.renameFolder(folder1.id.get, "newName")

    checkNoRepositoryFilePath(asset1.path.get)
    checkNoRepositoryFilePath(asset2.path.get)

    asset1 = altitude.service.library.getById(asset1.id.get)
    asset1.path should not be None
    asset1.path.value should not be empty
    asset1.path.get contains "newName" shouldBe true
    asset1.path.get.endsWith(asset1.fileName) shouldBe true
    checkRepositoryFilePath(asset1.path.get)
  }

  test("delete folder") {
    val folder1: Folder = altitude.service.library.addFolder("folder1")
    altitude.service.library.deleteFolderById(folder1.id.get)
    checkRepositoryDirPath("")
  }

  test("delete folder and assets") {
    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val asset1: Asset = testContext.persistAsset(folder = Some(folder1))
    val asset2: Asset = testContext.persistAsset(folder = Some(folder1))

    asset1.path should not be None
    asset2.path should not be None

    checkRepositoryFilePath(asset1.path.get)
    checkRepositoryFilePath(asset2.path.get)

    altitude.service.library.deleteFolderById(folder1.id.get)

    checkNoRepositoryFilePath(asset1.path.get)
    checkNoRepositoryFilePath(asset2.path.get)
  }

  test("restore asset") {
    val asset1 = importFile("images/1.jpg")
    val relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    // move asset to trash
    altitude.service.library.recycleAsset(asset1.id.get)
    checkNoRepositoryFilePath(relAssetPath.getPath)

    // restore the asset
    altitude.service.library.restoreRecycledAsset(asset1.id.get)
    checkRepositoryFilePath(relAssetPath.getPath)
  }

  test("restore asset into conflicting file") {
    val asset1 = importFile("images/1.jpg")
    val relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    // move asset to trash
    altitude.service.library.recycleAsset(asset1.id.get)
    checkNoRepositoryFilePath(relAssetPath.getPath)

    // create a dummy file in place of previously recycled asset
    FileUtils.writeByteArrayToFile(getAbsoluteFile(asset1.path.get), new Array[Byte](0))
    checkRepositoryFilePath(relAssetPath.getPath)

    intercept[StorageException] {
      altitude.service.library.restoreRecycledAsset(asset1.id.get)
    }
  }

  test("rename asset") {
    var asset = importFile("images/1.jpg")
    var relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    asset = altitude.service.library.renameAsset(asset.id.get, "2.jpg")
    relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "2.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    asset = altitude.service.library.renameAsset(asset.id.get, "3.jpg")
    relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "3.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)
  }

  test("rename asset into existing file") {
    var asset = importFile("images/1.jpg")
    var relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "1.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    asset = altitude.service.library.renameAsset(asset.id.get, "2.jpg")
    relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "2.jpg")
    checkRepositoryFilePath(relAssetPath.getPath)

    // rename the asset with a file already at destination
    relAssetPath = new File(altitude.service.fileStore.triageFolderPath, "3.jpg")
    FileUtils.writeByteArrayToFile(getAbsoluteFile(relAssetPath.getPath), new Array[Byte](0))

    intercept[StorageException] {
      asset = altitude.service.library.renameAsset(asset.id.get, "3.jpg")
    }
  }

  test("Folder tree can be created and deleted") {
    /*
      folder1
      folder2
        folder2_1
          folder2_1_1
        folder2_2
      */

    val folder1: Folder = altitude.service.library.addFolder(
      name = "folder1")

    var relativePath = FilenameUtils.concat(C.Path.ROOT, folder1.name)
    checkRepositoryDirPath(relativePath)

    val folder2: Folder = altitude.service.library.addFolder(
      name = "folder2")

    relativePath = FilenameUtils.concat(C.Path.ROOT, folder2.name)
    checkRepositoryDirPath(relativePath)

    val folder2_1: Folder = altitude.service.library.addFolder(
      name = "folder2_1", parentId = folder2.id)

    relativePath = folder2_1.path.get
    checkRepositoryDirPath(relativePath)

    val folder2_1_1: Folder = altitude.service.library.addFolder(
      name = "folder2_1_1", parentId = folder2_1.id)

    relativePath = folder2_1_1.path.get
    checkRepositoryDirPath(relativePath)

    val folder2_2: Folder = altitude.service.library.addFolder(
      name = "folder2_2", parentId = folder2.id)

    relativePath = folder2_2.path.get
    checkRepositoryDirPath(relativePath)

    // delete the empty folder
    altitude.service.library.deleteFolderById(folder1.id.get)
    relativePath = FilenameUtils.concat(C.Path.ROOT, folder1.name)
    checkNoRepositoryDirPath(relativePath)

    // delete the folder with children
    altitude.service.library.deleteFolderById(folder2.id.get)
    relativePath = FilenameUtils.concat(C.Path.ROOT, folder2.name)
    checkNoRepositoryDirPath(relativePath)

    // doing so again is a not found
    intercept[NotFoundException] {
        altitude.service.library.deleteFolderById(folder2.id.get)
    }

    val folder3: Folder = altitude.service.library.addFolder(
      name = "folder3")

    val folder3_3: Folder = altitude.service.library.addFolder(
      name = "folder3_3", parentId = folder3.id)

    altitude.service.library.renameFolder(folder3.id.get, "folder3_new")
    relativePath = FilenameUtils.concat(C.Path.ROOT, folder3_3.name)
    checkNoRepositoryDirPath(relativePath)
    relativePath = FilenameUtils.concat(C.Path.ROOT, "folder3_new")
    checkRepositoryDirPath(relativePath)
  }

  test("Creating new repository sets up the folder tree") {
    altitude.service.repository.addRepository(
      name = Util.randomStr(),
      fileStoreType = C.FileStoreType.FS,
      owner = testContext.user)

    checkRepositoryDirPath(C.Path.ROOT)
    checkRepositoryDirPath(C.Path.TRIAGE)
    checkRepositoryDirPath(C.Path.TRASH)
  }

  test("Recycled path of extension-less files should not contain trailing separator") {
    val assetModel = testContext.makeAsset(filename = "filename")
    val asset: Asset = testContext.persistAsset(Some(assetModel))
    asset.fileName shouldEqual "filename"

    val recycledAsset: Asset = altitude.service.library.recycleAsset(asset.id.get)
    recycledAsset.fileName shouldEqual "filename"

    val relAssetPath = new File(altitude.service.fileStore.trashFolderPath, recycledAsset.id.get)
    checkRepositoryFilePath(relAssetPath.getPath)
  }

  private def checkRepositoryDirPath(path: String) = {
    // get current repo root
    val f = getAbsoluteFile(path)
    f.exists shouldBe true
    f.isDirectory shouldBe true
  }

  private def checkNoRepositoryDirPath(path: String) = {
    val f = getAbsoluteFile(path)
    f.exists shouldBe false
  }

  private def checkRepositoryFilePath(path: String) = {
    val f = getAbsoluteFile(path)
    f.exists shouldBe true
    f.isFile shouldBe true
  }

  private def checkNoRepositoryFilePath(path: String) = {
    // get current repo root
    val rootPath = testContext.repository.fileStoreConfig(C.Repository.Config.PATH)
    val f = new File(rootPath, path)
    f.exists shouldBe false
  }

  private def getAbsoluteFile(path: String): File = {
    val rootPath = testContext.repository.fileStoreConfig(C.Repository.Config.PATH)
    new File(rootPath, path)
  }

  private def importFile(path: String): Asset = {
    switchContextRepo(testContext.repository)

    val _path = getClass.getResource(s"/import/$path").getPath
    val fileImportAsset = fileToImportAsset(new File(_path))
    val importedAsset = altitude.service.assetImport.importAsset(fileImportAsset).get
    importedAsset.assetType should equal(importedAsset.assetType)
    importedAsset.path should not be empty
    importedAsset.checksum should not be empty

    val asset: Asset = altitude.service.library.getById(importedAsset.id.get)
    asset.assetType should equal(importedAsset.assetType)
    asset.path should not be empty
    asset.checksum should not be empty
    asset.sizeBytes should not be 0
    asset
  }
}
