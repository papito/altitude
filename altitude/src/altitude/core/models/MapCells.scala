package altitude.core.models

/** What the map draws for one viewport: the cells over the plotted points and the Locations pinned in it */
case class MapCells(cells: List[MapCell], locations: List[MapLocation])
