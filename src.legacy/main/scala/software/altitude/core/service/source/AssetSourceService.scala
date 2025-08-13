package software.altitude.core.service.source

import software.altitude.core.models.ImportAsset

trait AssetSourceService {
  def assetIterator(path: String): Iterator[ImportAsset]
  def count(path: String): Int
}
