package app.privatex.browser

import android.app.Application
import android.content.Context

class PrivateXApp : Application() {
    val components by lazy { Components(applicationContext) }

    override fun onCreate() {
        super.onCreate()
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
}

val Context.components: Components
    get() = (applicationContext as PrivateXApp).components
