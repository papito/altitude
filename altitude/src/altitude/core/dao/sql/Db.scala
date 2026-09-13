package altitude.core.dao.sql

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scalasql.core.Context
import scalasql.dialects.Dialect

import altitude.core.RequestContext

/**
 * The ScalaSql entry point for the DAO layer.
 *
 * ScalaSql runs on the connection the request is already holding: [[TransactionManager]] opens it, `RequestContext.conn` carries
 * it, and this object never opens, commits or closes one. A `DbApi` is a cheap wrapper, so it is built per call rather than
 * cached, and it is deliberately never closed - closing it would close the transaction's connection.
 */
object Db:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Central SQL logging for every statement ScalaSql runs, replacing the per-call `logger.debug(sql)` of the raw paths */
  val config: scalasql.Config = new scalasql.Config:
    override def logSql(sql: String, file: String, line: Int): Unit =
      logger.debug(s"SQL: $sql ($file:$line)")

  /** Exactly one read query, counted as the raw paths count theirs */
  def read[R](dialect: Dialect)(f: scalasql.DbApi => R): R =
    RequestContext.readQueryCount.value = RequestContext.readQueryCount.value + 1
    f(api(dialect))

  /** Exactly one write query, counted as the raw paths count theirs */
  def write[R](dialect: Dialect)(f: scalasql.DbApi => R): R =
    RequestContext.writeQueryCount.value = RequestContext.writeQueryCount.value + 1
    f(api(dialect))

  /** The context a typed query renders in when it is spliced into a hand-written statement */
  def rootContext(dialect: Dialect): Context =
    Context.Impl(Map(), Map(), valueMarker = false, config, dialect)

  /** Renders a typed query to a fragment that carries its own bind values */
  def render(query: scalasql.core.SqlStr.Renderable, dialect: Dialect): scalasql.SqlStr =
    scalasql.core.SqlStr.Renderable.renderSql(query)(rootContext(dialect))

  private def api(dialect: Dialect): scalasql.DbApi =
    new scalasql.core.DbApi.Impl(RequestContext.getConn, config, dialect, Nil, autoCommit = false)
