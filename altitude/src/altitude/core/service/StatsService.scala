package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.dao.StatDao
import altitude.core.models.Asset
import altitude.core.models.Stat
import altitude.core.models.Stats
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query

class StatsService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: StatDao = app.DAO.stats
  protected val txManager: TransactionManager = app.txManager

  def getStats: Stats =
    txManager.asReadOnly[Stats] {
      val q: Query = new Query().withRepository()
      val stats: List[Stat] = dao.query(q).records

      // Assemble the total stats on-the-fly
      val totalAssetsDims = Stats.SORTED_ASSETS :: Stats.RECYCLED_ASSETS :: Stats.TRIAGE_ASSETS :: Nil
      val totalAssets = stats.filter(stat => totalAssetsDims.contains(stat.dimension)).map(_.dimVal).sum

      val totalBytesDims = Stats.SORTED_BYTES :: Stats.RECYCLED_BYTES :: Stats.TRIAGE_BYTES :: Nil
      val totalBytes = stats.filter(stat => totalBytesDims.contains(stat.dimension)).map(_.dimVal).sum

      val wTotals = Stat(Stats.TOTAL_ASSETS, totalAssets) :: Stat(Stats.TOTAL_BYTES, totalBytes) :: stats

      Stats(wTotals)
    }

  /**
   * The one write of the stats: the non-zero deltas, by dimension, applied in one transaction in the order of their dimension
   * names. Every write takes the stat rows it changes in that one order, so on PostgreSQL two operations wait for each other
   * instead of deadlocking, and a dimension that does not change is not written.
   */
  def adjust(deltas: Map[String, Long]): Unit =
    txManager.withTransaction {
      deltas.toList.filter(_._2 != 0).sortBy(_._1).foreach {
        (dimension, delta) =>
          logger.trace(s"Stat [$dimension] changes by [$delta]")
          dao.incrementStat(dimension, delta)
      }
    }

  def incrementStat(statName: String, count: Long = 1): Unit =
    adjust(Map(statName -> count))

  def decrementStat(statName: String, count: Long = 1): Unit =
    adjust(Map(statName -> -count))

  def createStat(dimension: String): Stat =
    txManager.withTransaction {
      val stat = Stat(dimension, 0)
      dao.add(stat)
    }

  /**
   * Repairs the context repository's stats from its assets: a stat that differs is logged and set to what the assets are. The
   * stats that were wrong are returned, with the values they had.
   */
  def reconcile(): Map[String, Long] =
    txManager.withTransaction {
      val repository = RequestContext.getRepository.name
      val corrected = dao.reconcile()

      if corrected.isEmpty then logger.debug(s"Stats verified. Repo: $repository")
      else
        val stats = getStats
        corrected.toList.sortBy(_._1).foreach {
          (dimension, old) =>
            logger.warn(s"Stat [$dimension] was $old, corrected to ${stats.getStatValue(dimension)}. Repo: $repository")
        }

      corrected
    }

  /** Counts an asset whose import is complete */
  def addAsset(asset: Asset): Unit =
    logger.trace(s"Counting asset [${asset.id}]")
    transition(before = Nil, after = List(asset))

  /**
   * The stats of assets changing state, in one write: each asset in `before`, as it was read, leaves the dimensions it counts in,
   * and each in `after`, as it is written, joins its own. An import has no `before`, a purge no `after`.
   */
  def transition(before: Seq[Asset], after: Seq[Asset]): Unit =
    val counted = before.map(_ -> -1L) ++ after.map(_ -> 1L)

    val deltas = counted.foldLeft(Map.empty[String, Long].withDefaultValue(0L)) {
      case (deltas, (asset, sign)) =>
        val (assetsDimension, bytesDimension) = dimensionsOf(asset)
        deltas
          .updated(assetsDimension, deltas(assetsDimension) + sign)
          .updated(bytesDimension, deltas(bytesDimension) + sign * asset.sizeBytes)
    }

    adjust(deltas)

  /**
   * The asset and byte dimensions an asset counts in: recycled when recycled, else triage when triaged, else sorted. `StatDao`
   * buckets the asset table the same way when it reconciles.
   */
  private def dimensionsOf(asset: Asset): (String, String) =
    if asset.isRecycled then (Stats.RECYCLED_ASSETS, Stats.RECYCLED_BYTES)
    else if asset.isTriaged then (Stats.TRIAGE_ASSETS, Stats.TRIAGE_BYTES)
    else (Stats.SORTED_ASSETS, Stats.SORTED_BYTES)
