package com.example.osimsofa

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Owns the sofa session for the whole process, so turning the phone or a new Activity never
 * reconnects or restarts anything (improve-plan §7.2). The app counts as "minimised" when no
 * Activity is visible any more (ProcessLifecycleOwner: Home, another app, screen off) — not for a
 * rotation or a permission dialog.
 */
class SofaApplication : Application() {

    lateinit var conn: SofaConnection; private set
    lateinit var controller: SofaController; private set
    lateinit var speaker: Speaker; private set

    override fun onCreate() {
        super.onCreate()
        conn = SofaConnection(this)
        controller = SofaController(conn, getSharedPreferences("sofa", Context.MODE_PRIVATE))
        speaker = Speaker(this) { conn.log(it) }
        controller.onEvent = { e ->
            if (e.speak) speaker.say(e.text, interrupt = e.problem)
            SofaService.update(this, e.text)
        }
        conn.onOperable = {
            controller.onOperable()
            SofaService.start(this)
        }
        conn.onClosed = { SofaService.stop(this) }
        // The return after minimising may run with the screen off: keep the CPU up meanwhile, or
        // the preset stream would pause and resume whenever the phone wakes (capped at 2 minutes).
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uDivaSofa:return").apply { setReferenceCounted(false) }
        controller.keepAwake = { on -> if (on) wake.acquire(2 * 60_000L) else if (wake.isHeld) wake.release() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = controller.onAppForeground()
            override fun onStop(owner: LifecycleOwner) = controller.onAppBackground()
        })
    }
}
