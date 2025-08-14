package altitude.core

object Altitude extends cask.MainRoutes{
  @cask.get("/")
  def hello(): String = {
    "This is Altitude DAM"
  }

  @cask.post("/do-thing")
  def doThing(request: cask.Request): String = {
    request.text().reverse
  }

  initialize()
}
