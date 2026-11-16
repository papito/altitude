package altitude.core.models

/**
 * The faces found in an asset, each with its crops, not yet matched or stored: every detection kept in an image, or one face per
 * cluster of a Video's detections
 */
case class DetectedFaces(faces: List[(Face, FaceImages)])
