// src/main/scala/software/altitude/core/Validators.scala
package altitude.core

import scala.util.matching.Regex

import altitude.core.{ Const => C }
import altitude.core.util.NameKey

object Validators:
  private val emailRegex: Regex =
    """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  private val uuidRegex: Regex = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r

  def isUuid(value: String): Boolean = uuidRegex.matches(value)

  /**
   * A name as it is stored ([[NameKey.normalized]]) once it passes its length rule, which is checked after normalization, since
   * NFC can lengthen a name (U+FB2C is three code points in NFC): `minLength` to `maxLength` characters, counted in code points
   * as the name column counts them, so an emoji is one, and a name key within [[NameKey.MAX_KEY_BYTES]]. Otherwise a
   * `ValidationException` that says why, as its message and as the error of the name's field (`field`), the request validator's
   * error for a length. A form's own length check counts what was typed, before normalization; this is the one the stored name is
   * held to.
   */
  def validName(name: String, field: String, maxLength: Int, minLength: Int = 0): String =
    val normalized = NameKey.normalized(name)
    val length = normalized.codePointCount(0, normalized.length)

    val error: Option[String] =
      if length < minLength then Some(C.Msg.Err.VALUE_TOO_SHORT.format(minLength))
      else if length > maxLength then Some(C.Msg.Err.VALUE_TOO_LONG.format(maxLength))
      else if !NameKey.keyFits(normalized) then Some(C.Msg.Err.NAME_KEY_TOO_LONG)
      else None

    error.foreach {
      message =>
        val ex = ValidationException(message)
        ex.errors += (field -> message)
        throw ex
    }
    normalized

  case class ApiRequestValidator(
      required: List[String] = List(),
      maxLengths: Map[String, Int] = Map.empty,
      minLengths: Map[String, Int] = Map.empty,
      email: List[String] = List.empty,
      uuid: List[String] = List.empty):

    def validate(json: ujson.Obj): Unit =
      val ex: ValidationException = ValidationException()

      required.foreach {
        field =>
          if !json.obj.contains(field) || json(field).strOpt.forall(_.isEmpty) then
            ex.errors += (field -> C.Msg.Err.VALUE_REQUIRED)
      }

      maxLengths.foreach {
        case (field, maxLength) =>
          if json.obj.contains(field) && json(field).strOpt.exists(_.length > maxLength) then
            ex.errors += (field -> C.Msg.Err.VALUE_TOO_LONG.format(maxLength))
      }

      minLengths.foreach {
        case (field, minLength) =>
          if json.obj.contains(field) && json(field).strOpt.exists(_.length < minLength) then
            ex.errors += (field -> C.Msg.Err.VALUE_TOO_SHORT.format(minLength))
      }

      email.foreach {
        field =>
          if isStillValid(ex, field, json) && json.obj.contains(field) &&
            !emailRegex.matches(json(field).str)
          then ex.errors += (field -> C.Msg.Err.VALUE_NOT_AN_EMAIL)
      }

      uuid.foreach {
        field =>
          if isStillValid(ex, field, json) && !isUuid(json(field).str) then ex.errors += (field -> C.Msg.Err.VALUE_NOT_A_UUID)
      }

      ex.trigger()

    private def isStillValid(ex: ValidationException, field: String, json: ujson.Obj): Boolean =
      !ex.errors.contains(field) && json.obj.contains(field)
