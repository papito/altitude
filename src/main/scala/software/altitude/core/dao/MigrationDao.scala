package software.altitude.core.dao

trait MigrationDao {
  def currentVersion: Int
  def executeCommand(command: String): Unit
}
