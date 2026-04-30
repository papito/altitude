package altitude.core.util

import ujson.*
import upickle.default.*

import java.time.LocalDateTime

/**
 * Project-wide upickle bundle with automatic camelCase → snake_case key mapping.
 * Import `JsonCodec.{given, *}` to bring all codecs and extensions into scope.
 */
object JsonCodec extends upickle.AttributeTagged:

  private def camelToSnake(s: String): String =
    "[A-Z]".r.replaceAllIn(s, m => "_" + m.group(0).toLowerCase)

  private def snakeToCamel(s: String): String =
    "_([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase)

  // Write Scala field names as snake_case JSON keys
  override def objectAttributeKeyWriteMap(s: CharSequence): CharSequence =
    camelToSnake(s.toString)

  // Read JSON snake_case keys back to camelCase to match Scala field names
  override def objectAttributeKeyReadMap(s: CharSequence): CharSequence =
    snakeToCamel(s.toString)

  // LocalDateTime ↔ ISO string
  given ReadWriter[LocalDateTime] = readwriter[String].bimap(
    _.toString,
    LocalDateTime.parse(_)
  )

  // Map[String, String] ↔ JSON object (not array-of-pairs)
  given ReadWriter[Map[String, String]] = readwriter[ujson.Value].bimap(
    (m: Map[String, String]) => {
      val result = ujson.Obj()
      m.foreach { case (k, v) => result(k) = ujson.Str(v) }
      result
    },
    (v: ujson.Value) => v.obj.map { case (k, v2) => k -> v2.str }.toMap
  )

  // ── ujson.Obj convenience extensions ──────────────────────────────────────

  extension (obj: ujson.Obj)

    /** Merge two Obj instances, right-side wins on duplicate keys. */
    def ++(other: ujson.Obj): ujson.Obj =
      val result = ujson.Obj()
      obj.value.foreach { case (k, v) => result(k) = v }
      other.value.foreach { case (k, v) => result(k) = v }
      result

    /** Return a new Obj with key removed. */
    def -(key: String): ujson.Obj =
      val result = ujson.Obj()
      obj.value.filterNot(_._1 == key).foreach { case (k, v) => result(k) = v }
      result

    /** Return a new Obj with all listed keys removed. */
    def --(keys: String*): ujson.Obj =
      val keySet = keys.toSet
      val result = ujson.Obj()
      obj.value.filterNot(kv => keySet.contains(kv._1)).foreach { case (k, v) => result(k) = v }
      result




