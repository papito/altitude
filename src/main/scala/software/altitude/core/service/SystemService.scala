package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions.ScalaInjector
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.dao.SystemMetadataDao
import software.altitude.core.models.SystemMetadata
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.{Const => C}

import java.sql.SQLException

class SystemService(val app: Altitude) {
  private final val log = LoggerFactory.getLogger(getClass)

  private val systemMetadataDao: SystemMetadataDao = app.injector.instance[SystemMetadataDao]
  protected val txManager: TransactionManager = app.txManager

  def version: Int = {
    txManager.withTransaction[Int] {
      try {
        readMetadata.version
      }
      catch {
        case ex: SQLException=> {
          /* Uncomment this if you get "current transaction is aborted, commands ignored until end of transaction block".
             It means the select query failed when it should not have, but the exception itself is normal for new installations
             AND tests (when there is no database yet). Seeing that error when running tests is annoying, so we just
             swallow it here.
           */
          // println(ex)

          0 // new installation
        }
        case ex: Exception => throw ex
      }
    }
  }

  def versionUp(): Unit = {
    val toVersion = version + 1

    txManager.withTransaction {
      systemMetadataDao.updateVersion(toVersion = toVersion)
    }
  }

  def readMetadata: SystemMetadata = {
    txManager.asReadOnly[SystemMetadata] {
      systemMetadataDao.getById(SystemMetadataDao.SYSTEM_RECORD_ID.toString)
    }
  }

  def initializeSystem(repositoryName: String, adminModel: User, password: String): Unit = {
    log.warn("INITIALIZING SYSTEM")

    txManager.withTransaction {
      val admin = app.service.user.add(adminModel, password = password)

      app.service.repository.addRepository(
        name = repositoryName,
        fileStoreType = C.FileStoreType.FS,
        owner = admin)

      systemMetadataDao.setInitialized()

      app.setIsInitializedState()

      RequestContext.account.value = Some(admin)
    }
  }
}
