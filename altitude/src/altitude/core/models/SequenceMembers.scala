package altitude.core.models

import java.time.LocalDateTime

/** What a Sequence holds, counted from its members' rows: how many there are and when the first of them was captured */
case class SequenceMembers(sequenceId: String, members: Int, firstCapturedAt: Option[LocalDateTime])
