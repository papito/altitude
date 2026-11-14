package altitude.core.integration

import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldEqual, shouldNot }
import org.scalatest.tagobjects.Slow

import scala.language.reflectiveCalls

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Repository

@DoNotDiscover class FolderServiceTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {

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

    /**
     * Setup:
     *
     * Folder names that are empty or made only of whitespace: a space, a line break between spaces, and spaces and tabs.
     *
     * Assertions:
     *
     * Each is rejected as invalid.
     */
    intercept[ValidationException] {
      testApp.service.folder.add("")
    }
    intercept[ValidationException] {
      testApp.service.folder.add(" ")
    }
    intercept[ValidationException] {
      testApp.service.folder.add(" \r\n ")
    }
    intercept[ValidationException] {
      testApp.service.folder.add("\t \t   ")
    }
  }

  test("New folders should be free of user-entered space characters") {

    /**
     * Setup:
     *
     * Two folders whose names are padded with spaces, one with a trailing newline.
     *
     * Assertions:
     *
     * Both are stored with the surrounding whitespace trimmed and the inner space kept.
     */
    val folder1: Folder = testApp.service.folder.add(" folder  ")
    folder1.name shouldEqual "folder"

    val folder2: Folder = testApp.service.folder.add(" Folder one \n")
    folder2.name shouldEqual "Folder one"
  }

  test("Deleting a folder should also recycle all children") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. folder1 is deleted.
     *
     * Assertions:
     *
     * folder1 and every folder below it, at all depths, read back as recycled.
     */
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

    /**
     * Setup:
     *
     * A folder ID that does not exist.
     *
     * Assertions:
     *
     * Deleting it fails as not found.
     */
    intercept[NotFoundException] {
      testApp.service.library.deleteFolderById("bogus")
    }
  }

  test("Deleting the root folder should fail") {

    /**
     * Setup:
     *
     * The context repository's root folder.
     *
     * Assertions:
     *
     * Deleting it is refused as an illegal operation.
     */
    intercept[IllegalOperationException] {
      testApp.service.library.deleteFolderById(RequestContext.getRepository.rootFolderId)
    }
  }

  test("Moving a folder to another folder should work") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. folder1_1_1 is moved under folder2.
     *
     * Assertions:
     *
     * The child counts of the source and target parents change by one each: folder2 goes from one child to two, folder1_1 from
     * one to none.
     */
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

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. folder1_1_1 and then folder1_1 are moved to the root.
     *
     * Assertions:
     *
     * The root's child count grows by one with each move, from two to four.
     *
     * Edge cases:
     *
     * A folder moved to the root from two levels down, then its former parent.
     */
    val f = folderHierarchyFixture

    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 2

    testApp.service.folder.move(f.folder1_1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 3

    testApp.service.folder.move(f.folder1_1.persistedId, RequestContext.getRepository.rootFolderId)
    testApp.app.service.folder.getChildren(rootId = RequestContext.getRepository.rootFolderId).length shouldBe 4
  }

  test("Can traverse the folder hierarchy") {

    /**
     * Setup:
     *
     * Three top-level folders: folder1 with a chain of three nested descendants, folder2 with four children added in reverse name
     * order, and folder3 with one child.
     *
     * folder1 folder1_1 folder1_1_1 folder1_1_1_1 folder2 folder2_1 folder2_2 folder2_3 folder2_4 folder3 folder3_1
     *
     * Assertions:
     *
     * Immediate children of the root exclude the root itself, children come back sorted by name whatever order they were added
     * in, a deep folder's ancestors are every folder above it short of the root, and a folder's recursive children are its whole
     * subtree.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    val folder1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1", parentId = folder1_1.id)

    val folder1_1_1_1: Folder = testApp.service.folder.add(name = "folder1_1_1_1", parentId = folder1_1_1.id)

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
    allChildrenOf1.size shouldEqual 3
    val allChildrenOf1Ids = allChildrenOf1.map(_.persistedId)

    (allChildrenOf1Ids should contain).allOf(folder1_1.persistedId, folder1_1_1.persistedId, folder1_1_1_1.persistedId)
  }

  test("Folder counts should be accurate") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1.
     *
     * Assertions:
     *
     * Child counts match the tree: the root has two children, folder1 has two, and of those folder1_1 has one and folder1_2 none.
     */
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

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. A second folder named folder1_1_1 is added under folder2.
     *
     * Assertions:
     *
     * Moving a folder into itself is an illegal operation, and moving it into its own descendant or into a parent that already
     * has a child of the same name is a duplicate.
     *
     * Edge cases:
     *
     * Moving a folder into a folder three levels below it.
     */
    val f = folderHierarchyFixture

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

    /**
     * Setup:
     *
     * Two top-level folders, each with one child whose names differ only by case.
     *
     * folder1 child folder2 CHILD
     *
     * Assertions:
     *
     * Moving one child under the other's parent is a duplicate, since folder names compare case-insensitively.
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

  test("Moving into a folder that does not exist should throw") {

    /**
     * Setup:
     *
     * A top-level folder and a destination folder ID that does not exist.
     *
     * Assertions:
     *
     * The move is rejected as invalid.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    // move into a folder that does not exist
    intercept[ValidationException] {
      testApp.service.folder.move(folder1.persistedId, "bogus-id")
    }
  }

  test("Rename a folder") {

    /**
     * Setup:
     *
     * A folder named "folder", renamed to "newName".
     *
     * Assertions:
     *
     * The folder reads back under its new name.
     */
    val folder1: Folder = testApp.service.folder.add("folder")

    testApp.service.folder.rename(folder1.persistedId, "newName")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "newName"
  }

  test("Folder name casing can be changed") {

    /**
     * Setup:
     *
     * A folder named "folder", renamed to "Folder".
     *
     * Assertions:
     *
     * Renaming a folder to a different casing of its own name is not a duplicate, and the new casing is stored.
     */
    val folder1: Folder = testApp.service.folder.add("folder")

    testApp.service.folder.rename(folder1.persistedId, "Folder")

    val renamedFolder: Folder = testApp.service.folder.getById(folder1.persistedId)
    renamedFolder.name shouldEqual "Folder"
  }

  test("Duplicate folder rename actions should throw") {

    /**
     * Setup:
     *
     * Two sibling folders at the root.
     *
     * Assertions:
     *
     * Renaming one to the other's name is a duplicate.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")
    val folder2: Folder = testApp.service.folder.add("folder2")

    intercept[DuplicateException] {
      testApp.service.folder.rename(folder1.persistedId, folder2.name)
    }
  }

  test("Sibling folder names that differ only by case fold, canonical form or compatibility form are duplicates") {

    /**
     * Setup:
     *
     * Three folders at the root, "Straße", "Café" with a precomposed "é" and "Summer", a fourth, "Other", to rename, and a folder
     * "Parent" holding none of them.
     *
     * Assertions:
     *
     * Adding a sibling, or renaming "Other", to "STRASSE" (the case fold of "ß"), to "Café" with an "e" and a combining accent
     * (the canonical form) or to "Ｓummer" with a full-width "S" (the compatibility form) is a duplicate.
     *
     * Edge cases:
     *
     * Each of those names is free under "Parent", and a folder can be renamed to another form of its own name.
     */
    val other: Folder = testApp.service.folder.add("Other")
    val parent: Folder = testApp.service.folder.add("Parent")
    val clashes = List("Stra\u00DFe" -> "STRASSE", "Caf\u00E9" -> "Cafe\u0301", "Summer" -> "\uFF33ummer")

    for ((name, clash) <- clashes) withClue(s"[$clash]: ") {
      val folder: Folder = testApp.service.folder.add(name)
      intercept[DuplicateException](testApp.service.folder.add(clash))
      intercept[DuplicateException](testApp.service.folder.rename(other.persistedId, clash))

      testApp.service.folder.add(clash, parentId = parent.id)
      testApp.service.folder.rename(folder.persistedId, clash)
    }
  }

  test("A folder name is stored in NFC, and its key does not depend on the default locale") {

    /**
     * Setup:
     *
     * A folder added as "Café" and one renamed to "Crème", both typed with a combining accent; a folder "Istanbul" added under a
     * Turkish default locale.
     *
     * Assertions:
     *
     * The two folders read back as "Café" and "Crème" with precomposed letters. Adding "Istanbul" again under the root locale is
     * a duplicate: the Turkish locale's dotless "ı" never reaches the key.
     *
     * Edge cases:
     *
     * A name with a full-width letter keeps it: the stored name is not folded.
     */
    val added: Folder = testApp.service.folder.add("Cafe\u0301")
    val renamed: Folder = testApp.service.folder.add("before")
    testApp.service.folder.rename(renamed.persistedId, "Cre\u0300me")

    added.name shouldBe "Caf\u00E9"
    testApp.service.folder.getById(added.persistedId).name shouldBe "Caf\u00E9"
    testApp.service.folder.getById(renamed.persistedId).name shouldBe "Cr\u00E8me"

    withDefaultLocale(Locale.forLanguageTag("tr"))(testApp.service.folder.add("Istanbul"))
    withDefaultLocale(Locale.ROOT) {
      intercept[DuplicateException](testApp.service.folder.add("Istanbul"))
    }

    testApp.service.folder.add("\uFF33ummer").name shouldBe "\uFF33ummer"
  }

  test("A folder name longer than 250 characters once in NFC, or whose name key is too long, is refused") {

    /**
     * Setup:
     *
     * A folder "Existing" at the root.
     *
     * Assertions:
     *
     * Adding a folder, or renaming "Existing", to 250 of U+FB2C (250 characters as typed, 750 in NFC) fails validation with the
     * name field's too-long error, and to 100 of U+FDFA (whose name key NFKC spells out to 3300 bytes) with the name key error.
     * "Existing" keeps its name.
     *
     * Edge cases:
     *
     * A name of 250 characters in NFC is accepted, counted in code points as the column counts them: 250 emoji, each two UTF-16
     * units.
     */
    val max = Api.Constraints.MAX_FOLDER_NAME_LENGTH
    val existing: Folder = testApp.service.folder.add("Existing")
    val tooLong = Const.Msg.Err.VALUE_TOO_LONG.format(max)

    refusesName(Api.Field.Folder.NAME, tooLong)(testApp.service.folder.add(lengthenedByNfc(max)))
    refusesName(Api.Field.Folder.NAME, tooLong)(testApp.service.folder.rename(existing.persistedId, lengthenedByNfc(max)))
    refusesName(Api.Field.Folder.NAME, Const.Msg.Err.NAME_KEY_TOO_LONG)(testApp.service.folder.add(longKeyName(100)))
    refusesName(Api.Field.Folder.NAME, Const.Msg.Err.NAME_KEY_TOO_LONG) {
      testApp.service.folder.rename(existing.persistedId, longKeyName(100))
    }
    testApp.service.folder.getById(existing.persistedId).name shouldBe "Existing"

    val emoji = "\uD83D\uDE00" * max
    testApp.service.folder.add(emoji).name shouldBe emoji
  }

  test("Illegal folder rename actions should throw") {

    /**
     * Setup:
     *
     * A top-level folder and the repository's root folder.
     *
     * Assertions:
     *
     * Renaming the root folder is refused as an illegal operation.
     */
    val folder1: Folder = testApp.service.folder.add("folder")

    // rename a system folder
    intercept[IllegalOperationException] {
      testApp.service.folder.rename(RequestContext.getRepository.rootFolderId, folder1.name)
    }
  }

  /** Flattens a tree into a folder id -> recursive asset count lookup, for asserting on `getTree` results */
  private def assetCounts(tree: Folder): Map[String, Int] =
    tree.children.flatMap(assetCounts).toMap + (tree.persistedId -> tree.numOfAssets)

  private def treeCounts: Map[String, Int] = assetCounts(testApp.service.folder.getTree)

  private def rootFolderId: String = RequestContext.getRepository.rootFolderId

  test("Folder tree is assembled from the root with children sorted by name") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. A top-level folder named "deleted" is added and then deleted.
     *
     * Assertions:
     *
     * The tree starts at the repository's root, lists each folder's children sorted by name with their child counts, and leaves
     * the deleted folder out.
     *
     * Edge cases:
     *
     * A recycled folder at the top level.
     */
    val f = folderHierarchyFixture
    val deleted: Folder = testApp.service.folder.add("deleted")
    testApp.service.library.deleteFolderById(deleted.persistedId)

    val tree: Folder = testApp.service.folder.getTree
    tree.persistedId shouldEqual rootFolderId
    tree.children.map(_.name) shouldEqual List(f.folder1.name, f.folder2.name)
    tree.numOfChildren shouldEqual 2

    val folder1 = tree.children.head
    folder1.children.map(_.name) shouldEqual List(f.folder1_1.name, f.folder1_2.name)
    folder1.numOfChildren shouldEqual 2
    folder1.children.last.numOfChildren shouldEqual 0

    assetCounts(tree).keySet shouldNot contain(deleted.persistedId)
  }

  test("Folder tree asset counts roll up recursively") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. An empty top-level folder3 is added, then eight assets: two in
     * folder1_1_1_1, one each in folder1_1_1_2 and folder1_2, three in folder2_1 and one at the root.
     *
     * Assertions:
     *
     * Each folder's count is its own assets plus those of its whole subtree, up to the root counting all eight.
     *
     * Edge cases:
     *
     * Folders that hold no assets of their own but have them below, and a folder with none at all.
     */
    val f = folderHierarchyFixture
    val folder3: Folder = testApp.service.folder.add("folder3")

    testContext.persistAsset(folder = Some(f.folder1_1_1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1_2))
    testContext.persistAsset(folder = Some(f.folder1_2))
    testContext.persistAsset(folder = Some(f.folder2_1))
    testContext.persistAsset(folder = Some(f.folder2_1))
    testContext.persistAsset(folder = Some(f.folder2_1))
    testContext.persistAsset()

    val counts = treeCounts
    counts(f.folder1_1_1_1.persistedId) shouldEqual 2
    counts(f.folder1_1_1_2.persistedId) shouldEqual 1
    counts(f.folder1_1_1.persistedId) shouldEqual 3
    counts(f.folder1_1.persistedId) shouldEqual 3
    counts(f.folder1_2.persistedId) shouldEqual 1
    counts(f.folder1.persistedId) shouldEqual 4
    counts(f.folder2_1.persistedId) shouldEqual 3
    counts(f.folder2.persistedId) shouldEqual 3
    counts(folder3.persistedId) shouldEqual 0
    counts(rootFolderId) shouldEqual 8
  }

  test("Triaged assets do not count toward any folder") {

    /**
     * Setup:
     *
     * Two triaged assets, which have no folder.
     *
     * Assertions:
     *
     * The root's recursive asset count stays at zero.
     */
    testContext.persistAsset(isTriaged = true)
    testContext.persistAsset(isTriaged = true)

    treeCounts(rootFolderId) shouldEqual 0
  }

  test("Recycling and restoring an asset updates folder counts") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. One asset is imported into folder1_1, recycled, then restored.
     *
     * Assertions:
     *
     * The asset counts in folder1_1 and every ancestor up to the root drop to zero while it is recycled and come back once it is
     * restored.
     */
    val f = folderHierarchyFixture
    val asset: Asset = testContext.persistAsset(folder = Some(f.folder1_1))

    treeCounts(f.folder1_1.persistedId) shouldEqual 1

    testApp.service.library.recycleAssets(Set(asset.persistedId))
    val recycledCounts = treeCounts
    recycledCounts(f.folder1_1.persistedId) shouldEqual 0
    recycledCounts(f.folder1.persistedId) shouldEqual 0
    recycledCounts(rootFolderId) shouldEqual 0

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))
    val restoredCounts = treeCounts
    restoredCounts(f.folder1_1.persistedId) shouldEqual 1
    restoredCounts(f.folder1.persistedId) shouldEqual 1
    restoredCounts(rootFolderId) shouldEqual 1
  }

  test("Moving an asset between folders updates folder counts") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. One asset in folder1_2 is moved to folder2_1.
     *
     * Assertions:
     *
     * The source folder and its ancestors lose the asset and the destination and its ancestors gain it, with the root's total
     * unchanged.
     */
    val f = folderHierarchyFixture
    val asset: Asset = testContext.persistAsset(folder = Some(f.folder1_2))

    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), f.folder2_1.persistedId)

    val counts = treeCounts
    counts(f.folder1_2.persistedId) shouldEqual 0
    counts(f.folder1.persistedId) shouldEqual 0
    counts(f.folder2_1.persistedId) shouldEqual 1
    counts(f.folder2.persistedId) shouldEqual 1
    counts(rootFolderId) shouldEqual 1
  }

  test("Sorting a triaged asset into a folder updates folder counts") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. One triaged asset is moved into folder1.
     *
     * Assertions:
     *
     * The asset counts nowhere while triaged, and once moved it counts in folder1 and the root.
     */
    val f = folderHierarchyFixture
    val asset: Asset = testContext.persistAsset(isTriaged = true)

    treeCounts(rootFolderId) shouldEqual 0

    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), f.folder1.persistedId)

    val counts = treeCounts
    counts(f.folder1.persistedId) shouldEqual 1
    counts(rootFolderId) shouldEqual 1
  }

  test("Moving a folder subtree carries its asset counts") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. Assets are added to folder1_1, folder1_1_1, folder1_1_1_1 and
     * folder2, then folder1_1 is moved under folder2.
     *
     * Assertions:
     *
     * The moved subtree keeps its three assets, folder1 drops to zero, folder2 counts its own asset plus the subtree's, and the
     * root total stays at four.
     */
    val f = folderHierarchyFixture
    testContext.persistAsset(folder = Some(f.folder1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1_1))
    testContext.persistAsset(folder = Some(f.folder2))

    testApp.service.folder.move(f.folder1_1.persistedId, f.folder2.persistedId)

    val counts = treeCounts
    counts(f.folder1.persistedId) shouldEqual 0
    counts(f.folder1_1.persistedId) shouldEqual 3
    counts(f.folder2.persistedId) shouldEqual 4
    counts(rootFolderId) shouldEqual 4
  }

  test("Deleting a folder drops its subtree from the counts") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. Assets are added to folder1_1, folder1_1_1, folder1_1_1_2 and
     * folder1_2, then folder1_1 is deleted.
     *
     * Assertions:
     *
     * The deleted folder leaves the tree, and its parent and the root count only the one asset left in folder1_2.
     */
    val f = folderHierarchyFixture
    testContext.persistAsset(folder = Some(f.folder1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1))
    testContext.persistAsset(folder = Some(f.folder1_1_1_2))
    testContext.persistAsset(folder = Some(f.folder1_2))

    testApp.service.library.deleteFolderById(f.folder1_1.persistedId)

    val counts = treeCounts
    counts.keySet shouldNot contain(f.folder1_1.persistedId)
    counts(f.folder1.persistedId) shouldEqual 1
    counts(rootFolderId) shouldEqual 1
  }

  test("Purging recycled assets leaves folder counts unchanged") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. Two assets are imported into folder1_1 and one of them is
     * recycled, then purged.
     *
     * Assertions:
     *
     * Recycling already takes the asset out of the counts, so purging it changes none of them.
     */
    val f = folderHierarchyFixture
    testContext.persistAsset(folder = Some(f.folder1_1))
    val asset: Asset = testContext.persistAsset(folder = Some(f.folder1_1))

    testApp.service.library.recycleAssets(Set(asset.persistedId))
    val countsBefore = treeCounts
    countsBefore(f.folder1_1.persistedId) shouldEqual 1

    testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
    treeCounts shouldEqual countsBefore
  }

  test("Assets not yet through the pipeline do not count toward any folder") {

    /**
     * Setup:
     *
     * The standard folderHierarchyFixture tree: folder1 holding folder1_1 (with folder1_1_1 and its children folder1_1_1_1 and
     * folder1_1_1_2) and folder1_2, and folder2 holding folder2_1. One asset is imported into folder1, then its
     * is_pipeline_processed flag is cleared with raw SQL, written with each engine's own boolean literal.
     *
     * Assertions:
     *
     * An asset the import pipeline has not finished stops counting in its folder and the root.
     */
    val f = folderHierarchyFixture
    val asset: Asset = testContext.persistAsset(folder = Some(f.folder1))
    treeCounts(f.folder1.persistedId) shouldEqual 1

    // Raw SQL bypasses the DAO, so the engine's own boolean literal is required
    val nativeFalse: Any = testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES => false
      case Const.DbEngineName.SQLITE => 0
    }
    testApp.txManager.withTransaction {
      update("UPDATE asset SET is_pipeline_processed = ? WHERE id = ?", nativeFalse, asset.persistedId)
    }

    val counts = treeCounts
    counts(f.folder1.persistedId) shouldEqual 0
    counts(rootFolderId) shouldEqual 0
  }

  test("Folder counts are read from the capture-day index alone", Slow) {

    /**
     * Setup:
     *
     * An asset in a folder seeded into 5,000 copies over 500 folders for the planner, which are rolled back after the plan is
     * read, and the statement that counts the repository's assets by folder.
     *
     * Assertions:
     *
     * The plan reads `asset_search_date_taken`, which carries the folder, and never the table: an index-only scan on PostgreSQL,
     * a covering index on SQLite.
     */
    val folder: Folder = testApp.service.folder.add("Trips")
    val template = testContext.persistAsset(folder = Some(folder))
    val engine = searchDialect
    val counts =
      Db.render(SearchQueries.folderCounts(engine, RequestContext.getRepository.persistedId), engine.dialect)
        .withCompleteQuery(false)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(indexOnlyPlanOf(counts, "asset"))

    withClue(plan) {
      if (isPostgres) plan should include("Index Only Scan using asset_search_date_taken")
      else plan should include("USING COVERING INDEX asset_search_date_taken")
    }
  }

  test("Folder counts are scoped to the context repository") {

    /**
     * Setup:
     *
     * One asset in the first repository, then a second repository with two assets of its own.
     *
     * Assertions:
     *
     * Each repository's root counts only its own assets.
     */
    val firstRepo: Repository = testContext.repository
    testContext.persistAsset()

    val secondRepo: Repository = testContext.persistRepository()
    switchContextRepo(secondRepo)
    testContext.persistAsset(repository = Some(secondRepo))
    testContext.persistAsset(repository = Some(secondRepo))
    treeCounts(secondRepo.rootFolderId) shouldEqual 2

    switchContextRepo(firstRepo)
    treeCounts(firstRepo.rootFolderId) shouldEqual 1
  }

  test("A folder's ancestors come root first") {

    /**
     * Setup:
     *
     * A chain of three folders under a top-level folder.
     *
     * Assertions:
     *
     * The deepest folder's ancestors are listed from the top-level folder down to its parent, without the repository root, which
     * is the order a breadcrumb reads them in.
     */
    val top: Folder = testApp.service.folder.add("top")
    val middle: Folder = testApp.service.folder.add(name = "middle", parentId = top.id)
    val lower: Folder = testApp.service.folder.add(name = "lower", parentId = middle.id)
    val leaf: Folder = testApp.service.folder.add(name = "leaf", parentId = lower.id)

    val ancestors = testApp.service.folder.getAncestors(leaf.persistedId)
    ancestors.map(_.persistedId) shouldEqual List(top.persistedId, middle.persistedId, lower.persistedId)
  }

  test("A folder's path is its ancestors and then the folder itself") {

    /**
     * Setup:
     *
     * A chain of three folders under the repository root.
     *
     * Assertions:
     *
     * The deepest folder's path lists the top-level folder, the middle one and the folder itself, in that order, without the
     * repository root; a top-level folder's path is the folder alone.
     *
     * Edge cases:
     *
     * The root's path is the root alone, and an ID that names no folder has no path.
     */
    val top: Folder = testApp.service.folder.add("top")
    val middle: Folder = testApp.service.folder.add(name = "middle", parentId = top.id)
    val leaf: Folder = testApp.service.folder.add(name = "leaf", parentId = middle.id)
    val folders = testApp.service.folder

    folders.getPath(leaf.persistedId).map(_.persistedId) shouldEqual List(top.persistedId, middle.persistedId, leaf.persistedId)
    folders.getPath(top.persistedId).map(_.persistedId) shouldEqual List(top.persistedId)
    folders.getPath(testContext.repository.rootFolderId).map(_.persistedId) shouldEqual List(testContext.repository.rootFolderId)
    folders.getPath(java.util.UUID.randomUUID.toString) shouldEqual Nil
  }

  test("Ensuring a path reuses the folders that exist, restores the recycled ones and adds the missing ones") {

    /**
     * Setup:
     *
     * A folder Clients with the live folder Wedding under it, which holds the folder Ceremony, recycled with its own subfolder
     * Vows by a delete.
     *
     * Assertions:
     *
     * Ensuring the path Wedding / Ceremony / Portraits under Clients returns a new folder Portraits under Ceremony: Wedding is
     * the folder that was there, Ceremony is the recycled folder, live again, and Portraits is added. Ensuring the same path
     * again returns the same folder and adds nothing. Ensuring no names is the parent itself.
     *
     * Edge cases:
     *
     * A name that differs from an existing folder's only by case and by spaces around it is that folder. Restoring Ceremony
     * restores it alone: its recycled subfolder Vows stays recycled.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val wedding = folders.add("Wedding", clients.id)
    val ceremony = folders.add("Ceremony", wedding.id)
    val vows = folders.add("Vows", ceremony.id)
    testApp.service.library.deleteFolderById(ceremony.persistedId)
    folders.getById(ceremony.persistedId).isRecycled shouldBe true
    val before = folders.getAll.size

    val portraits = folders.ensurePath(clients.persistedId, Seq(" wedding ", "CEREMONY", "Portraits"))

    portraits.name shouldBe "Portraits"
    portraits.parentId shouldBe ceremony.persistedId
    folders.getPath(portraits.persistedId).map(_.persistedId) shouldBe List(
      clients.persistedId,
      wedding.persistedId,
      ceremony.persistedId,
      portraits.persistedId)
    folders.getById(ceremony.persistedId).isRecycled shouldBe false
    folders.getById(vows.persistedId).isRecycled shouldBe true
    folders.getAll.size shouldBe before + 1

    folders.ensurePath(clients.persistedId, Seq("Wedding", "Ceremony", "Portraits")).persistedId shouldBe portraits.persistedId
    folders.getAll.size shouldBe before + 1
    folders.ensurePath(clients.persistedId, Nil).persistedId shouldBe clients.persistedId
  }

  test("Ensuring a path refuses a name the folder rule refuses, and a parent that is no folder of the repository") {

    /**
     * Setup:
     *
     * A folder Clients.
     *
     * Assertions:
     *
     * A path with a name longer than a folder's name may be, or a name of spaces alone, is a `ValidationException`, and nothing
     * of the path is added, not even the valid name before the refused one. A parent that does not exist is not found.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val before = folders.getAll.size

    intercept[ValidationException] {
      folders.ensurePath(clients.persistedId, Seq("Wedding", "x" * (Api.Constraints.MAX_FOLDER_NAME_LENGTH + 1)))
    }
    intercept[ValidationException] {
      folders.ensurePath(clients.persistedId, Seq("Wedding", "  "))
    }
    folders.getAll.size shouldBe before

    intercept[NotFoundException] {
      folders.ensurePath(java.util.UUID.randomUUID.toString, Seq("Wedding"))
    }
  }

  test("Two transactions ensuring one missing folder at once end up with one folder") {

    /**
     * Setup:
     *
     * A folder Clients with no folder under it. Two transactions each ensure the path Wedding / Ceremony under it, the first held
     * open until the second waits for it: for the name's advisory lock on PostgreSQL, for the write connection on SQLite.
     *
     * Assertions:
     *
     * Both return the same folder Ceremony, under one folder Wedding: the second finds the folders the first created. Without the
     * turn-taking, the second, which read before the first committed, would add Wedding itself and fail on the unique index of a
     * folder's name among its siblings.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val ensured = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def ensure(): Unit = ensured.add(folders.ensurePath(clients.persistedId, Seq("Wedding", "Ceremony")).persistedId): Unit

    contend(first = ensure(), second = ensure())

    ensured.size shouldBe 2
    ensured.toArray.distinct.length shouldBe 1
    folders.getChildren(clients.persistedId).map(_.name) shouldBe List("Wedding")
  }

  test("A folder renamed or moved into a name while a path through that name is ensured is the folder the path finds") {

    /**
     * Setup:
     *
     * A folder Clients holding the folder Draft, and a folder Portraits elsewhere. A transaction renames Draft to Party and is
     * held open until a second one, which ensures the path Party under Clients, waits for it: for the name's advisory lock on
     * PostgreSQL, for the write connection on SQLite. Then the same with a move of Portraits into Clients against the path
     * Portraits.
     *
     * Assertions:
     *
     * Each ensured path is the folder that was renamed or moved into the name, and Clients holds those two folders alone. Without
     * the rename and the move taking their turn with the path's creator, the second transaction, which read before the first
     * committed, would add a folder of the name itself and fail on the unique index of a folder's name among its siblings.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val draft = folders.add("Draft", clients.id)
    val elsewhere = folders.add("Elsewhere")
    val portraits = folders.add("Portraits", elsewhere.id)
    val ensured = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def ensure(name: String): Unit = ensured.add(folders.ensurePath(clients.persistedId, Seq(name)).persistedId): Unit

    contend(first = folders.rename(draft.persistedId, "Party"): Unit, second = ensure("Party"))
    contend(first = folders.move(portraits.persistedId, clients.persistedId): Unit, second = ensure("Portraits"))

    ensured.toArray.toList shouldBe List(draft.persistedId, portraits.persistedId)
    folders.getChildren(clients.persistedId).map(_.name) shouldBe List("Party", "Portraits")
  }
}
