package software.altitude.core.dao.sqlite

import org.apache.commons.dbutils.QueryRunner
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.dao.sqlite.querybuilder.AssetSearchQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult
import software.altitude.core.{Const => C}

class SearchDao(override val app: Altitude) extends software.altitude.core.dao.jdbc.SearchDao(app) with Sqlite {
  private final val log = LoggerFactory.getLogger(getClass)

  override protected def addSearchDocument(asset: Asset)(implicit ctx: Context, txId: TransactionId): Unit = {
    val docSql =
      s"""
         INSERT INTO search_document (${C.Base.REPO_ID}, ${C.SearchToken.ASSET_ID}, body)
              VALUES (?, ?, ?)
       """

    val metadataValues = asset.metadata.data.foldLeft(Set[String]()) { (res, m) =>
      res ++ m._2.map(_.value)
    }

    val body = metadataValues.mkString(" ")

    val sqlVals: List[Any] = List(
      ctx.repo.id.get, asset.id.get, body)

    addRecord(asset, docSql, sqlVals)
  }

  override protected def replaceSearchDocument(asset: Asset)(implicit ctx: Context, txId: TransactionId): Unit = {
    val docSql =
      s"""
         UPDATE search_document
            SET body = ?
          WHERE ${C.Base.REPO_ID} = ?
            AND ${C.SearchToken.ASSET_ID} = ?
       """

    val metadataValues = asset.metadata.data.foldLeft(Set[String]()) { (res, m) =>
      res ++ m._2.map(_.value)
    }

    val body = metadataValues.mkString(" ")

    val sqlVals: List[Any] = List(
      body, ctx.repo.id.get, asset.id.get)

    addRecord(asset, docSql, sqlVals)

    val runner: QueryRunner = new QueryRunner()
    runner.update(conn, docSql, sqlVals.map(_.asInstanceOf[Object]): _*)
  }

  override def search(searchQuery: SearchQuery)(implicit ctx: Context, txId: TransactionId): SearchResult = {
    val sqlQueryBuilder = new AssetSearchQueryBuilder(sqlColsForSelect = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT)

    val sqlQuery = sqlQueryBuilder.buildSelectSql(query = searchQuery)
    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)

    val sqlCountQuery = sqlQueryBuilder.buildCountSql(query = searchQuery)
    val count: Int = getQueryResultCountBySql(sqlCountQuery.sqlAsString, sqlCountQuery.bindValues)

    log.debug(s"Found [$count] records. Retrieved [${recs.length}] records")
    if (recs.nonEmpty) {
      log.debug(recs.map(_.toString()).mkString("\n"))
    }

    log.debug(s"Found [$count] records. Retrieved [${recs.length}] records")
    if (recs.nonEmpty) {
      log.debug(recs.map(_.toString()).mkString("\n"))
    }

    SearchResult(
      records = recs.map{makeModel},
      total = count,
      rpp = searchQuery.rpp,
      sort = searchQuery.searchSort.toList)
  }
}
