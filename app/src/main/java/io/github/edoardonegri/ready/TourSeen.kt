package io.github.edoardonegri.ready

import android.content.Context
import androidx.core.content.edit

/*
 * Whether each tour (see Tutorial) was shown before. It is kept apart from the tour itself, so that
 * the tour, which is a good deal of code, is not even loaded on the starts after it was shown.
 */

private const val TOUR_PREFS = "tutorial"

/** Whether the tour [key] was shown, and need not be again. */
fun tourSeen(ctx: Context, key: String) = ctx.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

/** Notes that the tour [key] was shown. */
fun tourDone(ctx: Context, key: String) = ctx.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).edit { putBoolean(key, true) }

private const val SAMPLES = "samples"

/** Whether the book and the shelf made for the tour (see TourSamples) may still be there. */
fun tourSamplesPending(ctx: Context) = ctx.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).getBoolean(SAMPLES, false)

fun setTourSamplesPending(ctx: Context, pending: Boolean) = ctx.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE).edit { putBoolean(SAMPLES, pending) }
