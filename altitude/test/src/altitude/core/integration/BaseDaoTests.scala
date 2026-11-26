package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.util.Query

/** The reads and writes of `BaseDao` that every DAO shares, on both engines */
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

  test("A read by more IDs than a statement takes parameters returns the assets among them", Slow) {

    /**
     * Setup:
     *
     * Two assets of the context repository; the first is looked up among 70,000 unknown IDs, more parameters than a PostgreSQL
     * statement takes.
     *
     * Assertions:
     *
     * The read by IDs returns the first asset alone, and so does a query by the same IDs.
     */
    val asset = testContext.insertAsset()
    testContext.insertAsset()
    val ids = amongManyUnknownIds(Set(asset.persistedId))

    testApp.txManager.asReadOnly(testApp.DAO.asset.getByIds(ids)).map(_.persistedId) shouldBe List(asset.persistedId)

    val byQuery = new Query().add(FieldConst.ID -> Query.IN(ids.toSet[Any]))
    testApp.service.asset.queryAll(byQuery).records.map(_.persistedId) shouldBe List(asset.persistedId)
  }

  test("A write by more IDs than a statement takes parameters changes the rows among them", Slow) {

    /**
     * Setup:
     *
     * Two assets of the context repository; the first is renamed by a query of its ID among 70,000 unknown IDs, more parameters
     * than a PostgreSQL statement takes.
     *
     * Assertions:
     *
     * The write changes one row, the first asset's name is the new one, and the second asset keeps its name.
     */
    val asset = testContext.insertAsset()
    val other = testContext.insertAsset()
    val byIds = new Query().add(FieldConst.ID -> Query.IN(amongManyUnknownIds(Set(asset.persistedId)).toSet[Any]))

    testApp.txManager.withTransaction {
      testApp.DAO.asset.updateByQuery(byIds, Map(FieldConst.Asset.FILENAME -> "renamed.jpg"))
    } shouldBe 1

    testApp.service.asset.getById(asset.persistedId).fileName shouldBe "renamed.jpg"
    testApp.service.asset.getById(other.persistedId).fileName shouldBe other.fileName
  }
}
