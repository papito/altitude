package altitude.core.models

/**
 * What the Detail panel of Asset detail shows of an asset, read in one snapshot (`AssetService.detailOf`): the asset, its Faces
 * with their people as the People section lists them (bad matches left out, in reading order or by Frame time), the folders from
 * the top down to the asset's own (none for the root or for triage), its albums, its Locations, and the frames of its Sequence,
 * when it has one
 */
case class DetailPanelData(
    asset: Asset,
    faces: List[(Face, Person)],
    folderPath: List[Folder],
    albums: List[Album],
    locations: List[Location],
    sequenceFrames: Option[Int])
