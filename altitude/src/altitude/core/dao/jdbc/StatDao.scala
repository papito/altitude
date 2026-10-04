package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.SQLException
import scalasql.Sc
import scalasql.Table

import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.StatRow
import altitude.core.models.Stat
import altitude.core.models.Stats

abstract class StatDao(override val config: Config) extends BaseDao[Stat] with altitude.core.dao.StatDao:

  final override val tableName = "stats"

  final override type Row[T[_]] = StatRow[T]
  final override protected def table: Table[Row] = StatRow

  override protected def toModel(row: StatRow[Sc]): Stat = Stat(row.dimension, row.dimVal)

  private def repositoryId: String = RequestContext.getRepository.persistedId

  override protected def makeModel(rec: Map[String, AnyRef]): Stat =
    Stat(rec(FieldConst.Stat.DIMENSION).asInstanceOf[String], getLongField(rec(FieldConst.Stat.DIM_VAL)).get)

  override def add(stat: Stat): Stat =
    val sql: String = s"""
      INSERT INTO $tableName (${FieldConst.REPO_ID}, ${FieldConst.Stat.DIMENSION})
           VALUES (? ,?)"""

    val values: List[Any] = repositoryId :: stat.dimension :: Nil

    addRecord(sql, values)
    stat

  /**
   * Changes a stat of the context repository by `count`, which can be negative. The write fails for a stat the repository has no
   * row for, and for a change that would take the stat below zero, which the table's CHECK refuses.
   */
  def incrementStat(statName: String, count: Long = 1): Unit =
    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.Stat.DIM_VAL} = ${FieldConst.Stat.DIM_VAL} + ?
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.Stat.DIMENSION} = ?
      """

    val updated =
      try updateByBySql(sql, List(count, repositoryId, statName))
      catch
        // SQLite reports any constraint as 19, PostgreSQL a CHECK as 23514; the CHECK is the one this statement can break
        case ex: SQLException if ex.getErrorCode == 19 || ex.getSQLState == "23514" =>
          throw ConstraintException(
            s"Stat [$statName] of repository [$repositoryId] cannot change by [$count]: it would go below zero")
            .initCause(ex)

    requireOneRow(statName, updated)

  /**
   * The stat rows are locked first, in the order every stat write takes them, then the assets are counted: an operation that
   * changed assets and has yet to write its stats either committed before the count or adds its change to the values set here.
   * The assets that count are the completed ones not marked for purging, bucketed as `StatsService.transition` buckets an asset:
   * recycled when recycled, else triage when triaged, else sorted.
   */
  override def reconcile(): Map[String, Long] =
    val storedSql = s"""
      SELECT *
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
    ORDER BY ${FieldConst.Stat.DIMENSION}
             $forUpdate
      """
    val stored = manyBySqlQuery(storedSql, List(repositoryId)).map(makeModel).map(stat => stat.dimension -> stat.dimVal).toMap

    val sorted = "is_recycled = FALSE AND is_triaged = FALSE"
    val triage = "is_recycled = FALSE AND is_triaged = TRUE"
    val recycled = "is_recycled = TRUE"
    val countsSql = s"""
      SELECT COALESCE(SUM(CASE WHEN $sorted THEN 1 ELSE 0 END), 0) AS ${Stats.SORTED_ASSETS},
             COALESCE(SUM(CASE WHEN $sorted THEN size_bytes ELSE 0 END), 0) AS ${Stats.SORTED_BYTES},
             COALESCE(SUM(CASE WHEN $triage THEN 1 ELSE 0 END), 0) AS ${Stats.TRIAGE_ASSETS},
             COALESCE(SUM(CASE WHEN $triage THEN size_bytes ELSE 0 END), 0) AS ${Stats.TRIAGE_BYTES},
             COALESCE(SUM(CASE WHEN $recycled THEN 1 ELSE 0 END), 0) AS ${Stats.RECYCLED_ASSETS},
             COALESCE(SUM(CASE WHEN $recycled THEN size_bytes ELSE 0 END), 0) AS ${Stats.RECYCLED_BYTES}
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND is_pipeline_processed = TRUE
         AND is_purged = FALSE
      """
    // PostgreSQL sums a BIGINT as a NUMERIC
    val counted = executeAndGetOne(countsSql, List(repositoryId)).map((dimension, value) => dimension -> getLongField(value).get)

    val wrong = counted.filter((dimension, value) => !stored.get(dimension).contains(value))
    wrong.foreach((dimension, value) => setStat(dimension, value))
    wrong.map((dimension, _) => dimension -> stored(dimension))

  private def setStat(statName: String, value: Long): Unit =
    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.Stat.DIM_VAL} = ?
       WHERE ${FieldConst.REPO_ID} = ? AND ${FieldConst.Stat.DIMENSION} = ?
      """
    requireOneRow(statName, updateByBySql(sql, List(value, repositoryId, statName)))

  /** A stat write updates the one row of its dimension; none means the repository has no such stat */
  private def requireOneRow(statName: String, updated: Int): Unit =
    if updated != 1 then throw ConstraintException(s"Stat [$statName] of repository [$repositoryId] has $updated rows, not one")
