package altitude.core.models

/** The assets in triage (none in the trash), how many have a Face of an Unknown person, and how many have no Location */
case class TriageSummary(total: Int, unknownPeople: Int, noLocation: Int)
