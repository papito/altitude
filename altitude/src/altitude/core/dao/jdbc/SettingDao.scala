package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.tables.SettingRow
import altitude.core.models.Setting

abstract class SettingDao(override val config: Config) extends BaseDao[Setting] with altitude.core.dao.SettingDao:

  override val tableName = "setting"

  // The table has no repository_id
  final override protected val repositoryScoped = false

  final override type Row[T[_]] = SettingRow[T]
  final override protected def table: Table[Row] = SettingRow

  override protected def toModel(row: SettingRow[Sc]): Setting =
    Setting(name = row.name, value = row.value, defaultValue = row.defaultValue)

  override protected def makeModel(rec: Map[String, AnyRef]): Setting = Setting(
    name = rec(FieldConst.Setting.NAME).asInstanceOf[String],
    value = rec(FieldConst.VALUE).asInstanceOf[String],
    defaultValue = rec(FieldConst.Setting.DEFAULT_VALUE).asInstanceOf[String]
  )

  override def getAll: List[Setting] =
    import dialect.*
    Db.read(dialect)(_.run(table.select)).map(toModel).toList

  override def updateValues(values: Map[String, String]): Unit =
    val sql = s"UPDATE $tableName SET ${FieldConst.VALUE} = ? WHERE ${FieldConst.Setting.NAME} = ?"
    val names = values.keys.toList
    val counts = batchBySql(sql, names.map(name => List(values(name), name)))

    // A statement that updated no row names a Setting that has no row
    val missing = names.zip(counts).collect { case (name, count) if count != 1 => name }
    if missing.nonEmpty then throw NotFoundException(s"No setting row for: ${missing.mkString(", ")}")
