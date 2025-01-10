package software.altitude.core.dao.jdbc.querybuilder

/**
 * This type encapsulates elements of a SQL clause.
 * @param List
 *   of SQL strings comprising a clause. It can be any valid statement, which should contain bind placeholders [?]. For example,
 *   ["my_field1 \= ?", "my_field2 = ?"]
 *
 * Clause components are not full and complete SQL statements. They are used as an intermediate artifact while a query is being
 * built.
 *
 * Can be combined with the + operator.
 *
 * @param bindVals
 *   List of values to be bound to the statements stored in the "elements" property.
 */
case class ClauseComponents(elements: List[String] = List(), bindVals: List[Any] = List()) {
  val isEmpty: Boolean = elements.isEmpty && bindVals.isEmpty

  // scalastyle:off
  def +(that: ClauseComponents): ClauseComponents =
    ClauseComponents(this.elements ::: that.elements, this.bindVals ::: that.bindVals)
  // scalastyle:on

}
