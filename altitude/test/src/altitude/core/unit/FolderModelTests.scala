package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldEqual

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder
import altitude.core.util.Util

@DoNotDiscover class FolderModelTests extends funsuite.AnyFunSuite {

  test("Folder uniqueness") {

    /**
     * Setup:
     *
     * Four unsaved Folders: two share a parent ID and name, and two more each have a random parent and name, one of them an ID.
     *
     * Assertions:
     *
     * Folders are equal by ID, parent ID and name, so the two alike collapse into one element of a set of three.
     */
    val folder1 = new Folder(parentId = BaseDao.genId, name = Util.randomStr(30))
    val folder2 = new Folder(parentId = folder1.parentId, name = folder1.name)
    val folder3 = new Folder(parentId = BaseDao.genId, name = Util.randomStr(30))
    val folder4 = new Folder(id = Option(BaseDao.genId), parentId = Util.randomStr(30), name = Util.randomStr(30))

    Set(folder1, folder2, folder3, folder4).size shouldEqual 3
  }

  test("Equal Folders hash alike, so a hashed set of any size keeps one of them") {

    /**
     * Setup:
     *
     * A saved Folder, a copy of it that differs only in name case and counts, and ten unrelated Folders.
     *
     * Assertions:
     *
     * The copy equals the Folder and has its hash code, so a set of all twelve keeps eleven.
     *
     * Edge cases:
     *
     * Past four elements a Scala set is a hashed set, which looks an element up by its hash code before comparing; the test above
     * stays under that size.
     */
    val folder = new Folder(id = Option(BaseDao.genId), parentId = BaseDao.genId, name = "Holidays")
    val copy = folder.copy(name = "HOLIDAYS", numOfChildren = 1, numOfAssets = 3)
    val others = (1 to 10).map(_ => new Folder(parentId = BaseDao.genId, name = Util.randomStr(30)))

    copy shouldEqual folder
    copy.hashCode shouldEqual folder.hashCode
    (others :+ folder :+ copy).toSet.size shouldEqual 11
  }
}
