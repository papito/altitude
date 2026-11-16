package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

trait FaceDao extends BaseDao[Face]:
  def add(face: Face, asset: Asset, person: Person): Face
  def getAssetFaces(assetId: String): List[Face]

  /** In no order, leaving out hidden and bad-match people's as `getAssetFaces` does */
  def getFacesOfAssets(assetIds: Set[String]): List[Face]

  /** Every Face of an asset, those of hidden and bad-match people too, which `getAssetFaces` leaves out */
  def getAllAssetFaces(assetId: String): List[Face]

  def hasFaceOfPerson(assetId: String, personId: String): Boolean

  /** Either person's Faces on the Videos where both have one, in no order: a merge keeps one Face per Person per Video */
  def getFacesOnSharedVideos(personId: String, otherPersonId: String): List[Face]

  /** Best detection score first, then by ID. A Face on an asset marked for purging is left out: it can no longer be a cover. */
  def getTopFaces(personId: String, limit: Int): List[Face]

  /** The cover a person takes when Faces of theirs go: the first of [[getTopFaces]] on none of `excludedAssetIds` */
  def getBestCoverCandidate(personId: String, excludedAssetIds: Set[String]): Option[Face]

  /** Enrolled Faces closest first, under the cosine distance `distanceThreshold`, none of a bad-match person */
  def searchClosestFaceMatches(features: Array[Float], matchCount: Int, distanceThreshold: Double): List[Face]

  /** The Face's vector, from the engine's vector store: the model never carries it */
  def getFeatures(faceId: String): Option[Array[Float]]

  /** As [[searchClosestFaceMatches]], leaving out `excludedPersonId`'s Faces */
  def searchNearestFacesOfOthers(
      features: Array[Float],
      excludedPersonId: String,
      limit: Int,
      distanceThreshold: Double): List[Face]
