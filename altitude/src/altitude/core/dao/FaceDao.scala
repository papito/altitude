package altitude.core.dao

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person

trait FaceDao extends BaseDao[Face]:
  def add(face: Face, asset: Asset, person: Person): Face
  def getAssetFaces(assetId: String): List[Face]

  /** The Faces of the assets, those of hidden and bad-match people left out as `getAssetFaces` leaves them out, in no order */
  def getFacesOfAssets(assetIds: Set[String]): List[Face]

  /** Every Face of an asset, whoever its person is: those of hidden and bad-match people too, which `getAssetFaces` leaves out */
  def getAllAssetFaces(assetId: String): List[Face]

  /** Whether the person has a Face on the asset, in the context repository */
  def hasFaceOfPerson(assetId: String, personId: String): Boolean

  /**
   * The Faces of either person on the Videos where both have a Face, in the context repository, in no order: what a merge of the
   * two chooses between to keep one Face per Person per Video
   */
  def getFacesOnSharedVideos(personId: String, otherPersonId: String): List[Face]

  /** The Face by ID if it is in the context repository; none for a Face gone or of another repository */
  def getInRepository(faceId: String): Option[Face]

  /**
   * A person's faces by detection score, best first and then by ID, no more than `limit` of them, in the context repository: none
   * for another repository's person. A Face on an asset marked for purging is left out, since it can no longer be a cover
   */
  def getTopFaces(personId: String, limit: Int): List[Face]

  /**
   * The cover a person takes when Faces of theirs go: their Face with the highest detection score, then by ID as in
   * [[getTopFaces]], on none of `excludedAssetIds` and on no asset marked for purging, in the context repository; none when no
   * such Face is left
   */
  def getBestCoverCandidate(personId: String, excludedAssetIds: Set[String]): Option[Face]

  /**
   * The enrolled Faces of the repository nearest to `features`, closest first: no more than `matchCount`, none at a cosine
   * distance of `distanceThreshold` or more, and none of a bad-match person
   */
  def searchClosestFaceMatches(features: Array[Float], matchCount: Int, distanceThreshold: Double): List[Face]

  /**
   * The Face's vector, read from the engine's vector store since the model never carries it; none for a Face gone or of another
   * repository
   */
  def getFeatures(faceId: String): Option[Array[Float]]

  /**
   * The nearest Faces as `searchClosestFaceMatches` finds them, of people other than `excludedPersonId`: closest first, no more
   * than `limit`, none at a cosine distance of `distanceThreshold` or more, enrolled, of no bad-match person, in the context
   * repository. The Change combobox's Suggested group reads it for a Face, whose own Person is the one left out.
   */
  def searchNearestFacesOfOthers(
      features: Array[Float],
      excludedPersonId: String,
      limit: Int,
      distanceThreshold: Double): List[Face]
