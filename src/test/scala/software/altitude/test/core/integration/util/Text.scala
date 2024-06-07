package software.altitude.test.core.integration.util

object Text {
  def randomStr(size: Int = 10): String = scala.util.Random.alphanumeric.take(size).mkString
}
