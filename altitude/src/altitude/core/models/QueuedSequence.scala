package altitude.core.models

import java.time.LocalDateTime

/**
 * A Sequence in Curation's queue: its frames out of the trash, the first capture time (none when undated) and its cover, the
 * first frame in capture order
 */
case class QueuedSequence(sequenceId: String, members: Int, firstCapturedAt: Option[LocalDateTime], coverId: String)
