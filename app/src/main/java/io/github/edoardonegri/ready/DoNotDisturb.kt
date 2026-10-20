package io.github.edoardonegri.ready

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Silences the phone while a book is open, as Do Not Disturb does, letting through only what the
 * reader lets through it. The app may do so only once the reader has given it Do Not Disturb
 * access, which the phone's own settings alone can give ([ask]). From Android 15 on, the phone
 * keeps the silence as a mode of the app's own, which ending leaves the reader's own Do Not
 * Disturb alone; before that, the app only ends a silence it began itself.
 */
object DoNotDisturb {

    /** The silence was begun by the app, and is to be ended by it. */
    private var ours = false

    private fun manager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /** Whether the reader has given the app Do Not Disturb access. */
    fun allowed(ctx: Context) = manager(ctx)?.isNotificationPolicyAccessGranted == true

    /** Opens the phone's settings where the app can be given Do Not Disturb access. */
    fun ask(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Some phones have no such settings, and the app is never let silence them.
        }
    }

    fun start(ctx: Context) {
        val nm = manager(ctx) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        // The reader's own silence is theirs to end, before the app has a mode of its own.
        if (Build.VERSION.SDK_INT < 35 && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        ours = true
    }

    fun stop(ctx: Context) {
        if (!ours) return
        ours = false
        val nm = manager(ctx) ?: return
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }
}
