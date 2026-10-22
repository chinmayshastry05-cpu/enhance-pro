package com.enhancepro.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoTools {

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int)

    fun probe(context: Context, uri: Uri): VideoInfo? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.release()
            VideoInfo(dur, w, h)
        } catch (t: Throwable) { null }
    }

    /** Long exposure: average evenly-spaced frames into one photo. Returns saved display name or null. */
    fun longExposure(context: Context, uri: Uri, frameCount: Int, progress: (Int, Int) -> Unit): String? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var acc: Mat? = null
            var n = 0
            for (i in 0 until frameCount) {
                val tUs = (durMs * 1000L * i / frameCount)
                val bmp = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val m = Filters.bitmapToMat(bmp)
                val f = Mat()
                m.convertTo(f, CvType.CV_32F)
                m.release()
                if (acc == null) acc = f else { Core.add(acc, f, acc); f.release() }
                n++
                progress(i + 1, frameCount)
            }
            r.release()
            if (acc == null || n == 0) return null
            Core.multiply(acc, org.opencv.core.Scalar(1.0 / n), acc)
            val out = Mat()
            acc.convertTo(out, CvType.CV_8U)
            acc.release()
            // gentle grade: brighten a touch + sharpen
            val graded = Filters.apply(out, Filters.Tool.MAGIC, 40)
            out.release()
            val bmp = Filters.matToBitmap(graded)
            graded.release()
            saveBitmap(context, bmp, "LongExposure")
        } catch (t: Throwable) {
            try { r.release() } catch (_: Throwable) {}
            null
        }
    }

    /** Single Take: pick the 4 sharpest sampled stills + cut a 4s highlight clip around the best moment. */
    fun singleTake(context: Context, uri: Uri, progress: (String) -> Unit): String {
        val r = MediaMetadataRetriever()
        val saved = StringBuilder()
        try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return "Could not read video"
            val step = 500L // ms
            val scored = ArrayList<Pair<Long, Double>>()
            var t = 0L
            while (t < durMs) {
                val bmp = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val m = Filters.bitmapToMat(bmp)
                    val small = Mat()
                    Imgproc.resize(m, small, org.opencv.core.Size(320.0, 320.0 * m.rows() / m.cols()))
                    m.release()
                    val gray = Mat()
                    Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
                    small.release()
                    val lap = Mat()
                    Imgproc.Laplacian(gray, lap, CvType.CV_64F)
                    val mean = org.opencv.core.MatOfDouble(); val std = org.opencv.core.MatOfDouble()
                    Core.meanStdDev(lap, mean, std)
                    val score = std.toArray()[0]
                    lap.release(); gray.release(); mean.release(); std.release()
                    scored.add(t to score)
                }
                t += step
            }
            if (scored.isEmpty()) { r.release(); return "No frames read" }
            scored.sortByDescending { it.second }
            // pick top 4 spread at least 2s apart
            val picks = ArrayList<Long>()
            for (c in scored) {
                if (picks.size >= 4) break
                if (picks.all { kotlin.math.abs(it - c.first) > 2000 }) picks.add(c.first)
            }
            picks.sort()
            progress("Saving ${picks.size} best photos")
            var i = 0
            for (p in picks) {
                val bmp = r.getFrameAtTime(p * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                i++
                saveBitmap(context, bmp, "SingleTake_$i")
            }
            r.release()
            saved.append("Saved $i best photos")
            // highlight clip around best pick
            if (picks.isNotEmpty()) {
                progress("Cutting highlight clip")
                val best = picks[picks.size / 2]
                val clipOk = cutClip(context, uri, maxOf(0L, best - 2000), minOf(durMs, best + 2000))
                if (clipOk) saved.append(" + highlight clip")
            }
        } catch (t: Throwable) {
            try { r.release() } catch (_: Throwable) {}
            return "Failed: ${t.message}"
        }
        return saved.toString()
    }

    /** Re-mux a [startMs,endMs] segment into a new mp4 in Movies/EnhancePro. No re-encode. */
    private fun cutClip(context: Context, uri: Uri, startMs: Long, endMs: Long): Boolean {
        return try {
            val name = "SingleTake_clip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EnhancePro")
            }
            val outUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w") ?: return false
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = muxer.addTrack(fmt)
                }
            }
            muxer.start()
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((srcTrack, dstTrack) in trackMap) {
                extractor.selectTrack(srcTrack)
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buf, 0)
                    if (info.size < 0) break
                    val pts = extractor.sampleTime
                    if (pts > endMs * 1000) break
                    info.presentationTimeUs = pts
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstTrack, buf, info)
                    extractor.advance()
                }
                extractor.unselectTrack(srcTrack)
            }
            muxer.stop(); muxer.release(); extractor.release(); pfd.close()
            true
        } catch (t: Throwable) { false }
    }

    fun saveBitmap(context: Context, bmp: Bitmap, prefix: String): String? {
        return try {
            val name = "EnhancePro_" + prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EnhancePro")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            name
        } catch (t: Throwable) { null }
    }
}
