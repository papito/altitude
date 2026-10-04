package altitude.core.integration

import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.util.GroupedSearchResult
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

/**
 * Text searches run on both paths a Search text can take, which must agree on the assets, their order, the groups and the totals.
 * Under a probe limit of 0 every positive group with a hit is too broad to be answered from its hits, so the text is matched over
 * the whole library; under one no test reaches, every positive group is complete, and the search reads its candidates alone. A
 * search without text takes neither path and runs once.
 */
trait TextSearchPaths { self: IntegrationTestCore =>

  private val ALWAYS_BROAD = 0
  private val ALWAYS_SELECTIVE = 1000000

  /** The search run on the broad path and on the selective one, which must agree on `outcome`; the result of the broad one */
  protected def onBothPaths[T](query: SearchQuery)(run: SearchQuery => T)(outcome: T => Any): T =
    if (!query.isText) run(query)
    else {
      val broad = run(query.withTextProbeLimit(ALWAYS_BROAD))
      val selective = run(query.withTextProbeLimit(ALWAYS_SELECTIVE))
      withClue(s"The selective path disagrees with the broad one for $query: ")(outcome(selective) shouldBe outcome(broad))
      broad
    }

  protected def search(query: SearchQuery): SearchResult =
    onBothPaths(query)(testApp.service.library.search)(
      result => (result.records.map(_.persistedId), result.total, result.nextCursor))

  protected def searchGrouped(query: SearchQuery): GroupedSearchResult =
    onBothPaths(query)(testApp.service.library.searchGrouped) {
      result =>
        (
          result.groups.map(group => (group.key, group.total, group.assets.map(_.persistedId))),
          result.total,
          result.nextCursor,
          result.continuesGroup)
    }

  protected def count(query: SearchQuery): Int = onBothPaths(query)(testApp.service.library.count)(identity)
}
