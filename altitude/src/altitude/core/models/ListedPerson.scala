package altitude.core.models

/**
 * A Person on the People tab, with the number the tab shows for them: what clicking that Person finds. It stands apart from
 * `Person.numOfFaces`, the count over every asset outside the trash, which `Person.isMatched` and the People buckets read.
 */
case class ListedPerson(person: Person, visibleCount: Int)
