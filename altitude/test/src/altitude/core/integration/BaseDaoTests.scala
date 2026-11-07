package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.RequestContext

/** The hand-written SQL reads of `BaseDao` that every DAO shares, on both engines */
@DoNotDiscover class BaseDaoTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("The streaming read keeps what the mapper returns and counts every row it scanned") {

    /**
     * Setup:
     *
     * Five assets of the context repository, and a statement selecting their IDs in ID order. The mapper keeps every other row,
     * counting from the second.
     *
     * Assertions:
     *
     * The read returns exactly the odd-indexed IDs of the full read of the same statement, in its order, and reports all five
     * rows as scanned; a mapper that keeps nothing returns no rows and still reports all five.
     */
    (1 to 5).foreach(_ => testContext.persistAsset())

    val sql = "SELECT id FROM asset WHERE repository_id = ? ORDER BY id"
    val values = List(RequestContext.getRepository.persistedId)

    val all = testApp.txManager.asReadOnly {
      testApp.DAO.asset.manyBySqlQuery(sql, values).map(_("id").toString)
    }
    all should have size 5

    var seen = 0
    val everyOther = testApp.txManager.asReadOnly {
      testApp.DAO.asset.collectBySqlQuery(sql, values) {
        rec =>
          seen += 1
          Option.when(seen % 2 == 0)(rec("id").toString)
      }
    }

    everyOther.kept shouldBe all.zipWithIndex.collect { case (id, index) if index % 2 == 1 => id }
    everyOther.scanned shouldBe 5

    val none = testApp.txManager.asReadOnly {
      testApp.DAO.asset.collectBySqlQuery(sql, values)(_ => None)
    }
    none.kept shouldBe empty
    none.scanned shouldBe 5
  }
}
