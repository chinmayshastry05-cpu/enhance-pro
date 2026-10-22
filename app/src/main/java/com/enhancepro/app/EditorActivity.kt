package com.enhancepro.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorActivity : ComponentActivity() {

    companion object { const val EXTRA_URI = "uri" }

    private val bg = Color.parseColor("#0D0D0F")
    private val gold = Color.parseColor("#E8A33D")
    private val chipBg = Color.parseColor("#1C1C22")
    private val chipText = Color.parseColor("#C9C9D2")

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var slider: SeekBar
    private var original: Bitmap? = null
    private var srcMat: Mat? = null
    private var current: Bitmap? = null
    private var currentTool: Filters.Tool = Filters.Tool.MAGIC
    private var processing = false
    private val chipViews = mutableMapOf<Filters.Tool, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { original?.let { imageView.setImageBitmap(it) }; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        current?.let { imageView.setImageBitmap(it) }; true
                    }
                    else -> false
                }
            }
        }

        statusText = TextView(this).apply {
            text = "Hold the photo to compare"
            setTextColor(Color.parseColor("#7A7A84"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 4)
        }

        val chipRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipLin = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 10, 24, 10) }
        for (tool in Filters.Tool.values()) {
            val chip = makeChip(tool)
            chipViews[tool] = chip
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 96)
            lp.marginEnd = 18
            chip.layoutParams = lp
            chipLin.addView(chip)
        }
        chipRow.addView(chipLin)

        slider = SeekBar(this).apply {
            max = 100
            progress = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 40; marginEnd = 40
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { runTool() }
            })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 8, 30, 30)
        }
        val saveBtn = makeButton("Save", gold, Color.parseColor("#0D0D0F"))
        saveBtn.setOnClickListener { saveToGallery() }
        val shareBtn = makeButton("Share", chipBg, Color.WHITE)
        shareBtn.setOnClickListener { shareImage() }
        val lp1 = LinearLayout.LayoutParams(0, 130, 1f).apply { marginEnd = 14 }
        val lp2 = LinearLayout.LayoutParams(0, 130, 1f)
        btnRow.addView(saveBtn, lp1)
        btnRow.addView(shareBtn, lp2)

        root.addView(imageView)
        root.addView(statusText)
        root.addView(chipRow)
        root.addView(slider)
        root.addView(btnRow)
        setContentView(root)

        selectChip(Filters.Tool.MAGIC)
        loadImage(intent.getStringExtra(EXTRA_URI))
    }

    private fun makeChip(tool: Filters.Tool): TextView {
        return TextView(this).apply {
            text = tool.label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(36, 0, 36, 0)
            setChipStyle(this, false)
            setOnClickListener { selectChip(tool); runTool() }
        }
    }

    private fun setChipStyle(v: TextView, selected: Boolean) {
        val d = GradientDrawable()
        d.cornerRadius = 48f
        if (selected) { d.setColor(gold); v.setTextColor(Color.parseColor("#0D0D0F")) }
        else { d.setColor(chipBg); v.setTextColor(chipText) }
        v.background = d
    }

    private fun selectChip(tool: Filters.Tool) {
        currentTool = tool
        chipViews.forEach { (t, v) -> setChipStyle(v, t == tool) }
    }

    private fun makeButton(label: String, color: Int, textColor: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            val d = GradientDrawable()
            d.setColor(color)
            d.cornerRadius = 60f
            background = d
        }
    }

    private fun loadImage(uriStr: String?) {
        if (uriStr == null) { finish(); return }
        Thread {
            try {
                val uri = Uri.parse(uriStr)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                val maxDim = 3000
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) > maxDim) sample *= 2
                val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts2)
                }
                if (bmp == null) { toast("Could not open photo"); finish(); return@Thread }
                val m = Filters.bitmapToMat(bmp)
                runOnUiThread {
                    original = bmp
                    srcMat = m
                    current = bmp
                    imageView.setImageBitmap(bmp)
                    runTool()
                }
            } catch (t: Throwable) {
                toast("Error: ${t.message}")
                finish()
            }
        }.start()
    }

    private fun runTool() {
        val src = srcMat ?: return
        if (processing) return
        processing = true
        val tool = currentTool
        val intensity = slider.progress
        statusText.text = "${tool.label}..."
        Thread {
            val t0 = System.currentTimeMillis()
            val out = Filters.apply(src, tool, intensity)
            val bmp = Filters.matToBitmap(out)
            out.release()
            val ms = System.currentTimeMillis() - t0
            runOnUiThread {
                processing = false
                current = bmp
                imageView.setImageBitmap(bmp)
                statusText.text = "${tool.label} - ${ms / 1000.0}s - hold photo to compare"
            }
        }.start()
    }

    private fun saveToGallery() {
        val bmp = current ?: return
        Thread {
            try {
                val name = "EnhancePro_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EnhancePro")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    toast("Saved to gallery")
                } else toast("Save failed")
            } catch (t: Throwable) { toast("Save failed: ${t.message}") }
        }.start()
    }

    private fun shareImage() {
        val bmp = current ?: return
        Thread {
            try {
                val dir = File(cacheDir, "shared"); dir.mkdirs()
                val f = File(dir, "enhancepro_share.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                val uri = FileProvider.getUriForFile(this, "com.enhancepro.app.fileprovider", f)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(intent, "Share photo")) }
            } catch (t: Throwable) { toast("Share failed: ${t.message}") }
        }.start()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        srcMat?.release()
    }
}
