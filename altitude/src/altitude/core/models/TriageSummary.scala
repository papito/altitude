package altitude.core.models

/**
 * The counts of the Triage summary: the assets in triage (the Triage view, so none in the trash), how many of them have a Face of
 * an Unknown person, and how many no Location points at
 */
case class TriageSummary(total: Int, unknownPeople: Int, noLocation: Int)
