package altitude.core.models

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Base64

import altitude.core.util.JsonCodec

object Histogram:
  /** The bins of a channel: the 256 levels, four to a bin */
  val BINS: Int = 64

  /** The channels, in the order of the bytes: luminance, red, green, blue */
  private val CHANNELS: Int = 4

  // The wire form is the column's bytes in Base64; no API answers it today, but an Asset carries it in JSON
  given JsonCodec.ReadWriter[Histogram] =
    JsonCodec
      .readwriter[String]
      .bimap(histogram => Base64.getEncoder.encodeToString(histogram.toBytes), text => fromBytes(Base64.getDecoder.decode(text)))

  /** The Histogram of the bytes [[Histogram.toBytes]] writes; bytes of another length are refused */
  def fromBytes(bytes: Array[Byte]): Histogram =
    require(
      bytes.length == CHANNELS * BINS * java.lang.Float.BYTES,
      s"A Histogram is $CHANNELS channels of $BINS float32 bins, not ${bytes.length} bytes")
    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer
    def channel(): IArray[Float] =
      val bins = new Array[Float](BINS)
      floats.get(bins)
      IArray.unsafeFromArray(bins)
    Histogram(luminance = channel(), red = channel(), green = channel(), blue = channel())

/**
 * How a still image's pixels spread over the levels (the glossary's Histogram): its luminance and its red, green and blue
 * channels, each [[Histogram.BINS]] bins holding the fraction of the pixels whose level fell in the bin, so a channel sums to 1.
 * `ImageMeasurement` counts it at import over the image fitted to the measurement box, the luminance over the gray pixels the
 * exposure metrics read. The bins are immutable, and two Histograms are equal when their bins are: a case class over arrays would
 * compare them by reference, so two reads of one row would differ.
 */
case class Histogram(luminance: IArray[Float], red: IArray[Float], green: IArray[Float], blue: IArray[Float]):

  // An IArray is the float[] itself at runtime, which the JDK's array methods take
  private def channels: List[Array[Float]] = List(luminance, red, green, blue).map(_.asInstanceOf[Array[Float]])

  channels.foreach(
    channel => require(channel.length == Histogram.BINS, s"A channel has ${Histogram.BINS} bins, not ${channel.length}"))

  override def equals(other: Any): Boolean = other match
    case that: Histogram => channels.zip(that.channels).forall(Arrays.equals(_, _))
    case _ => false

  override def hashCode: Int = channels.map(Arrays.hashCode).hashCode

  /** The bins it has, never their values */
  override def toString: String = s"Histogram(${channels.size} channels of ${Histogram.BINS} bins)"

  /** The column's value on both engines: the four channels, luminance first, as little-endian float32 */
  def toBytes: Array[Byte] =
    val buffer = ByteBuffer.allocate(channels.size * Histogram.BINS * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    val floats = buffer.asFloatBuffer
    channels.foreach(floats.put)
    buffer.array

  /**
   * What a view draws: a byte per bin, the channels in the order of [[toBytes]], each bin over the tallest bin of the four, so
   * the channels share one scale and the tallest reaches 255. Read a byte as unsigned.
   */
  def relativeBytes: Array[Byte] =
    val tallest = channels.map(_.max).max
    channels.flatMap(_.map(bin => if tallest > 0 then Math.round(255 * bin / tallest).toByte else 0.toByte)).toArray
