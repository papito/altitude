package software.altitude.core

import com.google.inject.Injector
import org.scalatra.auth.ScentryStrategy
import org.slf4j.LoggerFactory
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionManager

trait AltitudeAppContext {
  private final val log = LoggerFactory.getLogger(getClass)

  val scentryStrategies: List[(String, Class[_ <: ScentryStrategy[User]])]

  /**
   * At this point determine which data access classes we are loading, which
   * transaction manager we are using for the data sources of choice, load the drivers,
   * etc.
   */
  val injector: Injector

  val txManager: TransactionManager

  // ID for this application - which we may have multiple of in the same environment
  final val id: Int = scala.util.Random.nextInt(java.lang.Integer.MAX_VALUE)

  // this is the environment we are running in
  final val environment: String = Environment.ENV match {
    case Environment.DEV => "development"
    case Environment.PROD => "production"
    case Environment.TEST => "test"
  }
  log.info(s"Environment is: $environment")

  protected val configOverride: Map[String, Any]
  final val config = new Configuration(configOverride = configOverride)

  final val dataSourceType = config.datasourceType
  log.info(s"Datasource type: $dataSourceType")
}
