package software.altitude.test.core

abstract class HtmxTestCore extends WebTestCore {

  override def header = null

  protected def getHeaders: Map[String, String] = Map("Content-Type" -> "application/json")

}
