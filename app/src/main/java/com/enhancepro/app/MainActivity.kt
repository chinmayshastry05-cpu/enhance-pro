package com.enhancepro.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val i = Intent(this, EditorActivity::class.java)
            i.putExtra(EditorActivity.EXTRA_URI, uri.toString())
            startActivity(i)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val i = Intent(this, VideoActivity::class.java)
            i.putExtra(VideoActivity.EXTRA_URI, uri.toString())
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        window.statusBarColor = Color.parseColor("#0D0D0F")

        val bg = Color.parseColor("#0D0D0F")
        val gold = Color.parseColor("#E8A33D")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "Enhance Pro"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "One tap. Better photos and videos.\nFully on your phone - nothing leaves it."
            setTextColor(Color.parseColor("#9A9AA2"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        val btn = TextView(this).apply {
            text = "Pick a photo"
            setTextColor(Color.parseColor("#0D0D0F"))
            textSize = 17f
            gravity = Gravity.CENTER
            val d = GradientDrawable()
            d.setColor(gold)
            d.cornerRadius = 60f
            background = d
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140)
            lp.topMargin = 120
            layoutParams = lp
            setOnClickListener { pickImage.launch("image/*") }
        }

        val btn2 = TextView(this).apply {
            text = "Pick a video"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            val d = GradientDrawable()
            d.setColor(Color.parseColor("#1C1C22"))
            d.cornerRadius = 60f
            background = d
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140)
            lp.topMargin = 30
            layoutParams = lp
            setOnClickListener { pickVideo.launch("video/*") }
        }

        root.addView(title)
        root.addView(sub)
        root.addView(btn)
        root.addView(btn2)
        setContentView(root)
    }
}
