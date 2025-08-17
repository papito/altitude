package altitude.core.routes

import altitude.core.util.Util
import cask.model.Response.Raw
import cask.router.Result
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import java.lang.System.currentTimeMillis

object decorators {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  class requestResponseLogger extends cask.RawDecorator {
    override def wrapFunction(req: cask.Request, delegate: Delegate): Result[Raw] = {
      val startTime = currentTimeMillis

      val requestId = Util.randomStr(size = 6)
      // this is used by logback pattern layout
      MDC.put("REQUEST_ID", requestId)

      logger.info(s"Request START - ${req.exchange.getRequestPath}, ${req.exchange.getRequestMethod}?${req.exchange.getQueryParameters}")

      delegate(req, Map()) match {
        case cask.router.Result.Success(response) =>
          logger.info(s"Request END [${response.statusCode}] - ${req.exchange.getRequestPath}, ${req.exchange.getRequestMethod}?${req.exchange.getQueryParameters} in ${currentTimeMillis - startTime}ms")
          cask.router.Result.Success(response)

        case error =>
          logger.error(error.toString)
          error
      }
    }
  }
}
