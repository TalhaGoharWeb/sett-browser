package app.privatex.browser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class PrivateXApp : Application() {
    val components by lazy { Components(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs its GPU/content work in child processes of this
        // app (":gpu", ":content", ...). Android runs Application.onCreate
        // in EVERY process, and building the components here would create
        // a GeckoRuntime inside a child process — which is fatal and kills
        // the app right after launch. Only the main process initialises.
        if (!isMainProcess()) return
        CrashLog.install(applicationContext)
        // Clear-on-exit fallback: if the previous session died before
        // its exit-clear ran, sweep now — before the store below can
        // restore anything.
        components.performStartupSweepIfNeeded()
        // Persist tabs automatically: periodically in the foreground, on
        // backgrounding, and whenever the session set changes.
        with(components) {
            sessionStorage.autoSave(store).apply {
                periodicallyInForeground()
                whenGoingToBackground()
                whenSessionsChange()
            }
        }
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }
        return processName == packageName
    }
}

/**
 * Last-crash recorder, fully local: an uncaught exception's stack trace is
 * written to the app's private files directory and shown to the user on the
 * next launch (BrowserActivity), where they can copy or share it. Nothing
 * is ever transmitted anywhere by the app itself.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString()
                File(context.filesDir, FILE_NAME).writeText(
                    "Private X crash — thread: ${thread.name}\n\n$trace",
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private const val CAUGHT_FILE_NAME = "last_caught.txt"

    /**
     * Records a caught (non-fatal) exception reported by a library —
     * used as the CrashReporting backend for Mozilla's storage layer.
     * Local file only, overwritten each time; never transmitted.
     */
    fun recordCaught(context: Context, throwable: Throwable) {
        runCatching {
            val trace = StringWriter().also { sw ->
                throwable.printStackTrace(PrintWriter(sw))
            }.toString()
            File(context.filesDir, CAUGHT_FILE_NAME).writeText(
                "Caught exception (non-fatal)\n\n$trace",
            )
        }
    }

    /** Returns the stored crash trace, if any, and clears it. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}

val Context.components: Components
    get() = (applicationContext as PrivateXApp).components
