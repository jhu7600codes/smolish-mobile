package com.smolish

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Developer tool (only shown to @jhu): plays a holiday's launch like a real cold start, first the
 * system splash icon on its own, then the intro and the bouncing cube. Tap to play it again.
 */
class SplashTestActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KEY = "key"
    }

    private lateinit var root: FrameLayout
    private val key by lazy { intent.getStringExtra(EXTRA_KEY) ?: "default" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        setContentView(root)
        root.setOnClickListener { play() }
        play()
    }

    private fun play() {
        root.removeAllViews()
        root.handler?.removeCallbacksAndMessages(null)
        // what the system splash shows: the theme's icon, 288dp, in the middle
        val icon = obtainStyledAttributes(Icons.splashTheme(key), intArrayOf(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon))
            .run { getDrawable(0).also { recycle() } }
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 288f, resources.displayMetrics).toInt()
        root.addView(ImageView(this).apply { setImageDrawable(icon) }, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
        root.addView(TextView(this).apply {
            text = key
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setPadding(0, 0, 0, px / 4)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // then the app takes over, like after a real launch
        root.postDelayed({
            if (isFinishing) return@postDelayed
            root.removeViewAt(0)
            root.addView(BouncingSplashView(this).apply { theme = key }, 0, FrameLayout.LayoutParams(-1, -1))
        }, 700)
    }
}
