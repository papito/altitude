package software.altitude.core.service.migration

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.models.Repository
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

abstract class MigrationService(val app: Altitude) extends CoreMigrationService {
  protected final val log: Logger = LoggerFactory.getLogger(getClass)

  def migrateVersion(ctx: Context, version: Int)(implicit txId: TransactionId = new TransactionId): Unit = {
      version match {
        case 1 => v1(ctx)
      }
  }

  private def v1(context: Context)(implicit txId: TransactionId = new TransactionId): Unit = {
    // create temporary default user
    log.info("Creating default user...")
    val user: User = app.service.user.addUser(User(id = Some(app.USER_ID)))
    log.info(s"Default user created with id [${user.id.get}]")

    // create temporary default repo
    log.info("Creating default repo...")
    val repo: Repository = app.service.repository.addRepository(
      id = Some(app.REPOSITORY_ID),
      name = "Default Repository",
      fileStoreType = C.FileStoreType.FS,
      user = user)

    log.info(s"Default repo created with id [${repo.id.get}]")  }
}
