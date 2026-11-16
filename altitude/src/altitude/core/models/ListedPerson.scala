package altitude.core.models

/**
 * A Person on the People tab with the count it shows: what clicking that Person finds. It differs from `Person.numOfFaces`, the
 * count over every asset outside the trash.
 */
case class ListedPerson(person: Person, visibleCount: Int)

/**
 * The assets out of the trash that a listed Person's count covers: sorted ones (triaged items hidden), triaged ones (the Triage
 * view), or both
 */
enum CountedAssets:
  case Sorted, Triaged, All

/**
 * A pane of the People tab. `leftOffByTriage` is whether the list came out empty because everyone in the buckets has only triaged
 * assets while those are hidden.
 */
case class ListedPeople(people: List[ListedPerson], leftOffByTriage: Boolean)
