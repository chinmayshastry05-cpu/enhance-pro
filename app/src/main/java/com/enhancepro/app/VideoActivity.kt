package com.enhancepro.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class VideoActivity : ComponentActivity() {

    companion object { const val EXTRA_URI = "uri" }

    private val bg = Color.parseColor("#0D0D0F")
    private val gold = Color.parseColor("#E8A33D")
    private val chipBg = Color.parseColor("#1C1C22")
    private val chipText = Color.parseColor("#C9C9D2")

    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private var processing = false
    private var mode = 0 // 0 = Long Exposure, 1 = Single Take
    private val chipViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr == null) { finish(); return }
        val uri = Uri.parse(uriStr)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        statusText = TextView(this).apply {
            text = "Choose a tool"
            setTextColor(Color.parseColor("#7A7A84"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 4)
        }
        val chipRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipLin = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 10, 24, 10) }
        listOf("Long Exposure", "Single Take").forEachIndexed { idx, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(36, 0, 36, 0)
                setChipStyle(this, idx == 0)
                setOnClickListener { mode = idx; chipViews.forEachIndexed { i, v -> setChipStyle(v, i == mode) } }
            }
            chipViews.add(chip)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 96)
            lp.marginEnd = 18
            chip.layoutParams = lp
            chipLin.addView(chip)
        }
        chipRow.addView(chipLin)

        val goBtn = TextView(this).apply {
            text = "Process video"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0D0D0F"))
            val d = GradientDrawable(); d.setColor(gold); d.cornerRadius = 60f
            background = d
            setOnClickListener { process(uri) }
        }
        val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130).apply {
            marginStart = 30; marginEnd = 30; topMargin = 8; bottomMargin = 30
        }

        root.addView(preview)
        root.addView(statusText)
        root.addView(chipRow)
        root.addView(goBtn, btnLp)
        setContentView(root)

        // show first frame as preview
        Thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val frame = r.getFrameAtTime(0)
                r.release()
                runOnUiThread { frame?.let { preview.setImageBitmap(it) } }
            } catch (t: Throwable) {}
        }.start()
    }

    private fun setChipStyle(v: TextView, selected: Boolean) {
        val d = GradientDrawable()
        d.cornerRadius = 48f
        if (selected) { d.setColor(gold); v.setTextColor(Color.parseColor("#0D0D0F")) }
        else { d.setColor(chipBg); v.setTextColor(chipText) }
        v.background = d
    }

    private fun process(uri: Uri) {
        if (processing) return
        processing = true
        val m = mode
        Thread {
            if (m == 0) {
                val name = VideoTools.longExposure(this, uri, 30) { done, total ->
                    runOnUiThread { statusText.text = "Long exposure: frame $done of $total" }
                }
                runOnUiThread {
                    processing = false
                    if (name != null) { statusText.text = "Saved to gallery"; toast("Long exposure saved to gallery") }
                    else statusText.text = "Failed - try a shorter video"
                }
            } else {
                val msg = VideoTools.singleTake(this, uri) { s ->
                    runOnUiThread { statusText.text = s }
                }
                runOnUiThread {
                    processing = false
                    statusText.text = msg
                    toast(msg)
                }
            }
        }.start()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
