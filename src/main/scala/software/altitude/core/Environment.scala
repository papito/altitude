package software.altitude.core

import java.io.File

object Environment extends Enumeration {
  val TEST, PROD, DEV = Value
  var ENV: Environment.Value = System.getenv().getOrDefault("ENV", "DEV") match {
    case "test" => TEST
    case "prod" => PROD
    case _ => DEV
  }

  def root: String = ENV match {
    case PROD =>
      val url = Environment.getClass.getProtectionDomain.getCodeSource.getLocation
      val path = new File(url.toURI).getParentFile.getPath
      File.separator + path + File.separator
    case _ => ""
  }
}
