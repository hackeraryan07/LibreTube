package com.github.libretube.api.obj

import android.os.Parcelable
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.ProxyHelper
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlin.io.path.Path

@Serializable
@Parcelize
data class PipedStream(
    var url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val videoOnly: Boolean? = null,
    val bitrate: Int? = null,
    val initStart: Int? = null,
    val initEnd: Int? = null,
    val indexStart: Int? = null,
    val indexEnd: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val audioTrackName: String? = null,
    val audioTrackId: String? = null,
    val contentLength: Long = -1,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null
): Parcelable {
    private fun getQualityString(title: String): String {
        // Sanitize title so it is safe to use as a file name
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        val qualityTag = quality?.replace(" ", "") ?: "unknown"
        return "${safeTitle}_${qualityTag}_$format." +
            mimeType?.split("/")?.last()
    }

    fun toDownloadItem(fileType: FileType, videoId: String, videoTitle: String = videoId) = DownloadItem(
        type = fileType,
        videoId = videoId,
        fileName = getQualityString(videoTitle),
        path = Path(""),
        url = url?.let { ProxyHelper.unwrapUrl(it) },
        format = format,
        quality = quality,
        language = audioTrackLocale,
        downloadSize = contentLength
    )
}
