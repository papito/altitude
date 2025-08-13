package software.altitude.test.core

import org.scalatra.test.ScalatraTests
import org.scalatra.test.scalatest.ScalatraFunSuite
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.AltitudeServletContext

abstract class ApiTestCore extends IntegrationTestCore with ScalatraTests with ScalatraFunSuite {

  override def header = null

  protected def getHeaders: Map[String, String] = Map("Content-Type" -> "application/json")

  final def jsonResponse: Option[JsObject] = Some(
    if (response.body.isEmpty) Json.obj() else Json.parse(response.body).as[JsObject]
  )

  // mount all controllers, just as we do in ScalatraBootstrap
  AltitudeServletContext.endpoints.foreach { case (servlet, path) =>
    mount(servlet, path)
  }
}
