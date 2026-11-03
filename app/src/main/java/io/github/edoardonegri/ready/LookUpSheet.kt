package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams

/**
 * What comes up when a word is held on the page: a plank that slides up from the foot of the
 * reader as its menu does, with "Search:" and the word on a title plate, as a dialog has its name,
 * and under it the apps it can be looked up in, by their pictures alone. Each stands on a brass
 * plate held by a screw at either end, the plates all alike, and the pictures all as large,
 * whatever size each app gives its own, with as much brass around a picture at its sides as above
 * and below it. A tap beside the plank, or Back, takes it away.
 */
@SuppressLint("ViewConstructor")
class LookUpSheet(ctx: Context) : FrameLayout(ctx) {
    private val d = resources.density
    private val plank = LinearLayout(ctx)
    private val word = TextView(ContextThemeWrapper(ctx, R.style.TitlePlate), null, 0, R.style.TitlePlate)
    private val apps = LinearLayout(ctx)

    /** Whether it is sliding away: still seen, but closed for all else. */
    private var closing = false

    /** Whether it is up, and not on its way out. */
    val open get() = isVisible && !closing

    init {
        visibility = INVISIBLE
        // The page around it is not for touching while it is up: a tap there takes it away.
        setOnClickListener { hide(slide = true) }
        plank.orientation = LinearLayout.VERTICAL
        plank.setBackgroundResource(R.drawable.shelf_plank)
        plank.isClickable = true
        plank.setPadding(0, 0, 0, ctx.dp(16))

        // The word on the ornate title plate of a dialog (dialog_shelf.xml), as wide as the room
        // between the buttons of a rail would leave it, whatever it says; see [show].
        word.background = PlaqueDrawable(d, ornate = true)
        val top = FrameLayout(ctx).apply {
            setPadding(ctx.dp(RAIL_DP), 0, ctx.dp(RAIL_DP), 0)
            addView(word, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(45), Gravity.CENTER).apply {
                marginStart = ctx.dp(PLATE_MARGIN_DP)
                marginEnd = ctx.dp(PLATE_MARGIN_DP)
            })
        }
        plank.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(62)))

        // The plates in a row in the middle, which scrolls if there are more than the plank holds.
        apps.gravity = Gravity.CENTER_HORIZONTAL
        apps.setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), 0)
        val row = HorizontalScrollView(ctx).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(apps, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        plank.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(plank, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /**
     * Slides up with [text] on its plaque and a plate for each of [choices], one of which [onPick]
     * is told of when it is tapped. [left] and [right] are kept clear at the sides, for a camera there.
     */
    fun show(text: String, choices: List<WordLookUp>, left: Int, right: Int, onPick: (WordLookUp) -> Unit) {
        word.text = context.getString(R.string.look_up_title, text)
        word.contentDescription = context.getString(R.string.look_up, text)
        plank.setPadding(left, 0, right, plank.paddingBottom)
        // On a screen on its side the plate is as wide as it is on the screen upright, not
        // stretched across all of it: the narrower side of the screen is the width upright. It is
        // never wider than the room the plank leaves it, as in a window shared with another app.
        val config = resources.configuration
        val aside = 2 * (RAIL_DP + PLATE_MARGIN_DP)
        word.updateLayoutParams {
            width = minOf(context.dp(config.smallestScreenWidthDp - aside), context.dp(config.screenWidthDp - aside) - left - right).coerceAtLeast(0)
        }
        apps.removeAllViews()
        choices.forEachIndexed { i, app -> apps.addView(plate(app, onPick), plateParams(i)) }
        plank.animate().cancel()
        closing = false
        visibility = VISIBLE
        // Out of sight until it is laid out with what it now holds, and then slid up from there.
        plank.translationY = resources.displayMetrics.heightPixels.toFloat()
        plank.doOnPreDraw {
            plank.translationY = plank.height.toFloat()
            plank.animate().translationY(0f).setDuration(IN_MS).setInterpolator(IN).withLayer()
        }
    }

    /** Takes it away: at once, or, to [slide], sliding down out of sight. */
    fun hide(slide: Boolean = false) {
        if (!isVisible || slide && closing) return
        plank.animate().cancel()
        if (!slide || plank.height == 0) return gone()
        closing = true
        plank.animate().translationY(plank.height.toFloat()).setDuration(OUT_MS).setInterpolator(OUT).withLayer()
            .withEndAction(::gone)
    }

    private fun gone() {
        closing = false
        visibility = INVISIBLE
        plank.translationY = 0f
    }

    /** The brass plate of [app], with its picture in the middle, between the screws. */
    private fun plate(app: WordLookUp, onPick: (WordLookUp) -> Unit) = FrameLayout(context).apply {
        background = PlaqueDrawable(d)
        foreground = ContextCompat.getDrawable(context, TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId)
        contentDescription = app.label
        tooltipText = app.label
        isFocusable = true
        setOnClickListener { onPick(app) }
        val icon = ImageView(context).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // The plate casts its shadow below its face, which the picture is in the middle of.
        addView(icon, LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP), Gravity.CENTER).apply { bottomMargin = context.dp(PlaqueDrawable.SHADOW_DP) })
    }

    private fun plateParams(i: Int) = LinearLayout.LayoutParams(context.dp(PLATE_WIDTH_DP), context.dp(PLATE_HEIGHT_DP)).apply {
        if (i > 0) marginStart = context.dp(12)
    }

    private companion object {
        /** The room left at either side of the title plate, where a rail has its buttons, and the plate's own margin, in dp. */
        const val RAIL_DP = 48
        const val PLATE_MARGIN_DP = 4

        /**
         * How large the picture on a plate is, and the brass around it on every side, in dp: the
         * same for every app. The screws at the ends stand in that brass, clear of the picture.
         */
        const val ICON_DP = 32
        const val RIM_DP = 11

        /** How large a plate is, in dp: its face as high as it is wide, and its shadow under it. */
        const val PLATE_WIDTH_DP = ICON_DP + 2 * RIM_DP
        const val PLATE_HEIGHT_DP = PLATE_WIDTH_DP + PlaqueDrawable.SHADOW_DP

        /** As the menu of the reader slides (ReaderActivity.showMenu). */
        const val IN_MS = 160L
        const val OUT_MS = 120L
        val IN = DecelerateInterpolator(1.5f)
        val OUT = AccelerateInterpolator(1.5f)
    }
}
