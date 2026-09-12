package altitude.core.dao.sqlite

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.sqlite.querybuilder.AssetSearchQueryBuilder
import altitude.core.models.Asset

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with SqliteOverrides:

  override protected def addSearchDocument(asset: Asset): Unit =
    val docSql =
      s"""
         INSERT INTO search_document (${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID}, body)
              VALUES (?, ?, ?)
       """

    val metadataValues = asset.userMetadata.data.foldLeft(Set[String]())((res, m) => res ++ m._2.map(_.value))

    val body = metadataValues.mkString(" ")

    val sqlVals: List[Any] = List(RequestContext.getRepository.persistedId, asset.persistedId, body)

    addRecord(docSql, sqlVals)

  override protected def replaceSearchDocument(asset: Asset): Unit =
    BaseDao.incrWriteQueryCount()

    val docSql =
      s"""
         UPDATE search_document
            SET body = ?
          WHERE ${FieldConst.REPO_ID} = ?
            AND ${FieldConst.SearchToken.ASSET_ID} = ?
        """

    val metadataValues = asset.userMetadata.data.foldLeft(Set[String]())((res, m) => res ++ m._2.map(_.value))

    val body = metadataValues.mkString(" ")

    val sqlVals: List[Any] = List(body, RequestContext.getRepository.persistedId, asset.persistedId)

    val runner: QueryRunner = new QueryRunner()
    runner.update(RequestContext.getConn, docSql, sqlVals.map(_.asInstanceOf[Object])*)

  override protected val assetSearchQueryBuilder: AssetSearchQueryBuilder =
    new AssetSearchQueryBuilder(sqlColsForSelect = columnsForSelect)
