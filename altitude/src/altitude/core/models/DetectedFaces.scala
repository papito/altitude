package altitude.core.models

/**
 * The faces found in an asset, each with its crops, not yet matched to anyone or stored: every detection kept in an image, or one
 * face per cluster of a Video's detections. The import carries them on its element from detection, through the measurement that
 * adds a still image's faces' sharpness, to recognition.
 */
case class DetectedFaces(faces: List[(Face, FaceImages)])
