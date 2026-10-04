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
}
