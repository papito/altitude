package software.altitude.core.service
import org.slf4j.Logger
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
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: StatDao = app.DAO.stats
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
    logger.debug(s"Adding asset [${asset.id}]")

    txManager.withTransaction {
      if (asset.isTriaged) {
        logger.debug(s"Asset [${asset.id}] moving TO triage. Incrementing TRIAGE")
        app.service.stats.incrementStat(Stats.TRIAGE_ASSETS)
        app.service.stats.incrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)
      } else {
        logger.debug(s"Asset [${asset.id}] moving TO sorted. Incrementing SORTED")

        app.service.stats.incrementStat(Stats.SORTED_ASSETS)
        app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)
      }
    }
  }

  def moveAsset(asset: Asset): Unit = {
    logger.debug(s"Moving asset [${asset.id}]")

    if (asset.isRecycled) {
      logger.debug(s"Asset [${asset.id}] is recycled")
      moveRecycledAsset(asset)
      return
    }

    if (asset.isTriaged) {
      logger.debug(s"Asset [${asset.id}] is triaged")
      moveTriagedAsset(asset)
      return
    }

    logger.debug(s"Asset [${asset.id}] WITHIN sorted.")
  }

  private def moveTriagedAsset(asset: Asset): Unit = {
    logger.debug(s"Moving triaged asset [${asset.id}]. Decrementing TRIAGE")

    app.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
    app.service.stats.decrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)

    logger.debug(s"Triaged asset [${asset.id}] moving TO sorted. Incrementing SORTED")
    app.service.stats.incrementStat(Stats.SORTED_ASSETS)
    app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)
  }

  private def moveRecycledAsset(asset: Asset): Unit = {
    logger.debug(s"Moving recycled asset [${asset.id}]. Decrementing RECYCLED")

    app.service.stats.decrementStat(Stats.RECYCLED_ASSETS)
    app.service.stats.decrementStat(Stats.RECYCLED_BYTES, asset.sizeBytes)

    logger.debug(s"Recycled asset [${asset.id}] moving TO sorted. Incrementing SORTED")
    app.service.stats.incrementStat(Stats.SORTED_ASSETS)
    app.service.stats.incrementStat(Stats.SORTED_BYTES, asset.sizeBytes)
  }

  def recycleAsset(asset: Asset): Unit = {
    logger.debug(s"Recycling asset [${asset.id}]. Incrementing RECYCLED")

    if (asset.isTriaged) {
      logger.debug(s"Asset [${asset.id}] recycled and moving FROM triage. Decrementing TRIAGE")
      app.service.stats.decrementStat(Stats.TRIAGE_ASSETS)
      app.service.stats.decrementStat(Stats.TRIAGE_BYTES, asset.sizeBytes)
    } else {
      logger.debug(s"Asset [${asset.id}] recycled and moving FROM sorted. Decrementing SORTED")
      app.service.stats.decrementStat(Stats.SORTED_ASSETS)
      app.service.stats.decrementStat(Stats.SORTED_BYTES, asset.sizeBytes)
    }

    app.service.stats.incrementStat(Stats.RECYCLED_ASSETS)
    app.service.stats.incrementStat(Stats.RECYCLED_BYTES, asset.sizeBytes)
  }

  def restoreAsset(asset: Asset): Unit = {
    moveRecycledAsset(asset)
  }
}
