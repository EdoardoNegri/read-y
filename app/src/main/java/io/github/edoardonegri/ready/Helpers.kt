package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
import android.widget.Toast

/*
 * Small helpers that many parts of the app use alike, kept in one place rather than copied into each.
 */

/** How many pixels make a dp on this screen. */
val Resources.density: Float get() = displayMetrics.density

/** [v] dp in whole pixels. */
fun Context.dp(v: Int) = (v * resources.density).toInt()

/** [v] dp in whole pixels, for a view that lays out or draws itself. */
fun View.dp(v: Int) = context.dp(v)

/** Shows [text] briefly, or for longer if it is [long]. */
fun Context.toast(text: Int, long: Boolean = false) =
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Context.toast(text: CharSequence, long: Boolean = false) =
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** Whether the activity is going away, so that what it shows is better let be. */
val Activity.closing: Boolean get() = isFinishing || isDestroyed

/**
 * Runs [action] on the main thread, unless the activity is closing by then: for work done in the
 * background that ends by showing something.
 */
fun Activity.whileOpen(action: () -> Unit) = runOnUiThread { if (!closing) action() }

/**
 * Numbers the searches [act] starts in the background, so that a slow one cannot show what it
 * found over what a later one did.
 */
class Searches(private val act: Activity) {
    private var started = 0

    /** Starts a search, and gives its number; what the searches before it find is not shown any more. */
    fun start(): Int = ++started

    /** Runs [action] on the main thread, if the search [run] is still the last one started and [act] is still there. */
    fun ifLatest(run: Int, action: () -> Unit) = act.runOnUiThread { if (run == started && !act.isDestroyed) action() }
}

/** The names of the app's preference files, each read with [prefs]. */
object Prefs {
    const val LIBRARY = "library"
    const val STATE = "state"
    const val META = "meta"
    const val READER = "reader"
    const val WIDGET = "widget"
    const val TOUR = "tutorial"
    const val END_MENU = "endmenu"
    const val STORE = "store"
}

/** The app's preference file [name], one of [Prefs]. */
fun Context.prefs(name: String): SharedPreferences = applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
