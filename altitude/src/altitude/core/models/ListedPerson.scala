package altitude.core.models

/**
 * A Person on the People tab, with the number the tab shows for them: what clicking that Person finds. It stands apart from
 * `Person.numOfFaces`, the count over every asset outside the trash, which `Person.isMatched` and the People buckets read.
 */
case class ListedPerson(person: Person, visibleCount: Int)

/**
 * A pane of the People tab: the people it lists, and whether its buckets hold people none of whom is listed, every one of them
 * having only triaged assets while triaged items are hidden, so that its empty line can say so.
 */
case class ListedPeople(people: List[ListedPerson], leftOffByTriage: Boolean)
