package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import altitude.core.RequestContext
import altitude.core.dao.postgres.querybuilder.AssetSearchQueryBuilder as PostgresAssetSearchQueryBuilder
import altitude.core.dao.sqlite.querybuilder.AssetSearchQueryBuilder as SqliteAssetSearchQueryBuilder
import altitude.core.models.Repository
import altitude.core.util.*
import altitude.core.Const as C
import altitude.test.TestFocus
import org.scalatest.matchers.should.Matchers.shouldBe

@DoNotDiscover class SearchSqlQueryTests extends funsuite.AnyFunSuite with TestFocus {
  private val repo = new Repository(
    id = Some("1"),
    name = "repo name",
    ownerAccountId = Util.randomStr(),
    rootFolderId = "1",
    fileStoreConfig = Map(),
    fileStoreType = C.StorageEngineName.FS)

  RequestContext.repository.value = Some(repo)
  RequestContext.account.value = None

  test("Basic WHERE SQL asset query is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery()
    val sqlQuery = builder.buildSelectSql(q)

    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset WHERE asset.repository_id = ? AND asset.is_pipeline_processed = ?"
    sqlQuery.bindValues.size shouldBe 2
  }

  test("Basic WHERE SQL asset query with parameters is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery().add("string_param" -> "a_value").add("bool_param" -> true)
    val sqlQuery = builder.buildSelectSql(q)

    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset WHERE asset.repository_id = ? AND asset.string_param = ? AND asset.bool_param = ? AND asset.is_pipeline_processed = ?"
    sqlQuery.bindValues.size shouldBe 4
  }

  test("WHERE asset query can be narrowed down by folders") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(folderIds = Set("1", "2", "3")).add("string_param" -> "a_value").add("num_param" -> 10)
    val sqlQuery = builder.buildSelectSql(q)

    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset WHERE asset.repository_id = ? AND asset.string_param = ? AND asset.num_param = ? AND asset.is_pipeline_processed = ? AND folder_id IN (?, ?, ?)"
    sqlQuery.bindValues.size shouldBe 7
  }

  test("Sqlite text search SQL query is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(text = Some("my text")).add("string_param" -> "a_value").add("num_param" -> 10)
    val sqlQuery = builder.buildSelectSql(q)

    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND body MATCH ? AND asset.string_param = ? AND asset.num_param = ? AND asset.is_pipeline_processed = ? AND search_document.asset_id = asset.id"
    sqlQuery.bindValues.size shouldBe 6
  }

  test("Postgres text search SQL query is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(text = Some("my text")).add("string_param" -> "a_value").add("num_param" -> 10)
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND search_document.tsv @@ to_tsquery(?) AND asset.string_param = ? AND asset.num_param = ? AND asset.is_pipeline_processed = ? AND search_document.asset_id = asset.id"
    sqlQuery.bindValues.size shouldBe 6
  }

  test("Text search SQL query with sorting is built correctly") {
    val builder = new SqliteAssetSearchQueryBuilder(List("*"))

    val q = new SearchQuery(
      text = Some("my text"),
      searchSort = List(SearchSort("sort_field", SortDirection.ASC)))

    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM ( SELECT asset.* FROM asset, search_document WHERE asset.repository_id = ? AND search_document.repository_id = ? AND body MATCH ? AND asset.is_pipeline_processed = ? AND search_document.asset_id = asset.id ) AS asset ORDER BY asset.sort_field ASC"
    sqlQuery.bindValues.size shouldBe 4
  }

  test("Metadata search SQL is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))
    val q = new SearchQuery(
      metadataFilters = Map(
        "text_field_id" -> "lol",
        "number_field_id" -> 12,
        "boolean_field_id" -> Query.EQUALS(false)
      )
    )
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM asset, metadata_parameter WHERE asset.repository_id = ? AND metadata_parameter.repository_id = ? AND asset.is_pipeline_processed = ? AND ((field_id = ? AND field_value_kw = ?) OR (field_id = ? AND field_value_num = ?) OR (field_id = ? AND field_value_bool = ?)) AND metadata_parameter.asset_id = asset.id GROUP BY asset.id HAVING count(asset.id) >= 3"
    sqlQuery.bindValues.size shouldBe 9
  }

  test("Metadata search SQL with sorting and params is built correctly") {
    val builder = new PostgresAssetSearchQueryBuilder(List("*"))

    val q = new SearchQuery(
      metadataFilters = Map(
        "text_field_id" -> "lol",
        "number_field_id" -> 12,
        "boolean_field_id" -> Query.EQUALS(false)
      ),
      searchSort = List(SearchSort("sort_field", SortDirection.ASC))
    ).add("string_param" -> "a_value").add("num_param" -> 10)

    val sqlQuery = builder.buildSelectSql(q)

    sqlQuery.sqlAsStringCompact shouldBe "SELECT *, count(*) OVER() AS total FROM ( SELECT asset.* FROM asset, metadata_parameter WHERE asset.repository_id = ? AND metadata_parameter.repository_id = ? AND asset.string_param = ? AND asset.num_param = ? AND asset.is_pipeline_processed = ? AND ((field_id = ? AND field_value_kw = ?) OR (field_id = ? AND field_value_num = ?) OR (field_id = ? AND field_value_bool = ?)) AND metadata_parameter.asset_id = asset.id GROUP BY asset.id HAVING count(asset.id) >= 3 ) AS asset ORDER BY asset.sort_field ASC"
    sqlQuery.bindValues.size shouldBe 11

  }
}
