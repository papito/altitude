package altitude.core.service

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.models.SystemMetadata
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import java.sql.SQLException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SystemService(val app: Altitude) {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val systemMetadataDao: SystemMetadataDao = app.DAO.systemMetadata
  protected val txManager: TransactionManager = app.txManager

  def version: Int = {
    txManager.withTransaction[Int] {
      try {
        readMetadata.version
      } catch {
        case ex: SQLException => {
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
    logger.warn("INITIALIZING SYSTEM")

    txManager.withTransaction {
      val admin = app.service.user.add(adminModel, password = password)

      val repo: Repository = app.service.repository.addRepository(
        name = repositoryName,
        fileStoreType = Const.StorageEngineName.FS, // hard default for now
        owner = admin)
//
//      // normally done on startup but on the first run still have to do this here.
      RequestContext.repository.value = Some(repo)
//      app.service.faceRecognition.initialize()
//
      app.service.user.setLastActiveRepoId(admin, repo.persistedId)

      systemMetadataDao.setInitialized()

      app.setIsInitializedState()

      RequestContext.account.value = Some(admin)
    }
  }
}
