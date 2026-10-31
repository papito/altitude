package altitude.core.models

import java.time.LocalDateTime

import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Album:
  given JsonCodec.ReadWriter[Album] = JsonCodec.macroRW
  given Conversion[ujson.Value, Album] = json => JsonCodec.read[Album](json)

/**
 * A flat, named collection of pointers to assets. An album stores nothing itself: membership lives in the `album_asset` table and
 * an asset can be in any number of albums. `numOfAssets` is computed on read and never stored. `createdAt` is the row's, set by
 * the database when the album is added, and is what the results title shows as the album's creation day.
 */
case class Album(
    id: Option[String] = None,
    name: String,
    numOfAssets: Int = 0,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  if name.isEmpty then throw ValidationException("Album name cannot be empty")

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
