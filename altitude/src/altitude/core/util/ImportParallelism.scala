package altitude.core.util

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType
import org.slf4j.LoggerFactory

import altitude.core.Const
import altitude.core.models.Settings

/**
 * How many threads do the import's work: what an Admin set, or, at 0, what the machine affords, and never more than the server's
 * cap.
 *
 * The costs are measured importing 100 24-megapixel JPEGs on a 24-core, 31 GB machine with 12 threads, each with its own
 * networks: resident memory peaks at 12.8 to 13.4 GB and 11 GB stays after a full GC, with the heap under 1 GB.
 *
 *   - An ArcFace `Net` holds about 780 MB resident after its first forward pass (174 MB of weights), about 370 MB with Winograd
 *     off. `FaceDetectionService` shares two of them between the threads: `POOL_COST`, at 384 MB each.
 *   - A YuNet `FaceDetectorYN` holds about 70 MB, one per thread for the life of the fixed import dispatcher.
 *   - A 24-megapixel image is 72 MB decoded, and `DetectFacesFlow`, `MeasureImageFlow` and `AddPreviewFlow` each hold up to one
 *     per thread.
 *
 * `THREAD_COST` is a YuNet, those three decodes and the file copy a decode leaves for the finalizer, rounded up.
 */
object ImportParallelism:
  private val logger = LoggerFactory.getLogger(getClass)

  private val MB: Long = 1024L * 1024
  private val GB: Long = 1024 * MB

  val MIN_THREADS = 2
  val POOL_COST: Long = 2 * 384 * MB
  val THREAD_COST: Long = 512 * MB

  /** The running parallelism, and why it is that */
  final case class Resolution(value: Int, reason: String):
    def summary: String = s"$value threads: $reason"

  /**
   * The Setting if there is one (the floor belongs to the automatic rule only), else the automatic rule, then the cap: 0 for
   * none, and an error when under the floor, whatever the Setting.
   */
  def resolve(setting: Int, cores: Int, totalMemoryBytes: Long, cap: Int): Resolution =
    if cap != 0 && cap < MIN_THREADS then
      throw IllegalStateException(s"${Const.Conf.IMPORT_PARALLELISM_MAX} is $cap: must be 0 for no cap or at least $MIN_THREADS")

    val uncapped = if setting > 0 then Resolution(setting, "the Setting") else automatic(cores, totalMemoryBytes)
    if cap > 0 && cap < uncapped.value then Resolution(cap, s"capped by ${Const.Conf.IMPORT_PARALLELISM_MAX}") else uncapped

  /** Half the cores, or as many threads as fit beside the shared nets in half the memory, whichever is fewer */
  private def automatic(cores: Int, totalMemoryBytes: Long): Resolution =
    val memoryBudget = totalMemoryBytes / 2
    val affordable = math.min(cores / 2, (memoryBudget - POOL_COST) / THREAD_COST).toInt
    // Without saying so, the floor would read as a contradiction of the halves on a small box
    val floor = if affordable < MIN_THREADS then s", never fewer than $MIN_THREADS" else ""
    val reason = s"automatic, half of $cores cores or ${memoryBudget / GB} GB of ${totalMemoryBytes / GB} GB$floor"
    Resolution(math.max(affordable, MIN_THREADS), reason)

  /**
   * The cap in the server config, 0 when there is none. To HOCON, the `import.parallelism` Setting and this key are a number and
   * an object at the same path, so a leftover number for the Setting in application.conf replaces the object from reference.conf,
   * and `getInt` would throw. reference.conf promises such a leftover is ignored, so it counts as no cap: `hasPath` is false.
   */
  def configuredCap(config: Config): Int =
    if config.hasPath(Const.Conf.IMPORT_PARALLELISM_MAX) then config.getInt(Const.Conf.IMPORT_PARALLELISM_MAX)
    else
      val isLeftover = config.hasPath(Settings.IMPORT_PARALLELISM) &&
        config.getValue(Settings.IMPORT_PARALLELISM).valueType != ConfigValueType.OBJECT
      if isLeftover then
        logger.warn(
          s"Ignoring ${Settings.IMPORT_PARALLELISM} in the server config, it is a Setting: " +
            s"the key also hides any ${Const.Conf.IMPORT_PARALLELISM_MAX}, so no cap applies. Remove it.")
      0
