package com.github.libretube.helpers

import android.util.Log
import org.mp4parser.IsoFile
import org.mp4parser.boxes.apple.AppleCoverBox
import org.mp4parser.boxes.apple.AppleItemListBox
import org.mp4parser.boxes.iso14496.part12.ChunkOffset64BitBox
import org.mp4parser.boxes.iso14496.part12.HandlerBox
import org.mp4parser.boxes.iso14496.part12.MediaBox
import org.mp4parser.boxes.iso14496.part12.MediaInformationBox
import org.mp4parser.boxes.iso14496.part12.MetaBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.StaticChunkOffsetBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path

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
            val isoFile = IsoFile(videoFile.absolutePath)

            var moov: MovieBox? = null
            for (box in isoFile.boxes) {
                if (box is MovieBox) {
                    moov = box
                    break
                }
            }

            if (moov == null) {
                isoFile.close()
                Log.e(TAG, "No moov box found in video, skipping embed")
                return
            }

            val oldMoovSize = moov.size

            var udta: UserDataBox? = moov.getBoxes(UserDataBox::class.java).firstOrNull()
            if (udta == null) {
                udta = UserDataBox()
                moov.addBox(udta)
            }

            var meta: MetaBox? = udta.getBoxes(MetaBox::class.java).firstOrNull()
            if (meta == null) {
                meta = MetaBox()
                val hdlr = HandlerBox()
                hdlr.name = "Apple mark"
                hdlr.handlerType = "mdir"
                meta.addBox(hdlr)
                udta.addBox(meta)
            }

            var ilst: AppleItemListBox? = meta.getBoxes(AppleItemListBox::class.java).firstOrNull()
            if (ilst == null) {
                ilst = AppleItemListBox()
                meta.addBox(ilst)
            }

            val newBoxes = ArrayList<org.mp4parser.Box>()
            for (box in ilst.boxes) {
                if (box !is AppleCoverBox) {
                    newBoxes.add(box)
                }
            }
            ilst.boxes = newBoxes

            val cover = AppleCoverBox()
            if (isJpg) {
                cover.setJpg(coverBytes)
            } else {
                cover.setPng(coverBytes)
            }
            ilst.addBox(cover)

            val newMoovSize = moov.getSize()
            val sizeDiff = newMoovSize - oldMoovSize

            var isMoovBeforeMdat = false
            for (box in isoFile.boxes) {
                if (box is MovieBox) {
                    isMoovBeforeMdat = true
                } else if (box.type == "mdat") {
                    break
                }
            }

            if (isMoovBeforeMdat && sizeDiff > 0) {
                for (trackBox in moov.getBoxes(TrackBox::class.java)) {
                    val mdia = trackBox.getBoxes(MediaBox::class.java).firstOrNull() ?: continue
                    val minf = mdia.getBoxes(MediaInformationBox::class.java).firstOrNull() ?: continue
                    val stbl = minf.getBoxes(SampleTableBox::class.java).firstOrNull() ?: continue

                    val stco = stbl.getBoxes(StaticChunkOffsetBox::class.java).firstOrNull()
                    if (stco != null) {
                        val offsets = stco.chunkOffsets
                        for (i in offsets.indices) {
                            offsets[i] = offsets[i] + sizeDiff
                        }
                        stco.chunkOffsets = offsets
                    }

                    val co64 = stbl.getBoxes(ChunkOffset64BitBox::class.java).firstOrNull()
                    if (co64 != null) {
                        val offsets = co64.chunkOffsets
                        for (i in offsets.indices) {
                            offsets[i] = offsets[i] + sizeDiff
                        }
                        co64.chunkOffsets = offsets
                    }
                }
            }

            val outRac = RandomAccessFile(tempFile, "rw")
            val fc = outRac.channel
            isoFile.getBox(fc)
            fc.close()
            outRac.close()
            isoFile.close()

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
}
