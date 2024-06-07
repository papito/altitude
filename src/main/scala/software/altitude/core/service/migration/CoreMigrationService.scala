package software.altitude.core.service.migration

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.dao.MigrationDao
import software.altitude.core.transactions.AbstractTransactionManager
import software.altitude.core.transactions.TransactionId

import java.io.File
import scala.io.Source

abstract class CoreMigrationService {
  private final val log = LoggerFactory.getLogger(getClass)

  protected val app: AltitudeCoreApp
  protected val DAO: MigrationDao = app.injector.instance[MigrationDao]
  protected val txManager: AbstractTransactionManager = app.injector.instance[AbstractTransactionManager]
  protected val CURRENT_VERSION: Int

  protected val MIGRATIONS_DIR: String
  protected val FILE_EXTENSION: String

  def migrateVersion(ctx: Context, version: Int)(implicit txId: TransactionId = new TransactionId): Unit

  private def runMigration(version: Int)
                          (implicit ctx: Context = new Context(repo = null, user = null),
                   txId: TransactionId = new TransactionId): Unit = {
    val migrationCommands = parseMigrationCommands(version)

    txManager.withTransaction {
      for (command <- migrationCommands) {
        log.info(s"Executing $command")
        DAO.executeCommand(command)
      }
    }

    // must have schema changes committed
    txManager.withTransaction {
      migrateVersion(ctx, version)
      DAO.versionUp()
    }
  }

  def existingVersion(implicit ctx: Context = new Context(repo = null, user = null),
                      txId: TransactionId = new TransactionId): Int = {
    txManager.asReadOnly[Int] {
      DAO.currentVersion
    }
  }

  def migrationRequired(implicit txId: TransactionId = new TransactionId): Boolean = {
    log.info("Checking if migration is required")
    val version = existingVersion
    log.info(s"Current database version is @ $version")
    val isRequired = version < CURRENT_VERSION
    log.info(s"Migration required? : $isRequired")
    isRequired
  }

  def migrate(): Unit = {
    val oldVersion = existingVersion
    log.warn("!!!! MIGRATING !!!!")
    log.info(s"From version $oldVersion to $CURRENT_VERSION")
    for (version <- oldVersion + 1 to CURRENT_VERSION) {
      runMigration(version)
    }
  }

  private def parseMigrationCommands(version: Int): List[String] = {
    log.info(s"RUNNING MIGRATION TO VERSION ^^$version^^")

    val path = new File(MIGRATIONS_DIR, s"$version$FILE_EXTENSION").toString
    log.info(s"PATH: $path")

    val r = getClass.getResource(path)
    val source = Source.fromURL(r)
    val commands = source.mkString.split("--//END").map(_.trim).toList.filter(_.nonEmpty)
    source.close()

    commands
  }

}
