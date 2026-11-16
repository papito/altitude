package altitude.core.integration

import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.util.GroupedSearchResult
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult

/**
 * Runs each text search on both paths a Search text can take, which must agree on the assets, their order, the groups and the
 * totals. A probe limit of 0 forces the broad path (the text matched over the whole library); one no test reaches forces the
 * selective path (only the candidates read). A search without text runs once.
 */
trait TextSearchPaths { self: IntegrationTestCore =>

  private val ALWAYS_BROAD = 0
  private val ALWAYS_SELECTIVE = 1000000

  /** Runs the search on both paths, which must agree on `outcome`, and returns the broad one's result */
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
