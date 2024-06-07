package software.altitude.core.dao.jdbc.querybuilder

import org.slf4j.LoggerFactory
import software.altitude.core.Context
import software.altitude.core.util.Query
import software.altitude.core.util.Query.QueryParam
import software.altitude.core.{Const => C}

protected object SqlQueryBuilder {
  val SELECT = "select"
  val FROM = "from"
  val JOIN = "join"
  val WHERE = "where"
  val GROUP_BY = "group_by"
  val ORDER_BY = "order_by"
  val HAVING = "having"
}

class SqlQueryBuilder[QueryT <: Query](selColumnNames: List[String], tableNames: Set[String]) {
  private final val log = LoggerFactory.getLogger(getClass)

  // convenience constructor for the common case of just one table
  def this(sqlColsForSelect: List[String], tableName: String) = {
    this(sqlColsForSelect, Set(tableName))
  }

  type ClauseGeneratorType = (QueryT, Context) => ClauseComponents

  protected val chainMethods: List[(String, ClauseGeneratorType)] = List(
    (SqlQueryBuilder.SELECT, this.select),
    (SqlQueryBuilder.FROM, this.from),
    (SqlQueryBuilder.WHERE, this.where),
    (SqlQueryBuilder.GROUP_BY, this.groupBy),
    (SqlQueryBuilder.ORDER_BY, this.orderBy),
    (SqlQueryBuilder.HAVING, this.having)
  )

  def buildSelectSql(query: QueryT)(implicit ctx: Context): SqlQuery = {
    val allClauses = compileClauses(query, ctx)

    val sql: String = selectStr(allClauses(SqlQueryBuilder.SELECT)) +
      fromStr(allClauses(SqlQueryBuilder.FROM)) +
      whereStr(allClauses(SqlQueryBuilder.WHERE)) +
      groupByStr(allClauses(SqlQueryBuilder.GROUP_BY)) +
      havingStr(allClauses(SqlQueryBuilder.HAVING)) +
      orderByStr(allClauses(SqlQueryBuilder.ORDER_BY)) +
      limitStr(query) +
      offsetStr(query)

    val bindValClauses = List(allClauses(SqlQueryBuilder.WHERE))
    val bindVals = bindValClauses.foldLeft(List[Any]()) { (res, clause) =>
      res ++ clause.bindVals
    }

    log.debug(s"Select SQL: $sql with $bindVals")
    // println("SELECT", sql, bindVals)
    SqlQuery(sql, bindVals)
  }

  def buildCountSql(query: QueryT)(implicit ctx: Context): SqlQuery = {
    // the SQL is the same but the SELECT clause is just the COUNT, so override it
    val selectClauseForCount = ClauseComponents(List("COUNT(*) AS count"))
    val allClauses = compileClauses(query, ctx) + (
      SqlQueryBuilder.SELECT -> selectClauseForCount)

    val sql: String = selectStr(allClauses(SqlQueryBuilder.SELECT)) +
      fromStr(allClauses(SqlQueryBuilder.FROM)) +
      whereStr(allClauses(SqlQueryBuilder.WHERE)) +
      groupByStr(allClauses(SqlQueryBuilder.GROUP_BY)) +
      havingStr(allClauses(SqlQueryBuilder.HAVING))

    val bindVals = allClauses.foldLeft(List[Any]()) { (res, comp) =>
      res ++ comp._2.bindVals
    }

    log.debug(s"Count SQL: $sql with $bindVals")
    // println("COUNT", sql, bindVals)
    SqlQuery(sql, bindVals)
  }

  protected def compileClauses(query: QueryT, ctx: Context): Map[String, ClauseComponents] = {
    // collect clauses so we can refer to them when we build the SQL string
    chainMethods.foldLeft(Map[String, ClauseComponents]()) { (res, m) =>
      val clauseName = m._1
      val clauseGeneratorMethod = m._2
      val clauseComponents = clauseGeneratorMethod(query, ctx)
      res + (clauseName -> clauseComponents)
    }
  }

  protected def select(query: QueryT, ctx: Context): ClauseComponents = ClauseComponents(elements = selColumnNames)
  protected def selectStr(clauseComponents: ClauseComponents): String = {
    val columnNames = clauseComponents.elements
    s"SELECT ${columnNames.mkString(", ")}"
  }

  protected def from(query: QueryT, ctx: Context): ClauseComponents = ClauseComponents(elements = tableNames.toList)
  protected def fromStr(clauseComponents: ClauseComponents): String = {
    val tableNames = clauseComponents.elements
    s" FROM ${tableNames.mkString(", ")}"
  }

  protected def where(query: QueryT, ctx: Context): ClauseComponents = {
    // FIXME: find a better way to get elements and bind vals in one swoop
    val elements = query.params.map { el: (String, Any) =>
      val (columnName, value) = el
      value match {
        case _: String => s"$columnName = ?"
        case _: Boolean => s"$columnName = ?"
        case _: Number => s"$columnName = ?"
        case qParam: QueryParam => qParam.paramType match {
          case Query.ParamType.IN => {
            val placeholders: String = qParam.values.toList.map {_ => "?"}.mkString(", ")
            s"$columnName IN ($placeholders)"
          }
          case Query.ParamType.EQ => s"$columnName = ?"

          case _ => throw new IllegalArgumentException(s"This type of parameter is not supported: ${qParam.paramType}")
        }
        case _ => throw new IllegalArgumentException(s"This type of parameter is not supported: $value")
      }
    }.toList ::: tableNames.map(tableName => s"$tableName.${C.Base.REPO_ID} = ?").toList

    val bindVals = query.params.foldLeft(List[Any]()) { (res, el: (String, Any)) =>
      val value = el._2
      value match {
        // string or integer values as is
        case _: String => res :+ value
        case _: Number => res :+ value
        // boolean becomes 0 or 1
        case _: Boolean => res :+ (if (value == true) 1 else 0)
        // extract all values from qParam
        case qParam: QueryParam => res ::: qParam.values.toList
        case _ => throw new IllegalArgumentException(s"This type of parameter is not supported: $value")
      }
    } ::: tableNames.toSeq.map(_ => ctx.repo.id.get).toList // repo id for each table

    ClauseComponents(elements, bindVals)
  }

  protected def whereStr(clauseComponents: ClauseComponents): String = {
    val whereClauses = clauseComponents.elements
    s" WHERE ${whereClauses.mkString(" AND ")}"
  }

  protected def groupBy(query: QueryT, ctx: Context): ClauseComponents = ClauseComponents()
  protected def groupByStr(clauseComponents: ClauseComponents): String = {
    if (clauseComponents.isEmpty) return ""
    s" GROUP BY ${clauseComponents.elements.mkString(", ")}"
  }

  protected def orderBy(query: QueryT, ctx: Context): ClauseComponents = {
    if (!query.isSorted) return ClauseComponents()

   val sort = query.sort.head
   ClauseComponents(elements = List(s"${sort.param} ${sort.direction}"))
  }

  protected def orderByStr(clauseComponents: ClauseComponents): String = {
    if (clauseComponents.isEmpty) return ""
    s" ORDER BY ${clauseComponents.elements.mkString(", ")}"
  }

  protected def having(query: QueryT, ctx: Context): ClauseComponents = ClauseComponents()
  protected def havingStr(clauseComponents: ClauseComponents): String = {
    if (clauseComponents.isEmpty) return ""
    s" HAVING ${clauseComponents.elements.mkString(", ")}"
  }

  protected def limitStr(query: QueryT): String = if (query.rpp > 0) s" LIMIT ${query.rpp}" else ""
  protected def offsetStr(query: QueryT): String = if (query.rpp > 0) s" OFFSET ${(query.page - 1) * query.rpp}" else ""
}
