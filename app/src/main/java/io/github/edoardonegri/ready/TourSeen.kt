package io.github.edoardonegri.ready

import android.content.Context
import androidx.core.content.edit

/*
 * Whether each tour (see Tutorial) was shown before. It is kept apart from the tour itself, so that
 * the tour, which is a good deal of code, is not even loaded on the starts after it was shown.
 */

/** Whether the tour [key] was shown, and need not be again. */
fun tourSeen(ctx: Context, key: String) = ctx.prefs(Prefs.TOUR).getBoolean(key, false)

/** Notes that the tour [key] was shown. */
fun tourDone(ctx: Context, key: String) = ctx.prefs(Prefs.TOUR).edit { putBoolean(key, true) }

private const val SAMPLES = "samples"
private const val MADE = "samples_made"

/** Whether the book and the shelf made for the tour (see TourSamples) may still be there. */
fun tourSamplesPending(ctx: Context) = ctx.prefs(Prefs.TOUR).getBoolean(SAMPLES, false)

/**
 * The names of the book and the shelf the tour made itself, which are the only ones it takes away:
 * the reader's own of the same name are left. Null where a tour of before this was noted left them.
 */
fun tourSamplesMade(ctx: Context): Set<String>? = ctx.prefs(Prefs.TOUR).getStringSet(MADE, null)

/** Notes [made] as what the tour made itself, which may still be there while it is not empty. */
fun setTourSamplesMade(ctx: Context, made: Set<String>) = ctx.prefs(Prefs.TOUR).edit {
    putStringSet(MADE, HashSet(made))
    putBoolean(SAMPLES, made.isNotEmpty())
}
