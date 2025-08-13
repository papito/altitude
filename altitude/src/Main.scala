package altitude

import ox.*
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer

@main def helloWorldNettySyncServer(): Unit =
  val port = sys.env.get("HTTP_PORT").flatMap(_.toIntOption).getOrElse(8088)

  val helloWorld = endpoint.get
          .in("hello")
          .in(query[String]("name"))
          .out(stringBody)
          .handleSuccess(name => s"Hello 8, $name!")

  supervised {
    val serverBinding = NettySyncServer().port(port).addEndpoint(helloWorld).start()
    println(s"You can now make requests to http://${serverBinding.hostName}:${serverBinding.port}/hello?name=...!")

    // Add shutdown hook to handle Ctrl-C gracefully
    sys.addShutdownHook {
      println("Shutting down server...")
      serverBinding.stop()
      println("Server stopped.")
    }

    never
  }
