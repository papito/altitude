package altitude

import sttp.tapir.*

import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

object Endpoints:
    case class User(name: String) extends AnyVal
    val helloEndpoint: PublicEndpoint[User, Unit, String, Any] = endpoint.get
            .in("hello")
            .in(query[User]("name"))
            .out(stringBody)
    val helloServerEndpoint: ServerEndpoint[Any, Identity] = helloEndpoint.handleSuccess(user => s"Hello you yo ${user.name}")

    val apiEndpoints: List[ServerEndpoint[Any, Identity]] = List(helloServerEndpoint)

    val all: List[ServerEndpoint[Any, Identity]] = apiEndpoints
