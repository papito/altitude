package altitude.core.util

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType
import org.slf4j.LoggerFactory

import altitude.core.Const
import altitude.core.models.Settings

/**
 * How many threads do the import's work: the Setting or, at 0, what the machine affords, never more than the server's cap.
 *
 * Measured importing 100 24-megapixel JPEGs on a 24-core, 31 GB machine with 12 threads, each with its own networks: resident
 * memory peaks at 12.8 to 13.4 GB and 11 GB stays after a full GC, with the heap under 1 GB.
 *
 *   - An ArcFace `Net` holds about 780 MB resident after its first pass (174 MB of weights), about 370 MB with Winograd off. The
 *     threads share `ARCFACE_NETS` of them, budgeted at 384 MB each.
 *   - A YuNet `FaceDetectorYN` holds about 70 MB, one per thread.
 *   - A 24-megapixel image is 72 MB decoded. Each of the three image flows decodes it and releases it inside its work, so a
 *     thread holds one at a time.
 *   - The two Scene models' sessions, shared by every thread, add about 1,110 MB resident once opened (about 550 MB each) and
 *     about 50 MB after their first passes: ONNX Runtime pre-packs the 718 MB of weights and keeps both copies
 *     (`session.disable_prepacking` saves about 300 MB for passes 8 to 10% slower). A pass on one thread takes about 370 ms
 *     (DINOv2) and 375 ms (SigLIP 2), at the core's AVX2 limit, so graph optimizations change it under 10%. Fitting the image
 *     adds about 50 ms a model, so an image costs its thread about 0.85 s. With 12 threads an image takes about 1.4 s, about 8
 *     images a second, and the passes' working memory adds about 50 MB a thread.
 *
 * `THREAD_COST` covers a YuNet, one large decode and the file copy it leaves for the finalizer (about 150 MB at 24 megapixels),
 * the Scene passes' working memory, and a deliberate margin for larger images and native allocator overhead. Before the Scene
 * models, 12 threads with the one shared net peaked at 3.9 to 5.1 GB resident, heap included, under the 6.4 GB that `POOL_COST`
 * and `THREAD_COST` budgeted.
 */
object ImportParallelism:
  private val logger = LoggerFactory.getLogger(getClass)

  private val MB: Long = 1024L * 1024
  private val GB: Long = 1024 * MB

  val MIN_THREADS = 2

  /** The ArcFace nets `FaceDetectionService` shares between the threads */
  val ARCFACE_NETS = 1
  val POOL_COST: Long = ARCFACE_NETS * 384 * MB

  /** The two Scene models' sessions, shared by the threads */
  val SCENE_MODELS_COST: Long = 1216 * MB

  /** What the import costs once, however many threads do its work */
  val FIXED_COST: Long = POOL_COST + SCENE_MODELS_COST
  val THREAD_COST: Long = 512 * MB

  final case class Resolution(value: Int, reason: String):
    def summary: String = s"${counted(value, "thread")}: $reason"

  private def counted(count: Int, noun: String): String = if count == 1 then s"$count $noun" else s"$count ${noun}s"

  /**
   * The Setting if set, else the automatic rule (the only one the floor applies to), then the cap: 0 for none, and an error when
   * under the floor, whatever the Setting.
   */
  def resolve(setting: Int, cores: Int, totalMemoryBytes: Long, cap: Int): Resolution =
    if cap != 0 && cap < MIN_THREADS then
      throw IllegalStateException(s"${Const.Conf.IMPORT_PARALLELISM_MAX} is $cap: must be 0 for no cap or at least $MIN_THREADS")

    val uncapped = if setting > 0 then Resolution(setting, "the Setting") else automatic(cores, totalMemoryBytes)
    if cap > 0 && cap < uncapped.value then Resolution(cap, s"capped by ${Const.Conf.IMPORT_PARALLELISM_MAX}") else uncapped

  /** Half the cores, or as many threads as fit beside the shared models in half the memory, whichever is fewer */
  private def automatic(cores: Int, totalMemoryBytes: Long): Resolution =
    val memoryBudget = totalMemoryBytes / 2
    val affordable = math.min(cores / 2, (memoryBudget - FIXED_COST) / THREAD_COST).toInt
    // Without saying so, the floor would read as a contradiction of the halves on a small box
    val floor = if affordable < MIN_THREADS then s", never fewer than $MIN_THREADS" else ""
    val reason = s"automatic, half of ${counted(cores, "core")} or ${memoryBudget / GB} GB of ${totalMemoryBytes / GB} GB$floor"
    Resolution(math.max(affordable, MIN_THREADS), reason)

  /**
   * 0 when there is none. To HOCON the `import.parallelism` Setting and this key are a number and an object at one path, so a
   * leftover number for the Setting in application.conf replaces reference.conf's object and hides the cap. reference.conf
   * promises such a leftover is ignored, so it counts as no cap.
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
