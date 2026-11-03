package app.mapa.parent

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import app.mapa.parent.collect.MotionSignal
import app.mapa.parent.collect.PowerReceiver
import app.mapa.parent.collect.Scheduler
import app.mapa.parent.collect.UnlockReceiver
import app.mapa.shared.Backend

class ParentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (app.mapa.parent.BuildConfig.JUDGE_DEMO_ENABLED) return
        Backend.init(this)
        if (Prefs(this).consented) start(this)
    }

    companion object {
        private var unlockRegistered = false

        /** Starts collection. Called only after the parent has consented. */
        @Synchronized
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            Scheduler.ensure(app)
            MotionSignal.start(app)
            java.io.File(app.filesDir, "models").deleteRecursively() // old on-phone note model, now unused
            if (!unlockRegistered) {
                unlockRegistered = true
                ContextCompat.registerReceiver(app, UnlockReceiver(), UnlockReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
                ContextCompat.registerReceiver(app, PowerReceiver(), PowerReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }
}
