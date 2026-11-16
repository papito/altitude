package altitude.core.transactions

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement

import scala.collection.mutable

import altitude.core.transactions.SqlExplainer.Bind

/**
 * Wraps a connection so each DML statement prepared on it is offered to a [[SqlExplainer]] just before it first runs.
 * `createStatement` passes through untouched, so transaction housekeeping, DDL, and the bind-less statements ScalaSql and
 * commons-dbutils send that way are never explained.
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
   * Records the values bound to the statement and offers it, with them, to the explainer the first time it runs or is added to a
   * batch, so a batch is explained once, with its first row.
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

  /** Calls the method on the wrapped object, rethrowing its failure unwrapped */
  private def forward(target: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
    try method.invoke(target, args*)
    catch case ex: InvocationTargetException => throw ex.getCause
