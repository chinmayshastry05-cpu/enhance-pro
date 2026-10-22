package com.enhancepro.app

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.calib3d.Calib3d

object Filters {

    enum class Tool(val label: String) {
        MAGIC("Magic"), BRIGHTEN("Brighten"), HDR("HDR"),
        SHARPEN("Sharpen"), DEBLUR("Deblur"), MOIRE("Fix Moire"),
        SHADOW("Remove Shadow"), GLARE("Reduce Glare"), LENS("Fix Lens")
    }

    fun bitmapToMat(b: Bitmap): Mat {
        val m = Mat()
        Utils.bitmapToMat(b, m) // RGBA
        val rgb = Mat()
        Imgproc.cvtColor(m, rgb, Imgproc.COLOR_RGBA2RGB)
        m.release()
        return rgb
    }

    fun matToBitmap(m: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(m, rgba, Imgproc.COLOR_RGB2RGBA)
        val b = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, b)
        rgba.release()
        return b
    }

    /** Apply [tool] at intensity 0..100, blending result with the source. Returns a new Mat. */
    fun apply(src: Mat, tool: Tool, intensity: Int): Mat {
        val full = when (tool) {
            Tool.MAGIC -> magic(src)
            Tool.BRIGHTEN -> brighten(src)
            Tool.HDR -> hdr(src)
            Tool.SHARPEN -> sharpen(src, 1.0)
            Tool.DEBLUR -> deblur(src)
            Tool.MOIRE -> moire(src)
            Tool.SHADOW -> shadow(src, intensity)
            Tool.GLARE -> glare(src)
            Tool.LENS -> lens(src, intensity)
        }
        val alpha = intensity.coerceIn(0, 100) / 100.0
        val out = Mat()
        Core.addWeighted(src, 1.0 - alpha, full, alpha, 0.0, out)
        full.release()
        return out
    }

    private fun magic(src: Mat): Mat {
        // denoise -> gray-world white balance -> CLAHE on L -> saturation boost -> unsharp
        val den = Mat()
        Imgproc.bilateralFilter(src, den, 5, 40.0, 40.0)
        val wb = grayWorld(den)
        den.release()
        val lab = Mat()
        Imgproc.cvtColor(wb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val clahe = Imgproc.createCLAHE(1.6, Size(8.0, 8.0))
        val l = Mat()
        clahe.apply(ch[0], l)
        ch[0].release(); lab.release()
        ch[0] = l
        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }
        val rgb = Mat()
        Imgproc.cvtColor(merged, rgb, Imgproc.COLOR_Lab2RGB)
        merged.release()
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val hc = ArrayList<Mat>()
        Core.split(hsv, hc)
        hsv.release()
        val s = Mat()
        Core.multiply(hc[1], org.opencv.core.Scalar(1.15), s)
        Core.min(s, org.opencv.core.Scalar(255.0), s)
        hc[1].release()
        hc[1] = s
        val hm = Mat()
        Core.merge(hc, hm)
        hc.forEach { it.release() }
        val sat = Mat()
        Imgproc.cvtColor(hm, sat, Imgproc.COLOR_HSV2RGB)
        hm.release()
        val out = unsharp(sat, 3.0, 0.3)
        rgb.release(); sat.release()
        return out
    }

    private fun brighten(src: Mat): Mat {
        // gamma lift 0.65 via LUT
        val lut = Mat(1, 256, CvType.CV_8UC1)
        for (i in 0..255) {
            val v = 255.0 * Math.pow(i / 255.0, 0.65)
            lut.put(0, i, v.coerceIn(0.0, 255.0))
        }
        val out = Mat()
        org.opencv.core.Core.LUT(src, lut, out)
        lut.release()
        return out
    }

    private fun hdr(src: Mat): Mat {
        // local contrast (CLAHE) + detail layer boost
        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val l = Mat()
        clahe.apply(ch[0], l)
        ch[0].release()
        ch[0] = l
        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }
        val base = Mat()
        Imgproc.cvtColor(merged, base, Imgproc.COLOR_Lab2RGB)
        merged.release()
        // detail = src - blur; add back
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 5.0)
        val srcF = Mat(); val blurF = Mat()
        src.convertTo(srcF, CvType.CV_32F); blur.convertTo(blurF, CvType.CV_32F)
        blur.release()
        val detail = Mat()
        Core.subtract(srcF, blurF, detail)
        Core.multiply(detail, org.opencv.core.Scalar(0.35), detail)
        val baseF = Mat()
        base.convertTo(baseF, CvType.CV_32F)
        base.release()
        val sum = Mat()
        Core.add(baseF, detail, sum)
        srcF.release(); blurF.release(); detail.release(); baseF.release()
        val out = Mat()
        sum.convertTo(out, CvType.CV_8U)
        sum.release()
        return out
    }

    private fun sharpen(src: Mat, amount: Double): Mat = unsharp(src, 3.0, amount)

    private fun deblur(src: Mat): Mat {
        val den = Mat()
        Imgproc.bilateralFilter(src, den, 3, 25.0, 25.0)
        val out = unsharp(den, 2.0, 1.0)
        den.release()
        return out
    }

    private fun moire(src: Mat): Mat {
        // suppress fine periodic patterns: blur the chroma, light blur overall, keep luminance detail
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 1.4)
        val out = Mat()
        Core.addWeighted(src, 0.35, blur, 0.65, 0.0, out)
        blur.release()
        val final = unsharp(out, 2.0, 0.3)
        out.release()
        return final
    }

    private fun grayWorld(src: Mat): Mat {
        val ch = ArrayList<Mat>()
        Core.split(src, ch)
        val means = Core.mean(src)
        val avg = (means.`val`[0] + means.`val`[1] + means.`val`[2]) / 3.0
        for (i in 0..2) {
            val gain = if (means.`val`[i] > 1.0) avg / means.`val`[i] else 1.0
            val scaled = Mat()
            Core.multiply(ch[i], org.opencv.core.Scalar(gain.coerceIn(0.8, 1.25)), scaled)
            ch[i].release()
            ch[i] = scaled
        }
        val out = Mat()
        Core.merge(ch, out)
        ch.forEach { it.release() }
        return out
    }

    private fun unsharp(src: Mat, sigma: Double, amount: Double): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), sigma)
        val out = Mat()
        Core.addWeighted(src, 1.0 + amount, blur, -amount, 0.0, out)
        blur.release()
        return out
    }

    private fun shadow(src: Mat, intensity: Int): Mat {
        // lift dark regions toward local background brightness
        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        val l = ch[0]
        val bg = Mat()
        Imgproc.GaussianBlur(l, bg, Size(0.0, 0.0), 40.0)
        // shadow mask: pixels notably darker than local background
        val lF = Mat(); val bgF = Mat()
        l.convertTo(lF, CvType.CV_32F); bg.convertTo(bgF, CvType.CV_32F)
        val ratio = Mat()
        Core.divide(lF, bgF, ratio)
        // lift factor = bg/l, capped, applied smoothly where ratio < 0.9
        val lift = Mat()
        Core.divide(bgF, lF, lift)
        Core.min(lift, org.opencv.core.Scalar(2.2), lift)
        val strength = intensity.coerceIn(0, 100) / 100.0
        val one = Mat.ones(lift.size(), CvType.CV_32F)
        val liftBlend = Mat()
        Core.addWeighted(one, 1.0 - strength, lift, strength, 0.0, liftBlend)
        val lNew = Mat()
        Core.multiply(lF, liftBlend, lNew)
        Core.min(lNew, org.opencv.core.Scalar(255.0), lNew)
        val l8 = Mat()
        lNew.convertTo(l8, CvType.CV_8U)
        ch[0].release(); bg.release(); lF.release(); bgF.release(); ratio.release()
        lift.release(); one.release(); liftBlend.release(); lNew.release()
        ch[0] = l8
        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        merged.release()
        return out
    }

    private fun glare(src: Mat): Mat {
        // compress clipped highlights: soft-knee curve on V channel
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        hsv.release()
        val v = ch[0]
        val lut = Mat(1, 256, CvType.CV_8UC1)
        for (i in 0..255) {
            val x = i / 255.0
            val y = if (x > 0.75) 0.75 + (x - 0.75) * 0.55 else x
            lut.put(0, i, (y * 255.0).coerceIn(0.0, 255.0))
        }
        val v2 = Mat()
        Core.LUT(v, lut, v2)
        lut.release(); v.release()
        ch[0] = v2
        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_HSV2RGB)
        merged.release()
        // recover a bit of local contrast
        val final = unsharp(out, 4.0, 0.25)
        out.release()
        return final
    }

    private fun lens(src: Mat, intensity: Int): Mat {
        // slider 0..100 maps to k1 -0.35..+0.35 (50 = no correction)
        val k1 = (intensity - 50) / 50.0 * 0.35
        if (kotlin.math.abs(k1) < 0.01) return src.clone()
        val w = src.cols(); val h = src.rows()
        val cam = Mat.eye(3, 3, CvType.CV_64F)
        val f = maxOf(w, h).toDouble()
        cam.put(0, 0, f); cam.put(1, 1, f)
        cam.put(0, 2, w / 2.0); cam.put(1, 2, h / 2.0)
        val dist = Mat.zeros(1, 5, CvType.CV_64F)
        dist.put(0, 0, k1)
        val out = Mat()
        Calib3d.undistort(src, out, cam, dist)
        cam.release(); dist.release()
        return out
    }
}
