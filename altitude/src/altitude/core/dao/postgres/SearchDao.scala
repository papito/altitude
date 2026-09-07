package altitude.core.dao.postgres

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.postgres.querybuilder.AssetSearchQueryBuilder
import altitude.core.models.Asset

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:

  override protected def addSearchDocument(asset: Asset): Unit =
    val docSql =
      s"""
         INSERT INTO search_document (
                     ${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID},
                     metadata_values, body)
              VALUES (?, ?, ?, ?)
       """

    val metadataValues = asset.userMetadata.data.foldLeft(Set[String]())((res, m) => res ++ m._2.map(_.value))

    val sqlVals: List[Any] =
      List(RequestContext.getRepository.persistedId, asset.persistedId, metadataValues.mkString(" "), "" /* body */ )

    addRecord(docSql, sqlVals)

  override protected def replaceSearchDocument(asset: Asset): Unit =
    BaseDao.incrWriteQueryCount()

    val docSql =
      s"""
         UPDATE search_document
            SET metadata_values = ?
          WHERE ${FieldConst.REPO_ID} = ?
            AND ${FieldConst.SearchToken.ASSET_ID} = ?
       """

    val metadataValues = asset.userMetadata.data.foldLeft(Set[String]())((res, m) => res ++ m._2.map(_.value))

    val sqlVals: List[Any] = List(metadataValues.mkString(" "), RequestContext.getRepository.persistedId, asset.persistedId)

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, docSql, sqlVals.map(_.asInstanceOf[Object])*)

  override protected val assetSearchQueryBuilder: AssetSearchQueryBuilder =
    new AssetSearchQueryBuilder(sqlColsForSelect = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT)
