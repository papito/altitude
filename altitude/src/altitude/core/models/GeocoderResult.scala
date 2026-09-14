package altitude.core.models

/** One place a geocoder search found: its display name and its WGS84 point */
case class GeocoderResult(label: String, latitude: Double, longitude: Double)
