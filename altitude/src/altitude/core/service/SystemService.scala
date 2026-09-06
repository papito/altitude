package altitude.core.service

import java.sql.SQLException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.SystemMetadataDao
import altitude.core.models.Repository
import altitude.core.models.SystemMetadata
import altitude.core.models.User
import altitude.core.transactions.TransactionManager

class SystemService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val systemMetadataDao: SystemMetadataDao = app.DAO.systemMetadata
  protected val txManager: TransactionManager = app.txManager

  def version: Int =
    txManager.withTransaction {
      try readMetadata.version
      catch
        case ex: SQLException =>
          // Swallow - normal for new installations and tests with no DB yet
          0
    }

  def setVersion(version: Int): Unit =
    txManager.withTransaction {
      systemMetadataDao.updateVersion(toVersion = version)
    }

  def readMetadata: SystemMetadata =
    txManager.asReadOnly {
      systemMetadataDao.getById(SystemMetadataDao.SYSTEM_RECORD_ID.toString)
    }

  def initializeSystem(repositoryName: String, adminModel: User, password: String): (User, Repository) =
    logger.warn("INITIALIZING SYSTEM")

    txManager.withTransaction {
      val admin = app.service.user.add(adminModel, password = password)

      val repo: Repository = app.service.repository.addRepository(
        name = repositoryName,
        fileStoreType = Const.StorageEngineName.FS, // hard default for now
        owner = admin)

      RequestContext.repository.value = Some(repo)

      systemMetadataDao.setInitialized()

      app.setIsInitializedState()

      RequestContext.account.value = Some(admin)

      (admin, repo)
    }
