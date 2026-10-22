package com.enhancepro.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.core.Size

/** Real-ESRGAN x4 (Qualcomm w8a8 tflite). Input tiles 128x128 uint8 -> 512x512 uint8. */
class Esrgan(context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            val model = loadModel(context)
            val opts = Interpreter.Options()
            try { opts.setUseNNAPI(true) } catch (_: Throwable) {}
            opts.setNumThreads(4)
            interpreter = Interpreter(model, opts)
        } catch (t: Throwable) {
            try {
                val model = loadModel(context)
                val opts = Interpreter.Options(); opts.setNumThreads(4)
                interpreter = Interpreter(model, opts)
            } catch (t2: Throwable) { interpreter = null }
        }
    }

    private fun loadModel(context: Context): java.nio.ByteBuffer {
        val afd = context.assets.openFd("esrgan_x4.tflite")
        FileInputStream(afd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    val available get() = interpreter != null

    /** Upscale src x4 (or x2 if factor2). Progress callback (done,total). */
    fun upscale(src: Mat, factor2: Boolean, progress: (Int, Int) -> Unit): Mat {
        val it = interpreter ?: throw IllegalStateException("model not loaded")
        val w = src.cols(); val h = src.rows()
        val tilesX = (w + 127) / 128; val tilesY = (h + 127) / 128
        val outW = tilesX * 512; val outH = tilesY * 512
        val out = Mat.zeros(outH, outW, CvType.CV_8UC3)
        val inBuf = Array(1) { Array(128) { Array(128) { ByteArray(3) } } }
        val outBuf = Array(1) { Array(512) { Array(512) { ByteArray(3) } } }
        val tile = Mat()
        val row = ByteArray(128 * 3)
        val orow = ByteArray(512 * 3)
        var done = 0
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * 128; val y0 = ty * 128
                val rw = minOf(128, w - x0); val rh = minOf(128, h - y0)
                val roi = src.submat(y0, y0 + rh, x0, x0 + rw)
                if (rw != 128 || rh != 128) {
                    Imgproc.resize(roi, tile, Size(128.0, 128.0))
                } else roi.copyTo(tile)
                roi.release()
                for (y in 0 until 128) {
                    tile.get(y, 0, row)
                    for (x in 0 until 128) {
                        inBuf[0][y][x][0] = row[x * 3]
                        inBuf[0][y][x][1] = row[x * 3 + 1]
                        inBuf[0][y][x][2] = row[x * 3 + 2]
                    }
                }
                it.run(inBuf, outBuf)
                // crop 4px border to hide seams, paste
                val crop = 4
                val px = tx * 512; val py = ty * 512
                val pasteRoi = out.submat(py + crop, minOf(py + 512 - crop, outH), px + crop, minOf(px + 512 - crop, outW))
                val piece = Mat.zeros(512 - 2 * crop, 512 - 2 * crop, CvType.CV_8UC3)
                for (y in crop until 512 - crop) {
                    for (x in crop until 512 - crop) {
                        orow[(x - crop) * 3] = outBuf[0][y][x][0]
                        orow[(x - crop) * 3 + 1] = outBuf[0][y][x][1]
                        orow[(x - crop) * 3 + 2] = outBuf[0][y][x][2]
                    }
                    piece.put(y - crop, 0, orow.copyOf((512 - 2 * crop) * 3))
                }
                val pw = minOf(512 - 2 * crop, pasteRoi.cols()); val ph = minOf(512 - 2 * crop, pasteRoi.rows())
                piece.submat(0, ph, 0, pw).copyTo(pasteRoi.submat(0, ph, 0, pw))
                piece.release(); pasteRoi.release()
                done++
                progress(done, tilesX * tilesY)
            }
        }
        tile.release()
        // final crop to 4x original size
        val finalW = w * 4; val finalH = h * 4
        val cropped = out.submat(0, minOf(finalH, out.rows()), 0, minOf(finalW, out.cols())).clone()
        out.release()
        if (factor2) {
            val half = Mat()
            Imgproc.resize(cropped, half, Size((w * 2).toDouble(), (h * 2).toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
            cropped.release()
            return half
        }
        return cropped
    }

    fun close() { interpreter?.close() }
}
