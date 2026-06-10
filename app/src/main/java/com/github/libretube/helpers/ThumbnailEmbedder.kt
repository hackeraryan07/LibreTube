package com.github.libretube.helpers

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path

/**
 * Embeds a thumbnail image into an MP4 video file using raw byte-level MP4 box construction.
 *
 * WHY this approach instead of mp4parser's MetaBox:
 * mp4parser's MetaBox extends AbstractContainerBox, NOT AbstractFullBox.
 * But the ISO 14496-12 spec says `meta` IS a FullBox (requires 4 bytes: version + flags).
 * mp4parser therefore writes `meta` WITHOUT those 4 bytes.
 * Android 9 (API 28) has a strict MP4 parser that follows the spec — it misreads the
 * box size and silently ignores the thumbnail.
 * Android 12+ has a lenient parser that tries both interpretations — so it works there.
 *
 * This rewrite manually constructs all boxes with correct FullBox headers, making
 * the embedded thumbnail readable on Android 9 and all newer versions.
 */
object ThumbnailEmbedder {

    private const val TAG = "ThumbnailEmbedder"

    fun embedThumbnail(videoPath: Path, thumbnailPath: Path) {
        val videoFile = videoPath.toFile()
        val thumbFile = thumbnailPath.toFile()

        if (!videoFile.exists() || !thumbFile.exists()) {
            Log.w(TAG, "Video or thumbnail file does not exist, skipping embed")
            return
        }

        val coverBytes = thumbFile.readBytes()
        val isJpg = thumbFile.name.lowercase().endsWith(".jpg") ||
                thumbFile.name.lowercase().endsWith(".jpeg")

        val tempFile = File(videoFile.parent, videoFile.name + ".tmp")

        try {
            val videoBytes = videoFile.readBytes()

            // Find moov box
            val moovOffset = findBox(videoBytes, 0, videoBytes.size, "moov")
            if (moovOffset < 0) {
                Log.e(TAG, "No moov box found in video, skipping embed")
                return
            }
            val moovSize = readInt32BE(videoBytes, moovOffset)
            val moovEnd = moovOffset + moovSize

            // Build the udta+meta+ilst+covr tree from the inside out

            // 1. data box inside covr
            //    layout: size(4) + "data"(4) + type_indicator(4) + locale(4) + payload
            //    type_indicator: 13 = JPEG, 14 = PNG  (this IS a FullBox: version=0, flags=type)
            val dataTypeIndicator = if (isJpg) 13 else 14
            val dataBoxSize = 4 + 4 + 4 + 4 + coverBytes.size
            val dataBox = ByteArray(dataBoxSize)
            writeInt32BE(dataBox, 0, dataBoxSize)
            "data".toByteArray(Charsets.ISO_8859_1).copyInto(dataBox, 4)
            writeInt32BE(dataBox, 8, dataTypeIndicator)   // version(1 byte)=0 + flags(3 bytes)=type
            writeInt32BE(dataBox, 12, 0)                  // locale = 0
            coverBytes.copyInto(dataBox, 16)

            // 2. covr box  (container, NOT a FullBox)
            //    layout: size(4) + "covr"(4) + dataBox
            val covrBoxSize = 4 + 4 + dataBox.size
            val covrBox = ByteArray(covrBoxSize)
            writeInt32BE(covrBox, 0, covrBoxSize)
            "covr".toByteArray(Charsets.ISO_8859_1).copyInto(covrBox, 4)
            dataBox.copyInto(covrBox, 8)

            // 3. ilst box  (container, NOT a FullBox)
            //    layout: size(4) + "ilst"(4) + covrBox
            val ilstBoxSize = 4 + 4 + covrBox.size
            val ilstBox = ByteArray(ilstBoxSize)
            writeInt32BE(ilstBox, 0, ilstBoxSize)
            "ilst".toByteArray(Charsets.ISO_8859_1).copyInto(ilstBox, 4)
            covrBox.copyInto(ilstBox, 8)

            // 4. hdlr box inside meta  (FullBox: version=0, flags=0)
            //    layout: size(4) + "hdlr"(4) + version+flags(4) + pre_defined(4)
            //            + handler_type(4) + reserved(12) + name (null-terminated)
            val hdlrName = byteArrayOf(0)  // single null terminator
            val hdlrBoxSize = 4 + 4 + 4 + 4 + 4 + 12 + hdlrName.size
            val hdlrBox = ByteArray(hdlrBoxSize)
            writeInt32BE(hdlrBox, 0, hdlrBoxSize)
            "hdlr".toByteArray(Charsets.ISO_8859_1).copyInto(hdlrBox, 4)
            writeInt32BE(hdlrBox, 8, 0)                  // version=0, flags=0
            writeInt32BE(hdlrBox, 12, 0)                 // pre_defined = 0
            "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(hdlrBox, 16)  // handler_type
            // reserved[3] at offsets 20,24,28 = 0 (already zeroed)
            hdlrName.copyInto(hdlrBox, 32)               // null-terminated name

            // 5. meta box  *** CRITICAL FIX FOR ANDROID 9 ***
            //    meta IS a FullBox per ISO 14496-12 spec.
            //    mp4parser writes it WITHOUT the version+flags bytes → Android 9 breaks.
            //    We write it WITH version+flags = 0x00000000 → works on all versions.
            //    layout: size(4) + "meta"(4) + version+flags(4) + hdlrBox + ilstBox
            val metaBoxSize = 4 + 4 + 4 + hdlrBox.size + ilstBox.size
            val metaBox = ByteArray(metaBoxSize)
            writeInt32BE(metaBox, 0, metaBoxSize)
            "meta".toByteArray(Charsets.ISO_8859_1).copyInto(metaBox, 4)
            writeInt32BE(metaBox, 8, 0)                  // version=0, flags=0  ← THE FIX
            hdlrBox.copyInto(metaBox, 12)
            ilstBox.copyInto(metaBox, 12 + hdlrBox.size)

            // 6. udta box  (container, NOT a FullBox)
            //    layout: size(4) + "udta"(4) + metaBox
            val udtaBoxSize = 4 + 4 + metaBox.size
            val udtaBox = ByteArray(udtaBoxSize)
            writeInt32BE(udtaBox, 0, udtaBoxSize)
            "udta".toByteArray(Charsets.ISO_8859_1).copyInto(udtaBox, 4)
            metaBox.copyInto(udtaBox, 8)

            // Strip any existing udta from moov inner content
            val moovInnerStart = moovOffset + 8  // skip size(4) + "moov"(4)
            val moovInnerBytes = stripBox(videoBytes, moovInnerStart, moovEnd, "udta")

            // Build new moov box
            val newMoovSize = 8 + moovInnerBytes.size + udtaBox.size
            val sizeDiff = newMoovSize - moovSize

            // If moov appears before mdat, all stco/co64 offsets must be shifted
            val mdatOffset = findBox(videoBytes, 0, videoBytes.size, "mdat")
            val moovBeforeMdat = mdatOffset < 0 || moovOffset < mdatOffset

            val newMoovInner: ByteArray
            if (moovBeforeMdat && sizeDiff != 0) {
                newMoovInner = shiftChunkOffsets(moovInnerBytes, sizeDiff.toLong())
            } else {
                newMoovInner = moovInnerBytes
            }

            val newMoovBox = ByteArray(8 + newMoovInner.size + udtaBox.size)
            writeInt32BE(newMoovBox, 0, newMoovBox.size)
            "moov".toByteArray(Charsets.ISO_8859_1).copyInto(newMoovBox, 4)
            newMoovInner.copyInto(newMoovBox, 8)
            udtaBox.copyInto(newMoovBox, 8 + newMoovInner.size)

            // Write output: everything before moov + new moov + everything after old moov
            val fos = FileOutputStream(tempFile)
            fos.write(videoBytes, 0, moovOffset)
            fos.write(newMoovBox)
            fos.write(videoBytes, moovEnd, videoBytes.size - moovEnd)
            fos.flush()
            fos.close()

            // Replace original with the modified file
            videoFile.delete()
            tempFile.renameTo(videoFile)

            Log.i(TAG, "Thumbnail embedded successfully into ${videoFile.name}")

        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Failed to embed thumbnail: ${e.message}")
            Log.e(TAG, e.stackTraceToString())
        }
    }

    // -------------------------------------------------------------------------
    // Box search helpers
    // -------------------------------------------------------------------------

    /**
     * Find the byte offset of the first box with the given 4-char [type]
     * within [data] between [start] and [end].
     * Returns -1 if not found.
     */
    private fun findBox(data: ByteArray, start: Int, end: Int, type: String): Int {
        var offset = start
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        while (offset + 8 <= end) {
            val size = readInt32BE(data, offset)
            if (size < 8) break  // malformed
            val nameMatch = data[offset + 4] == typeBytes[0] &&
                    data[offset + 5] == typeBytes[1] &&
                    data[offset + 6] == typeBytes[2] &&
                    data[offset + 7] == typeBytes[3]
            if (nameMatch) return offset
            offset += size
        }
        return -1
    }

    /**
     * Return a copy of [data][start..end] with all top-level boxes of [type] removed.
     */
    private fun stripBox(data: ByteArray, start: Int, end: Int, type: String): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val result = mutableListOf<Byte>()
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt32BE(data, offset)
            if (size < 8) break
            val nameMatch = data[offset + 4] == typeBytes[0] &&
                    data[offset + 5] == typeBytes[1] &&
                    data[offset + 6] == typeBytes[2] &&
                    data[offset + 7] == typeBytes[3]
            if (!nameMatch) {
                for (i in offset until (offset + size)) {
                    result.add(data[i])
                }
            }
            offset += size
        }
        return result.toByteArray()
    }

    /**
     * Walk the moov inner bytes and shift all stco / co64 chunk offset entries by [delta].
     * This is required when the moov box grows and sits before mdat in the file.
     */
    private fun shiftChunkOffsets(moovInner: ByteArray, delta: Long): ByteArray {
        val result = moovInner.copyOf()
        shiftOffsetsInRange(result, 0, result.size, delta)
        return result
    }

    private fun shiftOffsetsInRange(data: ByteArray, start: Int, end: Int, delta: Long) {
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt32BE(data, offset)
            if (size < 8) break
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "stco" -> {
                    // FullBox: version+flags at offset+8, entry_count at offset+12
                    val count = readInt32BE(data, offset + 12)
                    var i = 0
                    while (i < count) {
                        val entryOffset = offset + 16 + i * 4
                        val old = readInt32BE(data, entryOffset).toLong() and 0xFFFFFFFFL
                        writeInt32BE(data, entryOffset, (old + delta).toInt())
                        i++
                    }
                }
                "co64" -> {
                    // FullBox: version+flags at offset+8, entry_count at offset+12
                    val count = readInt32BE(data, offset + 12)
                    var i = 0
                    while (i < count) {
                        val entryOffset = offset + 16 + i * 8
                        val old = readInt64BE(data, entryOffset)
                        writeInt64BE(data, entryOffset, old + delta)
                        i++
                    }
                }
                "trak", "mdia", "minf", "stbl" -> {
                    // Recurse into container boxes
                    shiftOffsetsInRange(data, offset + 8, offset + size, delta)
                }
            }
            offset += size
        }
    }

    // -------------------------------------------------------------------------
    // Byte read/write helpers (Big Endian)
    // -------------------------------------------------------------------------

    private fun readInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt32BE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun readInt64BE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 56) or
                ((data[offset + 1].toLong() and 0xFF) shl 48) or
                ((data[offset + 2].toLong() and 0xFF) shl 40) or
                ((data[offset + 3].toLong() and 0xFF) shl 32) or
                ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
    }

    private fun writeInt64BE(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value ushr 56).toByte()
        data[offset + 1] = (value ushr 48).toByte()
        data[offset + 2] = (value ushr 40).toByte()
        data[offset + 3] = (value ushr 32).toByte()
        data[offset + 4] = (value ushr 24).toByte()
        data[offset + 5] = (value ushr 16).toByte()
        data[offset + 6] = (value ushr 8).toByte()
        data[offset + 7] = value.toByte()
    }
}
