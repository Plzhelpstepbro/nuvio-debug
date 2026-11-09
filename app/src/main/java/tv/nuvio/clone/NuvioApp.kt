package tv.nuvio.clone

import android.app.Application
import android.content.Intent

class NuvioApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Catches crashes across the ENTIRE app (Compose, Retrofit, Room, System)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val intent = Intent(this, CrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("CRASH_LOG", throwable.stackTraceToString())
            }
            startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}
