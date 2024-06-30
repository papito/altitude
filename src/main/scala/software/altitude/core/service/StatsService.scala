package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.dao.StatDao
import software.altitude.core.models.Asset
import software.altitude.core.models.Stat
import software.altitude.core.models.Stats
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.Query

class StatsService(val app: Altitude) {
  private final val log = LoggerFactory.getLogger(getClass)
  protected val dao: StatDao = app.injector.instance[StatDao]
  protected val txManager: TransactionManager = app.txManager

  def getStats: Stats = {
    txManager.asReadOnly[Stats] {
      val q: Query = new Query().withRepository()
      val stats: List[Stat] = dao.query(q).records.map(Stat.fromJson)

      // Assemble the total stats on-the-fly
      val totalAssetsDims = Stats.SORTED_ASSETS :: Stats.RECYCLED_ASSETS :: Stats.TRIAGE_ASSETS :: Nil
      val totalAssets = stats.filter(stat => totalAssetsDims.contains(stat.dimension)).map(_.dimVal).sum

      val totalBytesDims = Stats.SORTED_BYTES :: Stats.RECYCLED_BYTES :: Stats.TRIAGE_BYTES :: Nil
      val totalBytes = stats.filter(stat => totalBytesDims.contains(stat.dimension)).map(_.dimVal).sum

      val wTotals = Stat(Stats.TOTAL_ASSETS, totalAssets) :: Stat(Stats.TOTAL_BYTES, totalBytes) :: stats

      Stats(wTotals)
    }
  }

  private def incrementStat(statName: String, count: Long = 1): Unit = {
    dao.incrementStat(statName, count)
  }

  private def decrementStat(statName: String, count: Long = 1): Unit = {
    dao.decrementStat(statName, count)
  }

  def createStat(dimension: String): JsObject = {
    txManager.withTransaction {
      val stat = Stat(dimension, 0)
      dao.add(stat)
    }
  }

  def addAsset(asset: Asset): Unit = {
    log.debug(s"Adding asset [${asset.id}]")

    if (asset.isTriaged) {
      log.debug(s"Asset [${asset.id}] moving TO triage. Incrementing TRIAGE")
      app.service.stats.incrementStat(Stats.TRIAGE_ASSETS)
      app.service.stats.incrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)
    } else {
      log.debug(s"Asset [${asset.id}] moving TO sorted. Incrementing SORTED")

      app.service.stats.incrementStat(Stats.SORTED_ASSETS)
      app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)

      log.debug(s"Incrementing folder counter for asset [${asset.id}]")
      app.service.folder.incrAssetCount(asset.folderId)
    }
  }

  def moveAsset(asset: Asset, destFolderId: String): Unit = {
    log.debug(s"Moving asset [${asset.id}]")

    if (asset.isRecycled) {
      log.debug(s"Asset [${asset.id}] is recycled")
      moveRecycledAsset(asset, destFolderId)
      return
    }

    if (asset.isTriaged) {
      log.debug(s"Asset [${asset.id}] is triaged")
      moveTriagedAsset(asset, destFolderId)
      return
    }

    log.debug(s"Asset [${asset.id}] WITHIN sorted.")

    log.debug(s"Decrementing old folder counter for asset [${asset.id}]")
    app.service.folder.decrAssetCount(asset.folderId)
    log.debug(s"Incrementing new folder counter for asset [${asset.id}]")
    app.service.folder.incrAssetCount(destFolderId)
  }

  private def moveTriagedAsset(asset: Asset, destFolderId: String): Unit = {
    log.debug(s"Moving triaged asset [${asset.id}]. Decrementing TRIAGE")

    app.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
    app.service.stats.decrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)

    log.debug(s"Triaged asset [${asset.id}] moving TO sorted. Incrementing SORTED")
    app.service.stats.incrementStat(Stats.SORTED_ASSETS)
    app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)

    log.debug(s"Incrementing folder counter for asset [${asset.id}]")
    app.service.folder.incrAssetCount(destFolderId)
  }

  private def moveRecycledAsset(asset: Asset, destFolderId: String): Unit = {
    log.debug(s"Moving recycled asset [${asset.id}]. Decrementing RECYCLED")

    app.service.stats.decrementStat(Stats.RECYCLED_ASSETS)
    app.service.stats.decrementStat(Stats.RECYCLED_BYTES, asset.sizeBytes)

    log.debug(s"Recycled asset [${asset.id}] moving TO sorted. Incrementing SORTED")
    app.service.stats.incrementStat(Stats.SORTED_ASSETS)
    app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)

    log.debug(s"Incrementing folder counter for asset [${asset.id}]")
    app.service.folder.incrAssetCount(destFolderId)
  }

  def recycleAsset(asset: Asset): Unit = {
    log.debug(s"Recycling asset [${asset.id}]. Incrementing RECYCLED")

    if (asset.isTriaged) {
      log.debug(s"Asset [${asset.id}] recycled and moving FROM triage. Decrementing TRIAGE")
      app.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
      app.service.stats.decrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)
    } else {
      log.debug(s"Asset [${asset.id}] recycled and moving FROM sorted. Decrementing SORTED")
      app.service.stats.decrementStat(Stats.SORTED_ASSETS)
      app.service.stats.decrementStat(Stats.SORTED_BYTES, asset.sizeBytes)
    }

    app.service.stats.incrementStat(Stats.RECYCLED_ASSETS)
    app.service.stats.incrementStat(Stats.RECYCLED_BYTES, asset.sizeBytes)

    log.debug(s"Decrementing folder counter for recycled asset [${asset.id}]")
    app.service.folder.decrAssetCount(asset.folderId)
  }

  def restoreAsset(asset: Asset): Unit = {
    moveRecycledAsset(asset, asset.folderId)
  }
}
