package altitude.core.transactions

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement

import scala.collection.mutable

import altitude.core.transactions.SqlExplainer.Bind

/**
 * A connection that offers each DML statement prepared on it to a [[SqlExplainer]], just before the statement first runs.
 * Everything else goes to the wrapped connection untouched, statements run through `createStatement` included, so the
 * housekeeping of a transaction and DDL are never explained - and neither is a statement with no bind values, which ScalaSql (an
 * update) and the commons-dbutils query runner send through `createStatement`.
 */
object ExplainingConnection:

  // What runs a prepared statement, or queues its first row of a batch: the calls with no arguments
  private val EXECUTING = Set("execute", "executeQuery", "executeUpdate", "addBatch")

  def apply(conn: Connection, explainer: SqlExplainer): Connection =
    proxy(classOf[Connection]) {
      (method, args) =>
        (forward(conn, method, args), args) match
          case (statement: PreparedStatement, Array(sql: String, _*))
              if method.getName == "prepareStatement" && SqlExplainer.isExplainable(sql) =>
            explainingStatement(conn, statement, sql, explainer)
          case (result, _) => result
    }

  /**
   * A prepared statement that records the values bound to it and, the first time it is run or added to a batch, offers itself
   * with them to the explainer, on the connection it was prepared on. A batch is so explained once, with its first row.
   */
  private def explainingStatement(
      conn: Connection,
      statement: PreparedStatement,
      sql: String,
      explainer: SqlExplainer): PreparedStatement =
    val binds = mutable.Map[Int, Bind]()
    var isOffered = false

    proxy(classOf[PreparedStatement]) {
      (method, args) =>
        (method.getName, args) match
          // setX(index, value, ...); the second argument of setNull is a type, not a value
          case (name, Array(index: Integer, value, _*)) if name.startsWith("set") =>
            binds(index) = Bind(if name == "setNull" then null else value, forward(_, method, args))
          case ("clearParameters", _) => binds.clear()
          case (name, null) if EXECUTING(name) && !isOffered =>
            isOffered = true
            explainer.offer(conn, sql, binds.toMap)
          case _ =>

        forward(statement, method, args)
    }

  private def proxy[A](interface: Class[A])(handler: (Method, Array[AnyRef]) => AnyRef): A =
    Proxy
      .newProxyInstance(interface.getClassLoader, Array[Class[?]](interface), (_, method, args) => handler(method, args))
      .asInstanceOf[A]

  /** Calls the method on the wrapped object; its failure is thrown as it is, not wrapped in the reflection's own */
  private def forward(target: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
    try method.invoke(target, args*)
    catch case ex: InvocationTargetException => throw ex.getCause
