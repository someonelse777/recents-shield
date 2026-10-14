package com.recentsshield

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small on-phone log of service starts/stops, errors and crashes, shown in diagnostics. */
object Diag {
    private const val FILE = "events.log"
    private const val MAX_BYTES = 40_000

    private fun file(c: Context) = File(c.filesDir, FILE)

    @Synchronized
    fun log(c: Context, msg: String, t: Throwable? = null) {
        try {
            val f = file(c)
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep)
            }
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder("$time  $msg\n")
            if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString().lineSequence().take(25).joinToString("\n")).append('\n')
            }
            f.appendText(sb.toString())
        } catch (_: Throwable) {}
    }

    fun read(c: Context): String = try { file(c).takeIf { it.exists() }?.readText() ?: "" } catch (_: Throwable) { "" }
}

/** Records crashes to the log before Android takes the process down. */
class ShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.log(this, "App process started")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Diag.log(this, "CRASH on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }
}
