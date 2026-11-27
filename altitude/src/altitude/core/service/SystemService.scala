package altitude.core.service

import java.security.SecureRandom
import java.util.Base64
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.AlreadyInitializedException
import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.SystemMetadataDao
import altitude.core.models.AuditEventType
import altitude.core.models.Repository
import altitude.core.models.SystemMetadata
import altitude.core.models.User
import altitude.core.transactions.TransactionManager

object SystemService:
  // 256 bits, the size of an HMAC-SHA256 output
  private val SIGNING_KEY_BYTES = 32

class SystemService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val systemMetadataDao: SystemMetadataDao = app.DAO.systemMetadata
  protected val txManager: TransactionManager = app.txManager

  def readMetadata: SystemMetadata =
    txManager.asReadOnly {
      systemMetadataDao.getById(SystemMetadataDao.SYSTEM_RECORD_ID.toString)
    }

  /**
   * The key of signed content URLs ([[ContentSigner]]), created on the first call against a database without one. Two instances
   * racing on one database each store theirs only while none is stored and then read it back, so both get the first one stored:
   * on PostgreSQL the second update waits on the row lock and then matches nothing; SQLite runs writers one at a time.
   */
  def loadOrCreateSigningKey(): Array[Byte] =
    txManager.withTransaction {
      val stored = systemMetadataDao.getSigningKey.getOrElse {
        val bytes = new Array[Byte](SystemService.SIGNING_KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        if systemMetadataDao.setSigningKey(Base64.getEncoder.encodeToString(bytes)) then
          logger.info("Generated the signing key of signed content URLs")
        else logger.debug("Another instance stored the signing key of signed content URLs first; adopting it")
        systemMetadataDao.getSigningKey.get
      }
      Base64.getDecoder.decode(stored)
    }

  /**
   * Sets the instance up: its first Admin, signed in on the request, and its first Library. An instance set up already is refused
   * with an `AlreadyInitializedException` and nothing is added. The system row is marked first, so of two setups at once the
   * second waits for the first to commit and is refused; the instance's flag is set once the change has committed, so a failed
   * commit leaves Setup open.
   */
  def initializeSystem(
      repositoryName: String,
      adminModel: User,
      password: String,
      clientIp: Option[String] = None): (User, Repository) =
    txManager.withTransaction {
      if !systemMetadataDao.setInitialized() then throw AlreadyInitializedException()
      logger.warn("INITIALIZING SYSTEM")

      val admin = app.service.user.add(adminModel, password = password)
      // The setup Admin creates itself: no other account exists to act
      app.service.audit.record(
        AuditEventType.AccountCreated,
        actor = Some(admin),
        target = Some(admin),
        detail = ujson.Obj("accountType" -> admin.accountType.toString, "setup" -> true),
        clientIp = clientIp
      )

      val repo: Repository = app.service.repository.addRepository(
        name = repositoryName,
        fileStoreType = Const.StorageEngineName.FS // hard default for now
      )

      RequestContext.repository.value = Some(repo)

      txManager.afterCommit(app.isInitialized = true)

      // Setup signs the new Admin in: the request is the Admin's from here on
      app.service.user.switchContextToUser(admin)

      (admin, repo)
    }
