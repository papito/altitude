package software.altitude.core.controllers

import software.altitude.core.{Const => C}

object Util {
  def parseFolderIds(folderIds: String): Set[String] = {
    if (folderIds.isEmpty) {
      Set[String]()
    }
    else {
      folderIds
        .split(s"\\${C.Api.MULTI_VALUE_DELIM}")
        .map(_.trim)
        .filter(_.nonEmpty).toSet
    }
  }

}
