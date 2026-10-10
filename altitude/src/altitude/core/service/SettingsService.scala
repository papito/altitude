package altitude.core.service

import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.ValidationException
import altitude.core.dao.SettingDao
import altitude.core.models.SettingDef
import altitude.core.models.SettingKind
import altitude.core.models.Settings
import altitude.core.transactions.TransactionManager

/** A Restart setting whose saved value is not the one the server runs with */
case class PendingRestart(setting: SettingDef, saved: String, running: String)

/**
 * The Settings as one immutable typed snapshot ([[Settings]]), replaced whole on every load and save, so a reader never sees a
 * half-written set. The snapshot loaded first, at startup, is the running value of a Restart setting. Loads and saves take turns,
 * so the table and the snapshot agree; readers never wait.
 */
class SettingsService(app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  // Lazy: this service is constructed before the transaction manager, which reads the loaded Settings for its transactions
  protected lazy val txManager: TransactionManager = app.txManager
  private lazy val settingDao: SettingDao = app.DAO.setting

  private val snapshot = new AtomicReference[Option[Settings]](None)
  private val startup = new AtomicReference[Option[Settings]](None)

  /** The current snapshot, or nothing before the first load, which the migration of a fresh database runs ahead of */
  def currentIfLoaded: Option[Settings] = snapshot.get

  def current: Settings = currentIfLoaded.getOrElse(throw IllegalStateException("The settings are not loaded"))

  def atStartup: Settings = startup.get.getOrElse(throw IllegalStateException("The settings are not loaded"))

  /**
   * Reads the registry's Settings from the database into a new snapshot; the first load is the one the server starts with. A row
   * the registry does not know is left alone, so a Setting that is retired does not keep the server from starting.
   */
  def load(): Unit = synchronized {
    val rows = txManager.asReadOnly {
      settingDao.getAll
    }
    val (known, unknown) = rows.partition(row => Settings.byName.contains(row.name))
    unknown.foreach(row => logger.warn(s"Ignoring a stored setting the registry does not know: ${row.name}"))

    val values = known.map(row => row.name -> row.value).toMap
    val missing = Settings.all.map(_.name).filterNot(values.contains)
    if missing.nonEmpty then throw IllegalStateException(s"Settings missing from the database: ${missing.mkString(", ")}")
    val errors = Settings.validate(values)
    if errors.nonEmpty then throw IllegalStateException(s"Settings stored with invalid values: $errors")

    val loaded = Settings(values, known.map(row => row.name -> row.defaultValue).toMap)
    snapshot.set(Some(loaded))
    startup.compareAndSet(None, Some(loaded))
    logger.info("Settings loaded")
  }

  /** The errors of `values` over the current set, by name: a Setting not in `values` keeps its current value */
  def validate(values: Map[String, String]): Map[String, String] = Settings.validate(merged(current, trimmed(values)))

  /**
   * Saves `values` over the current set, in one transaction, and replaces the snapshot. When any field fails, the
   * [[ValidationException]] names every failing field and nothing is written. A value that spells the current one differently
   * ("0.850" for "0.85") is not a change.
   */
  def save(values: Map[String, String]): Unit = synchronized {
    val before = current
    val submitted = trimmed(values)
    val merged = this.merged(before, submitted)

    val errors = Settings.validate(merged)
    if errors.nonEmpty then
      val ex = ValidationException()
      ex.errors ++= errors
      throw ex

    val changed = submitted.filter((name, value) => !Settings.byName(name).valueType.same(before.value(name), value))
    if changed.nonEmpty then
      txManager.withTransaction {
        settingDao.updateValues(changed)
      }
      changed.foreach((name, value) => logger.info(s"Setting [$name] changed from [${before.value(name)}] to [$value]"))
      snapshot.set(Some(before.copy(text = merged)))
  }

  /** The Restart settings whose saved value differs from the running one, loaded at startup */
  def pendingRestart: List[PendingRestart] =
    Settings.all
      .filter(_.kind == SettingKind.Restart)
      .flatMap {
        setting =>
          val saved = current.value(setting.name)
          val running = atStartup.value(setting.name)
          Option.unless(setting.valueType.same(saved, running))(PendingRestart(setting, saved, running))
      }

  // Whitespace around a submitted value is never meant
  private def trimmed(values: Map[String, String]): Map[String, String] = values.map((name, value) => name -> value.trim)

  /** The current set with `values` over it, so a Setting that was not submitted keeps its value */
  private def merged(current: Settings, values: Map[String, String]): Map[String, String] =
    Settings.all.map(d => d.name -> current.value(d.name)).toMap ++ values
