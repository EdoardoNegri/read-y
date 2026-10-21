package io.github.edoardonegri.ready

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Moving books and shelves by dragging, on the bookshelf of MainActivity: the long press that
 * picks them up, what the finger carries them over, and what they are dropped on, which is
 * the shelf itself, the arrow of the top rail and the board that hangs under the name plate.
 * What the bookshelf itself does with what is dropped is [Host]'s.
 */
class ShelfDragging(
    private val act: AppCompatActivity,
    private val host: Host,
    private val shelf: RecyclerView,
    private val adapter: ShelfAdapter,
    private val transition: ShelfZoom,
    private val up: View,
    private val pickStar: View,
    private val pickRead: View,
    private val bin: View,
) {
    /** What the bookshelf lets the dragging do, and tells it. */
    interface Host {
        /** The shelf shown. */
        val current: File

        /** Whether what a search found is shown, which is only chosen, not carried. */
        val found: Boolean

        /** What is chosen, to be carried together, if anything is. */
        var picked: MutableSet<File>?

        fun refresh()
        fun showRail()
        fun enter(dir: File, from: View?)
        fun goUp()

        /** The shelf this one stands on, or null for the first shelf of the library. */
        fun outside(): File?
        fun moveAll(chosen: List<File>, to: File)
        fun mark(chosen: List<File>, favorite: Boolean)
        fun askDelete(things: List<File>)
        fun chosen(): List<File>?
        fun stopPicking()
        fun spec(): ShelfSpec
    }

    /** What the finger is on while things are carried, from the long press until they are dropped. */
    var dragged: File? = null
        private set

    /** The shelf the things were picked up from, and how it stood then. */
    private var origin: File? = null
    private var before: List<File> = emptyList()

    /** Whether what is carried has taken another place on the shelf. */
    private var reordered = false

    /** Whether the things were dropped somewhere, rather than put down again where they were. */
    private var acted = false

    /** The shelf the dragged item is held over and would be put into, with its view on screen. */
    private var into: File? = null
    private var intoView: View? = null

    /** The arrow of the top rail or a button of the board under it, while the things are held over it. */
    private var aimed: View? = null

    /** When the dragged item last changed place, to keep it from flickering between two places. */
    private var lastMove = 0L

    /** -1 or 1 while the drag is held near the top or the bottom edge and the shelf scrolls along. */
    private var scrolling = 0
    private val scroller = object : Runnable {
        override fun run() {
            if (scrolling == 0 || dragged == null) return
            shelf.scrollBy(0, scrolling * host.spec().px(9))
            shelf.postOnAnimation(this)
        }
    }

    /**
     * What is carried was held over a shelf for a while: the shelf opens as it does when tapped,
     * to put it down inside.
     */
    private val opener = Runnable {
        val dir = into
        val place = intoView
        if (dir != null && place != null && dragged != null) {
            // Back to its own size at once, for the shelf to grow out of the miniature as it stands.
            place.animate().cancel()
            place.scaleX = 1f
            place.scaleY = 1f
            carry { host.enter(dir, place) }
        }
    }

    /**
     * What is carried was held over the arrow for a while: this shelf closes as it does on going
     * back, and the one it stands on is shown.
     */
    private val leaver = Runnable { if (host.outside() != null && dragged != null && aimed == up) carry(host::goUp) }

    /**
     * Picks [f] up, along with whatever was chosen before: all of it is carried as one, in the
     * place of [f]. What a search found is gathered from several shelves and stays put: it is
     * only chosen, for the star and the tick of the top rail.
     */
    fun start(f: File, from: View) {
        val set = host.picked ?: LinkedHashSet<File>().also { host.picked = it }
        set.add(f)
        if (host.found) return host.refresh()
        dragged = f
        host.showRail()
        origin = host.current
        before = adapter.files.toList()
        reordered = false
        acted = false
        // While things are carried it is the other way round from choosing them: the shelf they
        // are carried over shows in full, and they themselves let it show through. Put down
        // again, they are bright once more and the rest faint.
        (from as? ViewGroup)?.let { for (i in 0 until it.childCount) it.getChildAt(i).alpha = 1f }
        // Only the book is taken in hand, not its place on the shelf with the plaque under it.
        val book = from.findViewById<View>(R.id.cover) ?: from
        val shadow = book.snapshot()?.let { InHand(it) } ?: View.DragShadowBuilder(book)
        adapter.moving = true
        // The miniatures of the shelves no longer show what is taken from them.
        Library.carried = set.toSet()
        adapter.choose(set)
        if (!from.startDragAndDrop(null, shadow, null, View.DRAG_FLAG_OPAQUE)) {
            dragged = null
            adapter.moving = false
            Library.carried = emptySet()
            host.refresh()
            return
        }
        adapter.carried = Library.carried
        adapter.remove(set - f)
    }

    /** What is carried as it is drawn under the finger: its [picture], which lets the shelf show through. */
    private class InHand(private val picture: Bitmap) : View.DragShadowBuilder() {
        private val paint = Paint().apply { alpha = CARRIED_ALPHA }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(picture.width, picture.height)
            touch.set(picture.width / 2, picture.height / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            // A picture the drawing hardware keeps can only be drawn by it; a phone that draws
            // the shadow without it is given a copy.
            val hardware = Build.VERSION.SDK_INT >= 26 && picture.config == Bitmap.Config.HARDWARE
            val drawn = if (hardware && !canvas.isHardwareAccelerated) {
                picture.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } else {
                picture
            }
            canvas.drawBitmap(drawn, 0f, 0f, paint)
        }
    }

    /** Goes to another shelf, as [go] does, while things are carried, so that they can be put down there. */
    private fun carry(go: () -> Unit) {
        hold(null, null)
        aim(null)
        lastMove = SystemClock.uptimeMillis()
        go()
    }

    fun onShelf(e: DragEvent): Boolean {
        val f = dragged ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> hover(f, e.x, e.y)
            DragEvent.ACTION_DRAG_EXITED -> {
                hold(null, null)
                scroll(0)
            }
            DragEvent.ACTION_DROP -> {
                val all = carried(f)
                val target = into
                if (target != null) {
                    acted = true
                    host.moveAll(all, target)
                } else if (reordered || host.current != origin) {
                    acted = true
                    host.moveAll(all, host.current)
                    if (reordered) {
                        // All that is carried takes the place the finger was on, in the order it
                        // stood in. The shelf keeps the order it was just given, whatever it was
                        // sorted by before.
                        val group = before.filter { it in all } + all.filter { it !in before }
                        val order = adapter.files.mapTo(ArrayList()) { it.name }
                        val at = order.indexOf(f.name)
                        if (at >= 0) {
                            order.removeAt(at)
                            order.addAll(at, group.map { it.name })
                        }
                        Library.setOrder(act, host.current, order)
                        Library.setSort(act, Sort.MANUAL)
                    }
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** All that is carried: what was chosen, and [f], which the finger is on. */
    private fun carried(f: File): List<File> = host.chosen() ?: listOf(f)

    /**
     * The top rail: what is dropped on the arrow goes out of this shelf, to the one it stands on.
     * Held over the arrow for a while, it is taken there without being put down.
     */
    fun onRail(e: DragEvent): Boolean {
        val f = dragged ?: return false
        val out = host.outside()
        val onArrow = out != null && e.x < up.right + host.spec().px(10)
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> aim(if (onArrow) up else null)
            DragEvent.ACTION_DRAG_EXITED -> aim(null)
            DragEvent.ACTION_DROP -> if (onArrow) {
                acted = true
                host.moveAll(carried(f), out)
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /**
     * The board under the name plate: what is dropped on its star becomes a favorite, what is
     * dropped on its tick is marked as read, and what is dropped on its bin is deleted.
     */
    fun onBoard(e: DragEvent): Boolean {
        val f = dragged ?: return false
        val over = arrayOf<View>(pickStar, pickRead, bin).firstOrNull { e.x >= it.left && e.x < it.right }
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> aim(over)
            DragEvent.ACTION_DRAG_EXITED -> aim(null)
            DragEvent.ACTION_DROP -> if (over == bin) {
                acted = true
                host.askDelete(carried(f))
            } else if (over != null) {
                acted = true
                host.mark(carried(f), favorite = over == pickStar)
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    /** Shows [view], the arrow or a button of the board, as what the carried things would be dropped on; null for none. */
    private fun aim(view: View?) {
        if (view == aimed) return
        aimed?.animate()?.scaleX(1f)?.scaleY(1f)?.duration = 120
        view?.animate()?.scaleX(1.5f)?.scaleY(1.5f)?.duration = 120
        aimed = view
        up.removeCallbacks(leaver)
        if (view == up) up.postDelayed(leaver, OPEN_MS)
    }

    /** The drag is held at ([x], [y]) of the shelf: over the middle of a shelf to go into it, else to take a place. */
    private fun hover(f: File, x: Float, y: Float) {
        // While a shelf opens or closes, nothing stands yet where it will.
        if (transition.moving) return
        val edge = host.spec().px(72)
        scroll(if (y < edge) -1 else if (y > shelf.height - edge) 1 else 0)
        val child = shelf.findChildViewUnder(x, y)
        val position = if (child == null) RecyclerView.NO_POSITION else shelf.getChildAdapterPosition(child)
        val target = adapter.files.getOrNull(position)
        // The place of what is carried is empty, and nothing can be put into or before itself.
        if (child == null || target == null || target in adapter.carried) {
            hold(null, null)
            return
        }
        val across = (x - child.left) / child.width
        if (target.isFolder && across in 0.25f..0.75f) {
            hold(target, child)
            return
        }
        hold(null, null)
        val from = adapter.indexOf(f)
        val to = if (across > 0.5f) position.coerceAtLeast(from) else position.coerceAtMost(from)
        val now = SystemClock.uptimeMillis()
        if (from < 0 || to == from || now - lastMove < MOVE_MS) return
        lastMove = now
        reordered = true
        adapter.move(from, to)
    }

    /**
     * Shows [view], the shelf [target], as the one the dragged item would go into; null for none.
     * It opens if the item is held there for a while.
     */
    private fun hold(target: File?, view: View?) {
        if (view == intoView) return
        intoView?.animate()?.scaleX(1f)?.scaleY(1f)?.duration = 120
        view?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.duration = 120
        into = target
        intoView = view
        shelf.removeCallbacks(opener)
        if (target != null) shelf.postDelayed(opener, OPEN_MS)
    }

    private fun scroll(direction: Int) {
        if (direction == scrolling) return
        scrolling = direction
        shelf.removeCallbacks(scroller)
        if (direction != 0) shelf.postOnAnimation(scroller)
    }

    /** Both the shelf and the rail are told that the drag is over; whichever is first tidies up. */
    private fun endDrag() {
        if (dragged == null) return
        dragged = null
        adapter.moving = false
        adapter.carried = emptySet()
        Library.carried = emptySet()
        hold(null, null)
        scroll(0)
        aim(null)
        // What was dropped somewhere is done with. What was only picked up and put down again
        // stays chosen, so that more can be chosen and carried with it. Either way the shelf is
        // shown as it now is.
        if (acted) host.stopPicking() else host.refresh()
    }

    private companion object {
        /** How long the dragged item keeps its place before it may take another, in ms. */
        const val MOVE_MS = 180L

        /** How long what is carried is held over a shelf or the arrow before it is taken there, in ms. */
        const val OPEN_MS = 700L

        /** How much of what is carried shows over what lies under it, of 255. */
        const val CARRIED_ALPHA = 120
    }
}
