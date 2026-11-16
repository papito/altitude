package altitude.core.models

/**
 * What the Detail panel shows of an asset, read in one snapshot. `faces` leaves out bad matches; `folderPath` runs from the top
 * down to the asset's own folder (empty for the root or triage); `curationSetSize` is set when its Sequence has a Curation set.
 */
case class DetailPanelData(
    asset: Asset,
    faces: List[(Face, Person)],
    folderPath: List[Folder],
    albums: List[Album],
    locations: List[Location],
    curationSetSize: Option[Int])
