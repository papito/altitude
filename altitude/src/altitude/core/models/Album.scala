package altitude.core.models

import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Album:
  given JsonCodec.ReadWriter[Album] = JsonCodec.macroRW
  given Conversion[ujson.Value, Album] = json => JsonCodec.read[Album](json)

/**
 * A flat, named collection of pointers to assets. An album stores nothing itself: membership lives in the `album_asset` table and
 * an asset can be in any number of albums. `numOfAssets` is computed on read and never stored.
 */
case class Album(id: Option[String] = None, name: String, numOfAssets: Int = 0) extends BaseModel with NoDates:

  if name.isEmpty then throw ValidationException("Album name cannot be empty")

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
