package altitude.core.models

/**
 * What the Detail panel of Asset detail shows of an asset, read in one snapshot (`AssetService.detailOf`): the asset, its Faces
 * with their people as the People section lists them (bad matches left out, in reading order or by Frame time), the folders from
 * the top down to the asset's own (none for the root or for triage), its albums, its Locations, and the size of the Curation set
 * of its Sequence, when it has one to open (`CurationService.setSizeOf`)
 */
case class DetailPanelData(
    asset: Asset,
    faces: List[(Face, Person)],
    folderPath: List[Folder],
    albums: List[Album],
    locations: List[Location],
    curationSetSize: Option[Int])
