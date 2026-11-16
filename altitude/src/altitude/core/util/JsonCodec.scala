package altitude.core.util

import java.time.LocalDateTime
import ujson._
import upickle.default._

/** Project-wide upickle bundle that maps camelCase fields to snake_case keys. Import `JsonCodec.{given, *}`. */
object JsonCodec extends upickle.AttributeTagged:

  private def camelToSnake(s: String): String =
    "[A-Z]".r.replaceAllIn(s, m => "_" + m.group(0).toLowerCase)

  private def snakeToCamel(s: String): String =
    "_([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase)

  override def objectAttributeKeyWriteMap(s: CharSequence): CharSequence =
    camelToSnake(s.toString)

  override def objectAttributeKeyReadMap(s: CharSequence): CharSequence =
    snakeToCamel(s.toString)

  given ReadWriter[LocalDateTime] = readwriter[String].bimap(
    _.toString,
    LocalDateTime.parse(_)
  )

  // A JSON object, not an array of pairs
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

    /** A new Obj (each of these leaves `obj` unchanged); the right side wins on duplicate keys */
    def ++(other: ujson.Obj): ujson.Obj =
      val result = ujson.Obj()
      obj.value.foreach { case (k, v) => result(k) = v }
      other.value.foreach { case (k, v) => result(k) = v }
      result

    def -(key: String): ujson.Obj =
      val result = ujson.Obj()
      obj.value.filterNot(_._1 == key).foreach { case (k, v) => result(k) = v }
      result

    def --(keys: String*): ujson.Obj =
      val keySet = keys.toSet
      val result = ujson.Obj()
      obj.value.filterNot(kv => keySet.contains(kv._1)).foreach { case (k, v) => result(k) = v }
      result
