package software.altitude.core.dao.postgres

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.dao.postgres.querybuilder.AssetSearchQueryBuilder
import software.altitude.core.models.Asset
import software.altitude.core.util.SearchQuery
import software.altitude.core.util.SearchResult
import software.altitude.core.{Const => C}

class SearchDao(override val config: Config) extends software.altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides {

  override protected def addSearchDocument(asset: Asset): Unit = {
    val docSql =
      s"""
         INSERT INTO search_document (
                     ${C.SearchToken.REPO_ID}, ${C.SearchToken.ASSET_ID},
                     metadata_values, body)
              VALUES (?, ?, ?, ?)
       """

    val metadataValues = asset.metadata.data.foldLeft(Set[String]()) { (res, m) =>
      res ++ m._2.map(_.value)
    }

    val sqlVals: List[Any] = List(
      RequestContext.getRepository.persistedId,
      asset.persistedId,
      metadataValues.mkString(" "),
      "" /* body */)

    addRecord(asset, docSql, sqlVals)
  }

  override protected def replaceSearchDocument(asset: Asset): Unit = {
    BaseDao.incrWriteQueryCount()

    val docSql =
      s"""
         UPDATE search_document
            SET metadata_values = ?
          WHERE ${C.Base.REPO_ID} = ?
            AND ${C.SearchToken.ASSET_ID} = ?
       """

    val metadataValues = asset.metadata.data.foldLeft(Set[String]()) { (res, m) =>
      res ++ m._2.map(_.value)
    }

    val sqlVals: List[Any] = List(
      metadataValues.mkString(" "), RequestContext.getRepository.persistedId, asset.persistedId)

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, docSql, sqlVals.map(_.asInstanceOf[Object]): _*)
  }

  // overriding for Postgres as AssetSearchQueryBuilder here is specific to Postgres
  override def search(searchQuery: SearchQuery): SearchResult = {
    val sqlQueryBuilder = new AssetSearchQueryBuilder(sqlColsForSelect = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT)

    val sqlQuery = sqlQueryBuilder.buildSelectSql(query = searchQuery)
    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)
    val total: Int = count(recs)

    logger.debug(s"Found [$total] records. Retrieved [${recs.length}] records")
    if (recs.nonEmpty) {
      logger.debug(recs.map(_.toString()).mkString("\n"))
    }

    logger.debug(s"Found [$total] records. Retrieved [${recs.length}] records")
    if (recs.nonEmpty) {
      logger.debug(recs.map(_.toString()).mkString("\n"))
    }

    SearchResult(
      records = recs.map{makeModel},
      total = total,
      rpp = searchQuery.rpp,
      sort = searchQuery.searchSort.toList)
  }
}
