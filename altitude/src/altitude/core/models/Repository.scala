package altitude.core.models

import java.text.Normalizer
import java.time.LocalDateTime
import java.util.Locale

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Repository:
  given JsonCodec.ReadWriter[Repository] = JsonCodec.macroRW
  given Conversion[ujson.Value, Repository] = json => JsonCodec.read[Repository](json)

  // Unicode whitespace: a tab, a newline and a no-break space are runs to collapse like spaces
  private val WHITESPACE = "(?U)\\s+".r

  /**
   * A Library name as stored: trimmed, each run of whitespace one space, and in NFC, so a name typed with a combining accent and
   * the same name typed precomposed are one name
   */
  def normalizedName(name: String): String =
    Normalizer.normalize(WHITESPACE.split(name).filter(_.nonEmpty).mkString(" "), Normalizer.Form.NFC)

  /**
   * The Library name rule, which setup and the New library dialog share through `RepositoryService.addRepository`: the name
   * normalized, or a `ValidationException` giving the name field (`FieldConst.Repository.NAME`) the first error of: a name is
   * required, holds no control characters, and is 2 to 80 characters long once normalized (counted in code points, so an emoji is
   * one).
   */
  def validName(name: String): String =
    val normalized = normalizedName(name)
    val length = normalized.codePointCount(0, normalized.length)
    val (min, max) = (Api.Constraints.MIN_REPOSITORY_NAME_LENGTH, Api.Constraints.MAX_REPOSITORY_NAME_LENGTH)

    val error: Option[String] =
      if normalized.isEmpty then Some(C.Msg.Err.LIBRARY_NAME_REQUIRED)
      else if normalized.exists(Character.isISOControl) then Some(C.Msg.Err.LIBRARY_NAME_CONTROL_CHARACTERS)
      else if length < min then Some(C.Msg.Err.LIBRARY_NAME_TOO_SHORT.format(min))
      else if length > max then Some(C.Msg.Err.LIBRARY_NAME_TOO_LONG.format(max))
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
    ownerAccountId: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  /**
   * The name key `repository_01` keeps unique instance-wide, folded here and not in SQL, since SQLite's `lower()` folds ASCII
   * only. `Locale.ROOT` keeps "I" folding to "i" whatever the JVM's default locale.
   */
  val nameLowercase: String = name.toLowerCase(Locale.ROOT)

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
