package com.enhancepro.app

import android.content.Context
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object SegTools {

    /** Portrait: blur background, keep subject sharp. intensity 0..100 = blur strength. */
    fun portrait(context: Context, src: Mat, intensity: Int): Mat {
        val bmp = Filters.matToBitmap(src)
        val seg = newSegmenter(context)
            ?: return src.clone()
        val mp = BitmapImageBuilder(bmp).build()
        val result = seg.segment(mp)
        seg.close()
        val maskImage = result.categoryMask().get()
        val mw = maskImage.width; val mh = maskImage.height
        val maskBuf = ByteBufferExtractor.extract(maskImage)
        val mask = Mat(mh, mw, CvType.CV_8UC1)
        val arr = ByteArray(mw * mh)
        maskBuf.rewind()
        for (i in arr.indices) arr[i] = if (maskBuf.get().toInt() != 0) 0 else 255.toByte() // 255 = person (category 0 = background in selfie segmenter)
        mask.put(0, 0, arr)
        val maskBig = Mat()
        Imgproc.resize(mask, maskBig, Size(src.cols().toDouble(), src.rows().toDouble()))
        mask.release()
        // feather the edge
        Imgproc.GaussianBlur(maskBig, maskBig, Size(0.0, 0.0), 6.0)
        // background blur strength by intensity
        val sigma = 4.0 + intensity / 100.0 * 20.0
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), sigma)
        // composite
        val out = Mat()
        val mF = Mat(); val srcF = Mat(); val blF = Mat()
        maskBig.convertTo(mF, CvType.CV_32F, 1.0 / 255.0)
        src.convertTo(srcF, CvType.CV_32F)
        blurred.convertTo(blF, CvType.CV_32F)
        maskBig.release(); blurred.release()
        val m3 = Mat()
        val chans = java.util.ArrayList<Mat>(); repeat(3) { chans.add(mF) }
        Core.merge(chans, m3)
        val inv = Mat(); Core.subtract(Mat.ones(mF.size(), CvType.CV_32FC1), mF, inv)
        val inv3 = Mat(); Core.merge(java.util.ArrayList<Mat>().also { repeat(3) { _ -> it.add(inv) } }, inv3)
        val t1 = Mat(); val t2 = Mat()
        Core.multiply(srcF, m3, t1); Core.multiply(blF, inv3, t2)
        val sum = Mat(); Core.add(t1, t2, sum)
        sum.convertTo(out, CvType.CV_8U)
        srcF.release(); blF.release(); m3.release(); inv.release(); inv3.release(); t1.release(); t2.release(); sum.release(); mF.release()
        return out
    }

    private fun newSegmenter(context: Context): ImageSegmenter? {
        return try {
            val base = BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite").build()
            val opts = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .build()
            ImageSegmenter.createFromOptions(context, opts)
        } catch (t: Throwable) { null }
    }

    /** Jawline: slim and define the jaw via landmark-guided warp. intensity 0..100. */
    fun jawline(context: Context, src: Mat, intensity: Int): Mat {
        val bmp = Filters.matToBitmap(src)
        val landmarker = newLandmarker(context) ?: return src.clone()
        val mp = BitmapImageBuilder(bmp).build()
        val res = landmarker.detect(mp)
        landmarker.close()
        if (res.faceLandmarks().isEmpty()) return src.clone()
        val lm = res.faceLandmarks()[0]
        val w = src.cols().toFloat(); val h = src.rows().toFloat()
        // face oval landmark indices (mediapipe canonical, jaw half)
        val oval = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
        // jaw = oval excluding top of head (indices 10, 21, 54, 103, 67, 109, 162, 127, 234 are forehead/temple)
        val jawIdx = intArrayOf(356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93)
        if (lm.size < 478) return src.clone()
        // face center axis: midpoint between eyes (33, 263)
        val cx = (lm[33].x() + lm[263].x()) / 2f * w
        val chin = lm[152]
        val chinX = chin.x() * w; val chinY = chin.y() * h
        val strength = intensity / 100.0f
        // sparse constraints: move jaw points toward the center axis by a few % of face width
        val faceW = kotlin.math.abs(lm[356].x() - lm[127].x()) * w
        val grid = 24
        val gw = src.cols() / grid + 2; val gh = src.rows() / grid + 2
        val dx = FloatArray(gw * gh); val dy = FloatArray(gw * gh)
        val wgt = FloatArray(gw * gh)
        val sigma2 = (faceW * 0.35f) * (faceW * 0.35f)
        for (i in jawIdx) {
            val px = lm[i].x() * w; val py = lm[i].y() * h
            val isChin = i == 152
            val moveX = (cx - px) * 0.06f * strength           // pull inward
            val moveY = if (isChin) 0f else -faceW * 0.015f * strength // slight lift, not chin
            // distance from center: skip points already near axis
            if (kotlin.math.abs(px - cx) < faceW * 0.06f && !isChin) continue
            val gx = (px / grid).toInt(); val gy = (py / grid).toInt()
            for (yy in max(0, gy - 8)..min(gh - 1, gy + 8)) {
                for (xx in max(0, gx - 8)..min(gw - 1, gx + 8)) {
                    val ddx = xx * grid - px; val ddy = yy * grid - py
                    val g = exp(-(ddx * ddx + ddy * ddy) / sigma2)
                    val k = yy * gw + xx
                    dx[k] += moveX * g; dy[k] += moveY * g; wgt[k] += g
                }
            }
        }
        val mapX = Mat(src.rows(), src.cols(), CvType.CV_32FC1)
        val mapY = Mat(src.rows(), src.cols(), CvType.CV_32FC1)
        val rx = FloatArray(src.cols()); val ry = FloatArray(src.cols())
        // bilinear sample of the displacement grid per pixel (coarse: nearest grid cell is fine at grid=24)
        for (y in 0 until src.rows()) {
            val gy = (y / grid).coerceIn(0, gh - 1)
            for (x in 0 until src.cols()) {
                val gx = (x / grid).coerceIn(0, gw - 1)
                val k = gy * gw + gx
                val wk = if (wgt[k] > 1e-6f) wgt[k] else 1f
                rx[x] = x + dx[k] / wk
                ry[x] = y + dy[k] / wk
            }
            mapX.put(y, 0, rx); mapY.put(y, 0, ry)
        }
        val out = Mat()
        Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_LINEAR)
        mapX.release(); mapY.release()
        return out
    }

    private fun newLandmarker(context: Context): FaceLandmarker? {
        return try {
            val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()
            FaceLandmarker.createFromOptions(context, opts)
        } catch (t: Throwable) { null }
    }
}
