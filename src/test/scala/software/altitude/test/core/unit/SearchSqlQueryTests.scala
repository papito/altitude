package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.RequestContext
import software.altitude.core.Util
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.dao.postgres.querybuilder.{AssetSearchQueryBuilder => PostgresAssetSearchQueryBuilder}
import software.altitude.core.dao.sqlite.querybuilder.{AssetSearchQueryBuilder => SqliteAssetSearchQueryBuilder}
import software.altitude.core.models.FieldType
import software.altitude.core.models.MetadataField
import software.altitude.core.models.Repository
import software.altitude.core.util._
import software.altitude.core.{Const => C}
import software.altitude.test.core.TestFocus

@DoNotDiscover class SearchSqlQueryTests extends funsuite.AnyFunSuite with TestFocus {
  private val repo = new Repository(
    id = Some("1"),
    name = "repo name",
    ownerAccountId = Util.randomStr(),
    rootFolderId = "1",
    triageFolderId = "2",
    fileStoreConfig = Map(),
    fileStoreType = C.FileStoreType.FS)

  RequestContext.repository.value = Some(repo)
  RequestContext.account.value = None

  test("Basic WHERE SQL asset query is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery()
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM asset WHERE asset.repository_id = ? AND is_recycled = ?"
    sqlQuery.bindValues.size shouldBe 2
  }

  test("WHERE asset query can be narrowed down by folders") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(folderIds = Set("1", "2", "3"))
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM asset WHERE asset.repository_id = ? AND is_recycled = ? AND folder_id IN (?, ?, ?)"
    sqlQuery.bindValues.size shouldBe 5
  }

  test("Sqlite text search SQL query is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(text = Some("my text"))
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND body MATCH ? AND is_recycled = ? AND search_document.asset_id = asset.id"
    sqlQuery.bindValues.size shouldBe 4
  }

  test("Postgres text search SQL query is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(text = Some("my text"))
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND search_document.tsv @@ to_tsquery(?) AND is_recycled = ? AND search_document.asset_id = asset.id"
    sqlQuery.bindValues.size shouldBe 4
  }

  test("Text search SQL query with sorting is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))

    val sortField = new MetadataField(id = Some("sort_field_id"), name = "sortField", fieldType = FieldType.NUMBER)

    val q = new SearchQuery(
      text = Some("my text"),
      searchSort = List(SearchSort(sortField, SortDirection.ASC)))

    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM (SELECT asset.* FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND body MATCH ? AND is_recycled = ? AND search_document.asset_id = asset.id) AS asset, search_parameter AS sort_param WHERE sort_param.repository_id = ? AND sort_param.asset_id = asset.id AND sort_param.field_id = ? ORDER BY sort_param.field_value_num ASC"
    sqlQuery.bindValues.size shouldBe 6
  }

  test("Parameterized asset search SQL is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(
      params = Map(
        "text_field_id" -> "lol",
        "number_field_id" -> 12,
        "boolean_field_id" -> Query.EQUALS(false)
      )
    )
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM asset, search_parameter WHERE asset.repository_id = ? AND search_parameter.repository_id = ? AND is_recycled = ? AND ((field_id = ? AND field_value_kw = ?) OR (field_id = ? AND field_value_num = ?) OR (field_id = ? AND field_value_bool = ?)) AND search_parameter.asset_id = asset.id GROUP BY asset.id HAVING count(asset.id) >= 3"
    sqlQuery.bindValues.size shouldBe 9
  }

  test("Parameterized asset search SQL with sorting is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))

    val sortField = new MetadataField(id = Some("sort_field_id"), name = "sortField", fieldType = FieldType.BOOL)

    val q = new SearchQuery(
      params = Map(
        "text_field_id" -> "lol",
        "number_field_id" -> 12,
        "boolean_field_id" -> Query.EQUALS(false)
      ),
      searchSort = List(SearchSort(sortField, SortDirection.ASC))
    )
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM (SELECT asset.* FROM asset, search_parameter WHERE asset.repository_id = ? AND search_parameter.repository_id = ? AND is_recycled = ? AND ((field_id = ? AND field_value_kw = ?) OR (field_id = ? AND field_value_num = ?) OR (field_id = ? AND field_value_bool = ?)) AND search_parameter.asset_id = asset.id GROUP BY asset.id HAVING count(asset.id) >= 3) AS asset, search_parameter AS sort_param WHERE sort_param.repository_id = ? AND sort_param.asset_id = asset.id AND sort_param.field_id = ? ORDER BY sort_param.field_value_bool ASC"
    sqlQuery.bindValues.size shouldBe 11
  }
}
