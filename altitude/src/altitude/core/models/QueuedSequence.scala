package altitude.core.models

import java.time.LocalDateTime

/**
 * A Sequence of Curation's queue, read from its members' rows: how many frames it has out of the trash, when the first of them
 * was captured (none for an undated Sequence) and its cover, the first of those frames in capture order, which the Sequence rail
 * shows a Preview of
 */
case class QueuedSequence(sequenceId: String, members: Int, firstCapturedAt: Option[LocalDateTime], coverId: String)
