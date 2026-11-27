package altitude.core.models

import java.time.LocalDateTime

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.NameKey
import altitude.core.util.Util

object Repository:
  given JsonCodec.ReadWriter[Repository] = JsonCodec.macroRW
  given Conversion[ujson.Value, Repository] = json => JsonCodec.read[Repository](json)

  // Unicode-aware, so a no-break space collapses like a space
  private val WHITESPACE = "(?U)\\s+".r

  /** Trimmed, whitespace runs collapsed to one space, and in NFC so a combining accent and its precomposed form are one name */
  def normalizedName(name: String): String =
    NameKey.normalized(WHITESPACE.split(name).filter(_.nonEmpty).mkString(" "))

  /**
   * The Library name rule: the normalized name, or a `ValidationException` on the name field with the first rule broken. Length
   * is counted in code points, so an emoji is one.
   */
  def validName(name: String): String =
    val normalized = normalizedName(name)
    val length = normalized.codePointCount(0, normalized.length)
    val (min, max) = (Api.Constraints.MIN_REPOSITORY_NAME_LENGTH, Api.Constraints.MAX_REPOSITORY_NAME_LENGTH)

    val error: Option[String] =
      if normalized.isEmpty then Some(C.Msg.Err.LIBRARY_NAME_REQUIRED)
      else if Util.holdsControlCharacter(normalized) then Some(C.Msg.Err.CONTROL_CHARACTERS)
      else if length < min then Some(C.Msg.Err.LIBRARY_NAME_TOO_SHORT.format(min))
      else if length > max then Some(C.Msg.Err.LIBRARY_NAME_TOO_LONG.format(max))
      else if !NameKey.keyFits(normalized) then Some(C.Msg.Err.NAME_KEY_TOO_LONG)
      else None

    error.foreach {
      message =>
        val ex = ValidationException(message)
        ex.errors += (FieldConst.Repository.NAME -> message)
        throw ex
    }
    normalized

case class Repository(
    id: Option[String] = None,
    name: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None)
  extends BaseModel:

  /** The name key `repository_01` keeps unique instance-wide (see [[NameKey]]) */
  val nameKey: String = NameKey.of(name)

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
