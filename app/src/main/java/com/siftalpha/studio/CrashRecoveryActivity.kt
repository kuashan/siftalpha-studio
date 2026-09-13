package com.siftalpha.studio

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Local-only screen shown after a previous process crash. */
class CrashRecoveryActivity : StudioActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 24)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(24)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_title); textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.START
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_privacy); textSize = 13f; setTextColor(Color.rgb(190, 194, 204)); setPadding(0, dp(8), 0, dp(12))
        })
        val report = StudioApplication.readPendingReport(this) ?: "SIFTALPHA_DEVICE_CRASH_REPORT=EMPTY"
        root.addView(TextView(this).apply {
            text = report; textSize = 12f; setTextColor(Color.rgb(224, 228, 236)); setBackgroundColor(Color.rgb(24, 28, 35)); typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        root.addView(Button(this).apply {
            text = getString(R.string.crash_back); isAllCaps = false
            setOnClickListener { StudioApplication.clearPendingReport(this@CrashRecoveryActivity); finish() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        })
        setContentView(scroll)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
