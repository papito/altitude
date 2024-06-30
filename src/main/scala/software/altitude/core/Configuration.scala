package software.altitude.core

import software.altitude.core.{Const => C}

import scala.collection.immutable.HashMap

class Configuration(configOverride: Map[String, Any] = new HashMap()) {
  def getString(key: String): String = data.getOrElse(key, "").asInstanceOf[String]
  def getFlag(key: String): Boolean = data.getOrElse(key, false).asInstanceOf[Boolean]
  def getInt(key: String): Int = data.getOrElse(key, 0).asInstanceOf[Int]
  def datasourceType: C.DatasourceType.Value = data("datasource").asInstanceOf[C.DatasourceType.Value]
  def fileStoreType: C.FileStoreType.Value = data("filestore").asInstanceOf[C.FileStoreType.Value]

  private val default = HashMap(
    "app.name" -> "Altitude",
    "dataDir" -> "data",
    "previewDir" -> "p",

    "datasource" -> C.DatasourceType.POSTGRES,
    "filestore" -> C.FileStoreType.FS,
    "importMode" -> C.ImportMode.COPY,

    "preview.box.pixels" -> 200,

    // safeguard for maximum records allowed at once without pagination
    "db.max_records" -> 1000,
    "db.postgres.user" -> "altitude",
    "db.postgres.password" -> "dba",
    "db.postgres.url" -> "jdbc:postgresql://localhost/altitude",

    "db.sqlite.url" -> "jdbc:sqlite:data/db/altitude.db"
  )

  private val test = default ++ HashMap(
    "testDir" -> "./test-data",
    "dataDir" -> "./test-data/file-store",
    "importMode" -> C.ImportMode.COPY,
    "filestore" -> C.FileStoreType.FS,

    "db.postgres.url" -> "jdbc:postgresql://localhost:5433/altitude-test",
    "db.postgres.user" -> "altitude-test",

    "db.sqlite.url" -> "jdbc:sqlite:./test-data/test.sqlite.db"
  )

  private val prod = default ++ HashMap()

  val data: Map[String, Any] = Environment.ENV match {
    case Environment.TEST => test ++ configOverride
    case Environment.DEV => default ++ configOverride
    case Environment.PROD => prod ++ configOverride
  }
}
