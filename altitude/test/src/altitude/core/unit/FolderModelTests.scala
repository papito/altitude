package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder
import altitude.core.util.Util
import org.scalatest.matchers.should.Matchers.shouldEqual

@DoNotDiscover class FolderModelTests extends funsuite.AnyFunSuite {

  test("Folder uniqueness") {
    val folder1 = new Folder(
      parentId = BaseDao.genId, name = Util.randomStr(30))
    val folder2 = new Folder(
      parentId = folder1.parentId, name = folder1.name)
    val folder3 = new Folder(
      parentId = BaseDao.genId, name = Util.randomStr(30))
    val folder4 = new Folder(
      id = Option(BaseDao.genId), parentId = Util.randomStr(30), name = Util.randomStr(30))

    Set(folder1, folder2, folder3, folder4).size shouldEqual 3
  }
}
