package altitude.core.integration

import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Signaler
import org.scalatest.concurrent.TimeLimits.failAfter
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldEqual, shouldNot }
import org.scalatest.tagobjects.Slow
import org.scalatest.time.Seconds
import org.scalatest.time.Span

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
import altitude.core.util.NameKey

@DoNotDiscover class FolderServiceTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {

  /** The standard folder tree, drawn below */
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
     * Folder names that are empty or only whitespace, line breaks and tabs included.
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
     * Both are stored trimmed, the inner space kept.
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
     * The standard folder tree, with folder1 deleted.
     *
     * Assertions:
     *
     * folder1 and every folder below it read back as recycled.
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
     * The standard folder tree, with folder1_1_1 moved under folder2.
     *
     * Assertions:
     *
     * folder2 goes from one child to two, folder1_1 from one to none.
     */
    val f = folderHierarchyFixture

    childrenOf(f.folder2.persistedId).length shouldBe 1
    childrenOf(f.folder1_1.persistedId).length shouldBe 1

    testApp.service.folder.move(f.folder1_1_1.persistedId, f.folder2.persistedId)
    childrenOf(f.folder2.persistedId).length shouldBe 2
    childrenOf(f.folder1_1.persistedId).length shouldBe 0
  }

  test("Moving a folder to repository root should work") {

    /**
     * Setup:
     *
     * The standard folder tree, with folder1_1_1 and then its former parent folder1_1 moved to the root.
     *
     * Assertions:
     *
     * The root's child count grows by one with each move, from two to four.
     */
    val f = folderHierarchyFixture

    childrenOf(RequestContext.getRepository.rootFolderId).length shouldBe 2

    testApp.service.folder.move(f.folder1_1_1.persistedId, RequestContext.getRepository.rootFolderId)
    childrenOf(RequestContext.getRepository.rootFolderId).length shouldBe 3

    testApp.service.folder.move(f.folder1_1.persistedId, RequestContext.getRepository.rootFolderId)
    childrenOf(RequestContext.getRepository.rootFolderId).length shouldBe 4
  }

  test("Can traverse the folder hierarchy") {

    /**
     * Setup:
     *
     * Three top-level folders: folder1 with a chain of three nested descendants, folder2 with four children added in reverse name
     * order, and folder3 with one child.
     *
     * Assertions:
     *
     * The root's children exclude the root itself, children come sorted by name, a deep folder's ancestors are every folder above
     * it short of the root, and a folder's recursive children are its whole subtree.
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

    val childrenOfRoot = childrenOf(RequestContext.getRepository.rootFolderId)
    childrenOfRoot.size shouldEqual 3
    childrenOfRoot.map(_.persistedId) shouldNot contain(RequestContext.getRepository.rootFolderId)

    val childrenOf1: List[Folder] = childrenOf(folder1.persistedId)
    childrenOf1.size shouldEqual 1
    childrenOf1.head.name shouldEqual folder1_1.name

    val childrenOf2: List[Folder] = childrenOf(folder2.persistedId)
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
     * The standard folder tree.
     *
     * Assertions:
     *
     * Child counts match the tree: the root has two children, folder1 two, folder1_1 one and folder1_2 none.
     */
    val f = folderHierarchyFixture

    val rootFolderChildren = childrenOf(RequestContext.getRepository.rootFolderId)
    rootFolderChildren.size shouldEqual 2

    val rootFolder: Folder = testApp.service.folder.getById(RequestContext.getRepository.rootFolderId)
    rootFolder.numOfChildren shouldEqual 2

    val folder1Children = childrenOf(f.folder1.persistedId)
    folder1Children.size shouldEqual 2
    folder1Children.head.numOfChildren shouldEqual 1
    folder1Children.last.numOfChildren shouldEqual 0
  }

  test("Illegal folder move actions should throw") {

    /**
     * Setup:
     *
     * The standard folder tree, plus a second folder named folder1_1_1 under folder2.
     *
     * Assertions:
     *
     * Moving a folder into itself or into its own descendant is an illegal operation; moving it into a parent that already has a
     * child of the same name is a duplicate.
     *
     * Edge cases:
     *
     * The descendant is two levels below the moved folder.
     */
    val f = folderHierarchyFixture

    testApp.service.folder.add(name = "folder1_1_1", parentId = f.folder2.id)

    intercept[IllegalOperationException] {
      testApp.service.folder.move(f.folder1.persistedId, f.folder1.persistedId)
    }

    intercept[IllegalOperationException] {
      testApp.service.folder.move(f.folder1.persistedId, f.folder1_1_1.persistedId)
    }

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
     * Assertions:
     *
     * Moving one child under the other's parent is a duplicate, since folder names compare case-insensitively.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "child", parentId = folder1.id)

    val folder2: Folder = testApp.service.folder.add("folder2")

    testApp.service.folder.add(name = "CHILD", parentId = folder2.id)

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
     * Recasing its own name is not a duplicate, and the new casing is stored.
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
     * Folders "Straße", "Café" (precomposed) and "Summer" at the root, "Other" to rename, and an empty folder "Parent".
     *
     * Assertions:
     *
     * Adding a sibling, or renaming "Other", to "STRASSE" (case fold), to "Café" with a combining accent (canonical form) or to
     * "Ｓummer" with a full-width "S" (compatibility form) is a duplicate.
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
     * A folder added as "Café" and one renamed to "Crème", both with a combining accent; "Istanbul" added under a Turkish default
     * locale.
     *
     * Assertions:
     *
     * Both names read back precomposed. Adding "Istanbul" again under the root locale is a duplicate: the Turkish dotless "ı"
     * never reaches the key.
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

  test("A folder name with a control character, longer than 250 characters once in NFC, or of too long a name key, is refused") {

    /**
     * Setup:
     *
     * A folder "Existing" at the root.
     *
     * Assertions:
     *
     * Adding a folder, or renaming "Existing", to 250 of U+FB2C (750 code points in NFC) fails with the too-long error, and to
     * 100 of U+FDFA (a 3300-byte name key) with the name key error, and to a name holding a NUL with the control character error,
     * which PostgreSQL would otherwise refuse as a raw `SQLException`. "Existing" keeps its name.
     *
     * Edge cases:
     *
     * 250 emoji, each two UTF-16 units, are accepted: the length counts code points, as the column does.
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
    val withNul = "Before\u0000after"
    refusesName(Api.Field.Folder.NAME, Const.Msg.Err.CONTROL_CHARACTERS)(testApp.service.folder.add(withNul))
    refusesName(Api.Field.Folder.NAME, Const.Msg.Err.CONTROL_CHARACTERS) {
      testApp.service.folder.rename(existing.persistedId, withNul)
    }
    testApp.service.folder.getById(existing.persistedId).name shouldBe "Existing"

    val emoji = "\uD83D\uDE00" * max
    testApp.service.folder.add(emoji).name shouldBe emoji
  }

  test("Illegal folder rename actions should throw") {

    /**
     * Setup:
     *
     * The repository's root folder.
     *
     * Assertions:
     *
     * Renaming it is an illegal operation.
     */
    val folder1: Folder = testApp.service.folder.add("folder")

    intercept[IllegalOperationException] {
      testApp.service.folder.rename(RequestContext.getRepository.rootFolderId, folder1.name)
    }
  }

  /** A tree's recursive asset counts by folder ID */
  private def assetCounts(tree: Folder): Map[String, Int] =
    tree.children.flatMap(assetCounts).toMap + (tree.persistedId -> tree.numOfAssets)

  private def treeCounts: Map[String, Int] = assetCounts(testApp.service.folder.getTree)

  private def rootFolderId: String = RequestContext.getRepository.rootFolderId

  test("Folder tree is assembled from the root with children sorted by name") {

    /**
     * Setup:
     *
     * The standard folder tree, plus a top-level folder that is deleted.
     *
     * Assertions:
     *
     * The tree starts at the root, lists children sorted by name with their child counts, and leaves the deleted folder out.
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
     * The standard folder tree plus an empty folder3, with eight assets spread over leaf folders and the root.
     *
     * Assertions:
     *
     * Each folder counts its own assets plus its whole subtree's, up to the root counting all eight.
     *
     * Edge cases:
     *
     * Folders with assets only below them, and a folder with none at all.
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
     * The standard folder tree, with one asset in folder1_1 recycled, then restored.
     *
     * Assertions:
     *
     * folder1_1 and its ancestors count zero while the asset is recycled and one once it is restored.
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
     * The standard folder tree, with an asset moved from folder1_2 to folder2_1.
     *
     * Assertions:
     *
     * The source branch loses the asset, the destination branch gains it, and the root's total is unchanged.
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
     * The standard folder tree, with a triaged asset moved into folder1.
     *
     * Assertions:
     *
     * The asset counts nowhere while triaged, then in folder1 and the root.
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
     * The standard folder tree, with three assets in folder1_1's subtree and one in folder2; folder1_1 is moved under folder2.
     *
     * Assertions:
     *
     * The moved subtree keeps its three assets, folder1 drops to zero, folder2 counts four, and so does the root.
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
     * The standard folder tree, with three assets in folder1_1's subtree and one in folder1_2; folder1_1 is deleted.
     *
     * Assertions:
     *
     * folder1_1 leaves the tree, and folder1 and the root count only the asset in folder1_2.
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
     * The standard folder tree, with two assets in folder1_1, one recycled, then purged.
     *
     * Assertions:
     *
     * Recycling already took the asset out of the counts, so purging changes none.
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
     * The standard folder tree, with an asset in folder1 whose is_pipeline_processed flag is then cleared.
     *
     * Assertions:
     *
     * An asset the import pipeline has not finished counts neither in its folder nor in the root.
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
     * The folder-count statement explained over an asset seeded into 5,000 copies across 500 folders, rolled back afterwards.
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
     * The deepest folder's ancestors run from the top-level folder down to its parent, without the repository root.
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
     * The deepest folder's path runs from the top-level folder to the folder itself, without the root; a top-level folder's path
     * is the folder alone.
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
     * Clients / Wedding / Ceremony / Vows, with Ceremony and Vows deleted.
     *
     * Assertions:
     *
     * Ensuring Wedding / Ceremony / Portraits under Clients reuses Wedding, restores Ceremony and adds Portraits. Ensuring it
     * again adds nothing, and ensuring no names gives the parent itself.
     *
     * Edge cases:
     *
     * A name differing only by case and surrounding spaces is the existing folder. Vows stays recycled.
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
     * A path with a name too long or of spaces alone fails validation, and nothing of it is added, not even the valid name
     * before. A parent that does not exist is not found.
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
     * An empty folder Clients. Two transactions ensure Wedding / Ceremony under it, the first held open until the second waits on
     * the name's lock.
     *
     * Assertions:
     *
     * Both return the same Ceremony under one Wedding: the second finds what the first created.
     *
     * Edge cases:
     *
     * Without the turn-taking the second would add Wedding itself and fail on the sibling-name unique index.
     */
    val folders = testApp.service.folder
    val clients = folders.add("Clients")
    val ensured = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    def ensure(): Unit = ensured.add(folders.ensurePath(clients.persistedId, Seq("Wedding", "Ceremony")).persistedId): Unit

    contend(first = ensure(), second = ensure())

    ensured.size shouldBe 2
    ensured.toArray.distinct.length shouldBe 1
    childrenOf(clients.persistedId).map(_.name) shouldBe List("Wedding")
  }

  test("A folder renamed or moved into a name while a path through that name is ensured is the folder the path finds") {

    /**
     * Setup:
     *
     * Clients holding Draft, and Portraits elsewhere. A rename of Draft to Party is held open until an ensure of Party under
     * Clients waits on the name's lock; then the same with a move of Portraits into Clients against an ensure of Portraits.
     *
     * Assertions:
     *
     * Each ensured path is the renamed or moved folder, and Clients holds those two alone.
     *
     * Edge cases:
     *
     * Without the rename and move taking their turn on the name, the ensure would add a folder itself and fail on the
     * sibling-name unique index.
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
    childrenOf(clients.persistedId).map(_.name) shouldBe List("Party", "Portraits")
  }

  test("Two opposite moves of sibling folders at once leave no cycle: the second is refused") {

    /**
     * Setup:
     *
     * Two sibling folders, Alpha and Beta. A move of Alpha into Beta is held open until a move of Beta into Alpha waits on the
     * folder tree's lock.
     *
     * Assertions:
     *
     * The second move is refused as a move into the folder's own descendant, and the tree is Beta holding Alpha.
     *
     * Edge cases:
     *
     * Without the turn-taking on PostgreSQL, each move's ancestor check reads the tree before the other's write, both commit, and
     * the two folders are each other's parent.
     */
    val folders = testApp.service.folder
    val alpha = folders.add("Alpha")
    val beta = folders.add("Beta")

    contend(
      first = folders.move(alpha.persistedId, beta.persistedId): Unit,
      second = intercept[IllegalOperationException](folders.move(beta.persistedId, alpha.persistedId)): Unit
    )

    folders.getById(alpha.persistedId).parentId shouldBe beta.persistedId
    folders.getById(beta.persistedId).parentId shouldBe testContext.repository.rootFolderId
  }

  test("The recursive folder reads end on a cycle stored in the tree") {

    /**
     * Setup:
     *
     * Alpha holding Beta, with Alpha's parent then set to Beta in the database, so the two are each other's parent.
     *
     * Assertions:
     *
     * Alpha's ancestors are Beta, its path Beta and then Alpha, and its folders all the way down Beta and Alpha, each once: every
     * read stops where the cycle closes.
     */
    val folders = testApp.service.folder
    val alpha = folders.add("Alpha")
    val beta = folders.add("Beta", alpha.id)
    testApp.txManager.withTransaction {
      update("UPDATE folder SET parent_id = ? WHERE id = ?", beta.persistedId, alpha.persistedId)
    }

    ending(folders.getAncestors(alpha.persistedId)).map(_.persistedId) shouldBe List(beta.persistedId)
    ending(folders.getPath(alpha.persistedId)).map(_.persistedId) shouldBe List(beta.persistedId, alpha.persistedId)
    ending(folders.getChildrenRecursive(alpha.persistedId)).map(_.persistedId).sorted shouldBe
      List(alpha.persistedId, beta.persistedId).sorted
  }

  test("A folder is neither added nor moved under a folder in the Trash") {

    /**
     * Setup:
     *
     * A deleted folder Trips, and a live folder Beach at the top level.
     *
     * Assertions:
     *
     * Adding a folder under Trips and moving Beach into it are both refused as illegal operations, and Beach stays where it was.
     */
    val folders = testApp.service.folder
    val trips = folders.add("Trips")
    val beach = folders.add("Beach")
    testApp.service.library.deleteFolderById(trips.persistedId)

    intercept[IllegalOperationException](folders.add("Summer", trips.id))
    intercept[IllegalOperationException](folders.move(beach.persistedId, trips.persistedId))

    folders.getById(beach.persistedId).parentId shouldBe testContext.repository.rootFolderId
    folders.getAll.map(_.name) shouldNot contain("Summer")
  }

  test("The root's folders all the way down are every other folder, each once") {

    /**
     * Setup:
     *
     * The standard folder tree.
     *
     * Assertions:
     *
     * The root's recursive children are the tree's eight folders, without the root, each listed once.
     */
    val f = folderHierarchyFixture

    val all = testApp.service.folder.getChildrenRecursive(testContext.repository.rootFolderId).map(_.persistedId)

    all.sorted shouldBe List(
      f.folder1,
      f.folder1_1,
      f.folder1_1_1,
      f.folder1_1_1_1,
      f.folder1_1_1_2,
      f.folder1_2,
      f.folder2,
      f.folder2_1).map(_.persistedId).sorted
  }

  test("A folder added under the name of a deleted sibling is that sibling, restored") {

    /**
     * Setup:
     *
     * A folder Trips holding Beach, deleted, then a folder TRIPS added at the same level.
     *
     * Assertions:
     *
     * The add is Trips restored under the name as typed, not a duplicate: one live folder TRIPS at that level, with Trips' ID.
     *
     * Edge cases:
     *
     * Beach stays in the Trash: the restore is of the folder alone, as an import's is. Adding the name again is a duplicate.
     */
    val folders = testApp.service.folder
    val trips = folders.add("Trips")
    val beach = folders.add("Beach", trips.id)
    testApp.service.library.deleteFolderById(trips.persistedId)

    val again = folders.add("TRIPS")

    again.persistedId shouldBe trips.persistedId
    again.isRecycled shouldBe false
    folders.getById(trips.persistedId).isRecycled shouldBe false
    folders.getById(trips.persistedId).name shouldBe "TRIPS"
    folders.getById(beach.persistedId).isRecycled shouldBe true
    folders.getTree.children.map(_.name) shouldBe List("TRIPS")

    intercept[DuplicateException](folders.add("Trips"))
  }

  test("A folder renamed or moved to the name of a deleted sibling takes it, and the sibling keeps its name in the Trash") {

    /**
     * Setup:
     *
     * Two top-level folders, Trips and Summer, both deleted; a live folder Holiday beside them, and Elsewhere holding a folder
     * SUMMER.
     *
     * Assertions:
     *
     * Holiday renamed to "trips" and SUMMER moved to the top level both succeed, and the top level shows the two folders with
     * their own IDs. The deleted Trips and Summer stay in the Trash under their names.
     *
     * Edge cases:
     *
     * Renaming Elsewhere to "Trips" afterwards is a duplicate: the live folder holds the name.
     */
    val folders = testApp.service.folder
    val trips = folders.add("Trips")
    val summer = folders.add("Summer")
    val holiday = folders.add("Holiday")
    val elsewhere = folders.add("Elsewhere")
    val summerElsewhere = folders.add("SUMMER", elsewhere.id)
    testApp.service.library.deleteFolderById(trips.persistedId)
    testApp.service.library.deleteFolderById(summer.persistedId)

    folders.rename(holiday.persistedId, "trips")
    folders.move(summerElsewhere.persistedId, testContext.repository.rootFolderId)

    childrenOf(testContext.repository.rootFolderId).map(f => (f.persistedId, f.name)) shouldBe List(
      elsewhere.persistedId -> "Elsewhere",
      summerElsewhere.persistedId -> "SUMMER",
      holiday.persistedId -> "trips")
    List(trips -> "Trips", summer -> "Summer").foreach {
      case (deleted, name) =>
        val stored = folders.getById(deleted.persistedId)
        stored.isRecycled shouldBe true
        stored.name shouldBe name
    }

    intercept[DuplicateException](folders.rename(elsewhere.persistedId, "Trips"))
  }

  test("A folder that gave its name away comes back from the Trash under it when free, and numbered when not") {

    /**
     * Setup:
     *
     * A top-level folder Trips holding an asset, deleted, and a folder Holiday holding an asset, renamed to "Trips", which moves
     * the deleted Trips aside.
     *
     * Assertions:
     *
     * With the live folder renamed back to Holiday, restoring the first asset brings the first Trips back under its name. With
     * that Trips deleted again, Holiday renamed to "Trips" and deleted too, restoring the first asset brings the first Trips back
     * under its name once more, moving the deleted holder of the name aside. Restoring the second asset then brings Holiday's
     * folder back as "Trips (2)" beside the live Trips, with its asset in it. After each restore the restored folder holds its
     * name: adding the name again is a duplicate.
     */
    val folders = testApp.service.folder
    val library = testApp.service.library
    val root = testContext.repository.rootFolderId

    val first = folders.add("Trips")
    val firstAsset = testContext.persistAsset(folder = Some(first))
    library.deleteFolderById(first.persistedId)
    val second = folders.add("Holiday")
    val secondAsset = testContext.persistAsset(folder = Some(second))
    folders.rename(second.persistedId, "Trips")

    // The name free: the first Trips gets it back
    folders.rename(second.persistedId, "Holiday")
    library.restoreRecycledAssets(Set(firstAsset.persistedId))
    folders.getById(first.persistedId).name shouldBe "Trips"
    folders.getById(first.persistedId).isRecycled shouldBe false
    intercept[DuplicateException](folders.add("trips"))

    // The name held by a folder in the Trash: the restored folder takes it, the deleted holder moves aside
    library.deleteFolderById(first.persistedId)
    folders.rename(second.persistedId, "Trips")
    library.deleteFolderById(second.persistedId)
    library.restoreRecycledAssets(Set(firstAsset.persistedId))
    folders.getById(first.persistedId).name shouldBe "Trips"
    intercept[DuplicateException](folders.add("trips"))

    // The name held by a live folder: the restored folder comes back numbered
    library.restoreRecycledAssets(Set(secondAsset.persistedId))
    val restored = folders.getById(second.persistedId)
    restored.name shouldBe "Trips (2)"
    restored.isRecycled shouldBe false
    childrenOf(root).map(f => (f.persistedId, f.name)) shouldBe List(
      first.persistedId -> "Trips",
      second.persistedId -> "Trips (2)")
    testApp.service.asset.getById(secondAsset.persistedId).folderId shouldBe second.persistedId
    intercept[DuplicateException](folders.add("TRIPS (2)"))
  }

  test("Two restores at once of assets in a folder that gave its name away number it once") {

    /**
     * Setup:
     *
     * A top-level folder Trips holding two assets, deleted, and a folder Holiday renamed to "Trips", taking the name. A restore
     * of the first asset is held open until a restore of the second waits on it.
     *
     * Assertions:
     *
     * The deleted Trips comes back once, as "Trips (2)", holding both assets.
     *
     * Edge cases:
     *
     * Without the second restore waiting its turn and reading the folder again, it would number the folder the first had just
     * numbered once more, as "Trips (3)".
     */
    val folders = testApp.service.folder
    val library = testApp.service.library
    val trips = folders.add("Trips")
    val assets = List.fill(2)(testContext.persistAsset(folder = Some(trips)))
    library.deleteFolderById(trips.persistedId)
    val holiday = folders.add("Holiday")
    folders.rename(holiday.persistedId, "Trips")

    contend(
      first = library.restoreRecycledAssets(Set(assets.head.persistedId)): Unit,
      second = library.restoreRecycledAssets(Set(assets.last.persistedId)): Unit)

    childrenOf(testContext.repository.rootFolderId).map(f => (f.persistedId, f.name)) shouldBe List(
      holiday.persistedId -> "Trips",
      trips.persistedId -> "Trips (2)")
    assets.foreach(asset => testApp.service.asset.getById(asset.persistedId).isRecycled shouldBe false)
  }

  test("A restore under a folder deleted at the same time brings that folder back too") {

    /**
     * Setup:
     *
     * A top-level folder Trips holding a folder Beach, deleted alone with its asset. A delete of Trips is held open until a
     * restore of the asset waits on it.
     *
     * Assertions:
     *
     * The restore sees Trips in the Trash and brings it back with Beach and the asset, so no live folder is left under a folder
     * in the Trash.
     *
     * Edge cases:
     *
     * Without the restore taking the tree's turn, on PostgreSQL it reads Trips live before the delete commits, and Beach and the
     * asset come back under a Trips in the Trash.
     */
    val folders = testApp.service.folder
    val library = testApp.service.library
    val trips = folders.add("Trips")
    val beach = folders.add("Beach", trips.id)
    val asset = testContext.persistAsset(folder = Some(beach))
    library.deleteFolderById(beach.persistedId)

    contend(
      first = library.deleteFolderById(trips.persistedId),
      second = library.restoreRecycledAssets(Set(asset.persistedId)): Unit)

    folders.getById(trips.persistedId).isRecycled shouldBe false
    folders.getById(beach.persistedId).isRecycled shouldBe false
    testApp.service.asset.getById(asset.persistedId).isRecycled shouldBe false
  }

  test("A folder in the Trash is neither renamed nor moved") {

    /**
     * Setup:
     *
     * A deleted folder Trips, as a dialog opened before the delete still offers it, and a folder Summer deleted beside it.
     *
     * Assertions:
     *
     * Renaming Trips to "Summer" and moving it into a live folder are both refused as illegal operations, and Summer keeps its
     * name in the Trash: adding a folder Summer restores it.
     */
    val folders = testApp.service.folder
    val trips = folders.add("Trips")
    val summer = folders.add("Summer")
    val beach = folders.add("Beach")
    testApp.service.library.deleteFolderById(trips.persistedId)
    testApp.service.library.deleteFolderById(summer.persistedId)

    intercept[IllegalOperationException](folders.rename(trips.persistedId, "Summer"))
    intercept[IllegalOperationException](folders.move(trips.persistedId, beach.persistedId))

    folders.getById(trips.persistedId).parentId shouldBe testContext.repository.rootFolderId
    folders.add("Summer").persistedId shouldBe summer.persistedId
  }

  test("A folder added or moved under a folder deleted at the same time is refused") {

    /**
     * Setup:
     *
     * Folders Trips and Beach at the top level. Twice, a delete of Trips is held open until an add of Summer under it, then a
     * move of Beach into it, waits on it; Trips is restored between the two.
     *
     * Assertions:
     *
     * Both the add and the move are refused as illegal operations, and nothing is left under Trips.
     *
     * Edge cases:
     *
     * Without the add and the move taking the tree's turn, on PostgreSQL each reads Trips live before the delete commits, and
     * Summer or Beach is left live under a folder in the Trash.
     */
    val folders = testApp.service.folder
    val library = testApp.service.library
    val trips = folders.add("Trips")
    val beach = folders.add("Beach")

    contend(
      first = library.deleteFolderById(trips.persistedId),
      second = intercept[IllegalOperationException](folders.add("Summer", trips.id)): Unit)
    folders.add("Trips").persistedId shouldBe trips.persistedId
    contend(
      first = library.deleteFolderById(trips.persistedId),
      second = intercept[IllegalOperationException](folders.move(beach.persistedId, trips.persistedId)): Unit
    )

    folders.getAll.filter(_.parentId == trips.persistedId) shouldBe Nil
  }

  test("A folder that gave its name away comes back numbered even when its name's key is near its limit") {

    /**
     * Setup:
     *
     * A top-level folder named with 60 characters whose keys are 33 bytes each and 18 letters, a key of 1,998 bytes, holding an
     * asset, deleted; a live folder renamed to the same name then.
     *
     * Assertions:
     *
     * Restoring the asset brings the folder back numbered, its name shortened by as many letters as the number's key needs: "
     * (2)" would take the key past `NameKey.MAX_KEY_BYTES`, so two letters go.
     */
    val folders = testApp.service.folder
    val name = longKeyName(60) + "x" * 18
    NameKey.of(name).getBytes(java.nio.charset.StandardCharsets.UTF_8).length shouldBe 1998
    val trips = folders.add(name)
    val asset = testContext.persistAsset(folder = Some(trips))
    testApp.service.library.deleteFolderById(trips.persistedId)
    folders.rename(folders.add("Holiday").persistedId, name)

    testApp.service.library.restoreRecycledAssets(Set(asset.persistedId))

    folders.getById(trips.persistedId).name shouldBe longKeyName(60) + "x" * 16 + " (2)"
  }

  /** The live children of the folder, sorted by name, as the folder tree has them; none for a folder not in it */
  private def childrenOf(folderId: String): List[Folder] = {
    def find(node: Folder): Option[Folder] =
      if (node.persistedId == folderId) Some(node) else node.children.view.flatMap(find).headOption
    find(testApp.service.folder.getTree).map(_.children).getOrElse(Nil)
  }

  /**
   * Runs `read` in a read transaction of its own, cancelling its statement after ten seconds, so a recursion that never ends
   * fails the test instead of hanging the suite
   */
  private def ending[T](read: => T): T =
    testApp.txManager.asReadOnly {
      val conn = RequestContext.getConn
      val cancel: Signaler = (_: Thread) =>
        testApp.dataSourceType match {
          case Const.DbEngineName.POSTGRES => conn.unwrap(classOf[org.postgresql.core.BaseConnection]).cancelQuery()
          case _ => conn.unwrap(classOf[org.sqlite.SQLiteConnection]).getDatabase.interrupt()
        }
      failAfter(Span(10, Seconds))(read)(using cancel)
    }
}
