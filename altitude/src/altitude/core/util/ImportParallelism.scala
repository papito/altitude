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
 *     off. `FaceDetectionService` shares `ARCFACE_NETS` of them between the threads: `POOL_COST`, at 384 MB each.
 *   - A YuNet `FaceDetectorYN` holds about 70 MB, one per thread for the life of the fixed import dispatcher.
 *   - A 24-megapixel image is 72 MB decoded. `DetectFacesFlow`, `MeasureImageFlow` and `AddPreviewFlow` each decode an image and
 *     release it inside their work, and no decoded image rides on the element, so a thread holds one at a time, while an image is
 *     decoded three times over its import.
 *
 * `THREAD_COST` is a YuNet, one decode of a large image and the file copy a decode leaves for the finalizer, about 150 MB at 24
 * megapixels, and the rest is a margin left on purpose for a larger image and for native allocator overhead. Imports of generated
 * 24-megapixel JPEGs with 12 threads and the one shared net peak at 3.9 to 5.1 GB resident, the heap included, under the 6.4 GB
 * that `POOL_COST` and `THREAD_COST` budget without it.
 */
object ImportParallelism:
  private val logger = LoggerFactory.getLogger(getClass)

  private val MB: Long = 1024L * 1024
  private val GB: Long = 1024 * MB

  val MIN_THREADS = 2

  /** The ArcFace nets `FaceDetectionService` shares between the threads */
  val ARCFACE_NETS = 1
  val POOL_COST: Long = ARCFACE_NETS * 384 * MB
  val THREAD_COST: Long = 512 * MB

  /** The running parallelism, and why it is that */
  final case class Resolution(value: Int, reason: String):
    def summary: String = s"${counted(value, "thread")}: $reason"

  private def counted(count: Int, noun: String): String = if count == 1 then s"$count $noun" else s"$count ${noun}s"

  /**
   * The Setting if there is one (the floor belongs to the automatic rule only), else the automatic rule, then the cap: 0 for
   * none, and an error when under the floor, whatever the Setting.
   */
  def resolve(setting: Int, cores: Int, totalMemoryBytes: Long, cap: Int): Resolution =
    if cap != 0 && cap < MIN_THREADS then
      throw IllegalStateException(s"${Const.Conf.IMPORT_PARALLELISM_MAX} is $cap: must be 0 for no cap or at least $MIN_THREADS")

    val uncapped = if setting > 0 then Resolution(setting, "the Setting") else automatic(cores, totalMemoryBytes)
    if cap > 0 && cap < uncapped.value then Resolution(cap, s"capped by ${Const.Conf.IMPORT_PARALLELISM_MAX}") else uncapped

  /** Half the cores, or as many threads as fit beside the shared net in half the memory, whichever is fewer */
  private def automatic(cores: Int, totalMemoryBytes: Long): Resolution =
    val memoryBudget = totalMemoryBytes / 2
    val affordable = math.min(cores / 2, (memoryBudget - POOL_COST) / THREAD_COST).toInt
    // Without saying so, the floor would read as a contradiction of the halves on a small box
    val floor = if affordable < MIN_THREADS then s", never fewer than $MIN_THREADS" else ""
    val reason = s"automatic, half of ${counted(cores, "core")} or ${memoryBudget / GB} GB of ${totalMemoryBytes / GB} GB$floor"
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
