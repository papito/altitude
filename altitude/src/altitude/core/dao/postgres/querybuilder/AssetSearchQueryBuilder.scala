package altitude.core.dao.postgres.querybuilder

import altitude.core.dao.jdbc.querybuilder.ClauseComponents
import altitude.core.dao.jdbc.querybuilder.SearchQueryBuilder
import altitude.core.util.SearchQuery

class AssetSearchQueryBuilder(sqlColsForSelect: List[String]) extends SearchQueryBuilder(selColumnNames = sqlColsForSelect):

  protected def textSearch(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.isText then
      ClauseComponents(elements = List(s"$searchDocumentTable.tsv @@ to_tsquery(?)"), bindVals = List(searchQuery.text.get))
    else ClauseComponents()
