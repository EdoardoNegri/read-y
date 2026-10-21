package io.github.edoardonegri.ready

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Going into a shelf and out of it on the bookshelf: the shelf grows out of its miniature, or
 * shrinks back into it. [shelf] shows the shelf, with [adapter]; [ghost] shows a picture of the one
 * being left meanwhile. [spec] is how the shelves are laid out.
 */
class ShelfZoom(
    private val act: Activity,
    private val shelf: RecyclerView,
    private val ghost: ImageView,
    private val adapter: ShelfAdapter,
    private val spec: () -> ShelfSpec,
) {

    /** Whether a shelf is going in or out, or is being waited for to do so: nothing stands yet where it will. */
    val moving get() = ghost.isVisible

    /**
     * A picture of the shelf as it is, in full colour: with fewer, the soft shading of the back wall
     * comes out in stripes. Without the [bars] of how far the books are read, if asked.
     */
    fun snapshot(bars: Boolean = true): Bitmap? {
        if (bars) return shelf.snapshot()
        adapter.showBars(shelf, 0f)
        return shelf.snapshot().also { adapter.showBars(shelf, 1f) }
    }

    /** Where a folder's miniature sits on the shelf: the scale and pivot that map the full shelf onto it. */
    class Miniature(val px: Float, val py: Float, val scale: Float)

    /** Where the miniature [thumbnail] sits on the shelf. */
    fun locate(thumbnail: View): Miniature {
        val at = IntArray(2)
        val origin = IntArray(2)
        thumbnail.getLocationInWindow(at)
        shelf.getLocationInWindow(origin)
        // The picture fills its view, and starts where the shelf does: under the top rail.
        val scale = (thumbnail.width.toFloat() / shelf.width).coerceIn(0.05f, 0.95f)
        return Miniature((at[0] - origin[0]) / (1 - scale), (at[1] - origin[1]) / (1 - scale), scale)
    }

    /**
     * Folder transition: a pure change of size. A miniature is its folder's case drawn small, its
     * first rows from under the top rail down to where the books of the last one stand, so the folder's shelf (always the upper, opaque
     * layer) starts exactly on it, cropped to the same rows, and is enlarged until it fills the screen while the crop opens up. The
     * parent shelf zooms in step underneath. Closing plays the same thing backwards.
     * [snapshot] is the shelf being left; the live list shows the one being entered. The bars of
     * how far the books are read, which the miniature does not show, come as the folder grows, and
     * go as it shrinks: closing, the folder is [snapshot] over [bare], a picture of it without them.
     */
    fun zoom(m: Miniature, snapshot: Bitmap, opening: Boolean, bare: Bitmap? = null) {
        shelf.animate().cancel()
        ghost.animate().cancel()
        val barred = if (opening || bare == null) null else snapshot.toDrawable(act.resources).also {
            ghost.setImageDrawable(LayerDrawable(arrayOf(bare.toDrawable(act.resources), it)))
        }
        if (barred == null) ghost.setImageBitmap(snapshot)
        if (opening) adapter.showBars(shelf, 0f)
        ghost.visibility = View.VISIBLE
        for (v in arrayOf(shelf, ghost)) {
            v.pivotX = m.px
            v.pivotY = m.py
            v.alpha = 1f
            v.clipBounds = null
        }
        // Opening: the list is the folder (on top), the snapshot is the parent (below). Closing: the reverse.
        val folder: View = if (opening) shelf else ghost
        val w = shelf.width
        val h = shelf.height
        val inset = shelf.paddingTop
        val cropped = (inset + spec().miniH).coerceAtMost(h)
        val crop = if (opening) Rect(0, inset, w, cropped) else Rect(0, 0, w, h)
        folder.clipBounds = crop
        ghost.translationZ = if (opening) -1f else 1f
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        shelf.scaleX = if (opening) m.scale else 1 / m.scale
        shelf.scaleY = shelf.scaleX

        val curve = AccelerateDecelerateInterpolator()
        val ghostTo = if (opening) 1 / m.scale else m.scale
        val shelfMove = shelf.animate().scaleX(1f).scaleY(1f)
        val ghostMove = ghost.animate().scaleX(ghostTo).scaleY(ghostTo)
        for (move in arrayOf(shelfMove, ghostMove)) {
            move.setDuration(ZOOM_MS).setInterpolator(curve).setUpdateListener(null).withLayer()
        }
        (if (opening) shelfMove else ghostMove).setUpdateListener { a ->
            val t = if (opening) a.animatedFraction else 1 - a.animatedFraction
            crop.top = (inset * (1 - t)).toInt()
            crop.bottom = (cropped + (h - cropped) * t).toInt()
            folder.clipBounds = crop
            if (opening) adapter.showBars(shelf, t) else barred?.alpha = (t * 255).toInt()
        }
        ghostMove.withEndAction {
            shelf.clipBounds = null
            ghost.clipBounds = null
            hide()
        }
        shelfMove.start()
        ghostMove.start()
    }

    /** Shows [snapshot], of the shelf being left, over the one coming, until the two change places (see [zoom]). */
    fun cover(snapshot: Bitmap) {
        shelf.animate().cancel()
        ghost.animate().cancel()
        adapter.showBars(shelf, 1f)
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        ghost.alpha = 1f
        ghost.clipBounds = null
        ghost.translationZ = 1f
        ghost.setImageBitmap(snapshot)
        ghost.visibility = View.VISIBLE
    }

    /**
     * Calls [then] once every place in view on the shelf shows its cover or miniature, which load
     * in the background; or after [SHOWN_WAIT_MS] at most, for any that will not.
     */
    fun whenShown(then: () -> Unit) {
        val until = SystemClock.uptimeMillis() + SHOWN_WAIT_MS
        fun check() {
            if (act.isDestroyed) return
            if (adapter.allShown(shelf) || SystemClock.uptimeMillis() >= until) then() else shelf.postOnAnimation { check() }
        }
        check()
    }

    /** Takes the picture of the shelf left away, and shows the shelf as it is. */
    fun hide() {
        adapter.showBars(shelf, 1f)
        ghost.visibility = View.GONE
        ghost.setImageBitmap(null)
    }

    private companion object {
        const val ZOOM_MS = 560L

        /** The longest a change of shelf waits for the covers of the one coming to be shown, see whenShown. */
        const val SHOWN_WAIT_MS = 400L
    }
}
