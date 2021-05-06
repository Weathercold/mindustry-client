package mindustry.client.utils

import arc.scene.Element
import arc.scene.ui.Dialog
import arc.scene.ui.Label
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import arc.util.serialization.Base64Coder
import mindustry.client.crypto.Base32768Coder
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.time.temporal.TemporalUnit
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import kotlin.experimental.and
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

fun Table.label(text: String): Cell<Label> {
    return add(Label(text))
}

fun ByteBuffer.remainingBytes(): ByteArray {
    return bytes(remaining())
}

fun ByteBuffer.bytes(num: Int): ByteArray {
    val bytes = ByteArray(num)
    get(bytes)
    return bytes
}

/** Converts a [Long] representing unix time in seconds to [Instant] */
fun Long.toInstant(): Instant? {
    if (Instant.MIN.epochSecond > this || Instant.MAX.epochSecond < this) return null
    return Instant.ofEpochSecond(this)
}

/** Seconds between this and [other].  If [other] happened after this, it will be positive. */
fun Temporal.secondsBetween(other: Temporal) = timeSince(other, ChronoUnit.SECONDS)

fun Temporal.timeSince(other: Temporal, unit: TemporalUnit) = unit.between(this, other)

/** The age of this temporal in the given unit (by default seconds). Always positive. */
fun Temporal?.age(unit: TemporalUnit = ChronoUnit.SECONDS): Long {
    return abs(this?.timeSince(Instant.now(), unit) ?: return Long.MAX_VALUE)
}

/** Adds an element to the table followed by a row. */
fun <T : Element> Table.row(element: T): Cell<T> {
    val out = add(element)
    row()
    return out
}

inline fun dialog(name: String, style: Dialog.DialogStyle = Styles.defaultDialog, dialog: BaseDialog.() -> Unit): Dialog {
    return BaseDialog(name, style).apply { clear() }.apply(dialog)
}

fun ByteArray.base64(): String = Base64Coder.encode(this).concatToString()

fun String.base64(): ByteArray? = try { Base64Coder.decode(this) } catch (e: IllegalArgumentException) { null }

fun ByteArray.base32678(): String = Base32768Coder.encode(this)

fun String.base32678(): ByteArray? = try { Base32768Coder.decode(this) } catch (e: IOException) { null }

fun Int.toBytes() = byteArrayOf((this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), (this).toByte())

fun Char.toBytes() = byteArrayOf((toInt() shr 8).toByte(), (this).toByte())

fun Long.toBytes() = byteArrayOf((this shr 56).toByte(), (this shr 48).toByte(), (this shr 40).toByte(), (this shr 32).toByte(), (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), (this).toByte())

fun ByteArray.toInt(): Int {
    return this[0].toInt() and 0xFF shl 24  or
          (this[1].toInt() and 0xFF shl 16) or
          (this[2].toInt() and 0xFF shl 8)  or
          (this[3].toInt() and 0xFF shl 0)
}

fun ByteArray.toShort(): Short {
    return ((this[1].toInt() and 0xFF shl 8) or (this[0].toInt() and 0xFF shl 0)).toShort()
}

fun ByteArray.toChar() = toShort().toChar()

fun Double.floor() = floor(this).toInt()

fun Float.floor() = floor(this).toInt()

fun Double.ceil() = ceil(this).toInt()

fun Float.ceil() = ceil(this).toInt()

fun ByteArray.buffer(): ByteBuffer = ByteBuffer.wrap(this)

object Compression {
    fun compress(input: ByteArray): ByteArray {
        val deflater = DeflaterInputStream(input.inputStream())
        val output = deflater.readBytes()
        deflater.close()
        return output
    }

    fun inflate(input: ByteArray): ByteArray {
        val inflater = InflaterInputStream(input.inputStream())
        val output = inflater.readBytes()
        inflater.close()
        return output
    }
}

fun ByteArray.compress() = Compression.compress(this)

fun ByteArray.inflate() = Compression.inflate(this)

inline fun <T> Iterable<T>.sortedThreshold(threshold: Double, predicate: (T) -> Double): List<T> {
    return zip(map(predicate))  // Compute the predicate for each value and put it in pairs with the original item
        .filter { it.second >= threshold }  // Filter by threshold
        .sortedBy { it.second }  // Sort
        .unzip().first  // Go from a list of pairs back to a list
}

fun String.replaceLast(deliminator: String, replacement: String): String {
    val index = lastIndexOf(deliminator)
    if (index == -1) return this
    return replaceRange(index, index + deliminator.length, replacement)
}

fun String.removeLast(deliminator: String) = replaceLast(deliminator, "")

fun <T> T.print(message: String = ""): T {
    println(message + this.toString())
    return this
}

val IntRange.size get() = last - first

fun onesUntilEnd(start: Int): Byte = (0xFF shr start).toByte()

fun ones(num: Int): Byte = ((0xFF shl (8 - num)) or 0xFF).toByte()

fun ByteArray.padded(length: Int): ByteArray {
    if (size >= length) return this
    return reversedArray().copyOf(length).reversedArray()
}
