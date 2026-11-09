package tv.nuvio.clone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val error = intent.getStringExtra("CRASH_LOG") ?: "No error log captured."

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 48, 32, 48)
        }

        val textView = TextView(this).apply {
            text = "⚠️ NUVIO CRASH REPORT\n\n$error"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        scrollView.addView(textView)
        setContentView(scrollView)
    }
}
