package io.github.edoardonegri.ready

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import androidx.core.graphics.withClip
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Opening a book from the shelf: its cover swings open on its spine, and the page behind it grows
 * until it fills the screen, where the reader takes over without a seam. Closing plays the same
 * backwards, starting from a picture of the page that was being read ([Left]). All of it is drawn
 * in a layer over the whole of [act], which holds the book at the size of the screen and shows it
 * as small as its place on the shelf; [attach] puts the layer elsewhere than over the activity's
 * content: in a window of its own (see [Detached]), or under the reader's menu.
 */
class BookOpening(private val act: Activity, attach: ((View) -> Unit)? = null) {

    /** Whether the layer was put elsewhere than over the activity's content, see [attach]. */
    private val detached = attach != null

    /**
     * The page a book was at when the reader left it for the shelf, for the shelf to close the book
     * from; left on the cover that ends it, with the [menu] standing under the cover, which goes
     * back into its row as the book closes.
     */
    class Left(val file: File, val page: Bitmap, val at: Long = SystemClock.uptimeMillis(), val menu: EndMenu? = null)

    /**
     * Where a cover stands on the screen, and what it looks like: [picture], laid over the cover
     * by [fit] if given, else stretched over it, with the [binding] of a book on the shelf over it.
     * [row] is the plank it stands on, if it stands on a shelf; [scene], the shelf around it, for a
     * book that only grows out of it (see [scene]).
     */
    class Spot(
        val x: Int, val y: Int, val w: Int, val h: Int, val picture: Bitmap, val fit: Matrix? = null, val binding: Boolean = false,
        val row: Row? = null,
    ) {
        var scene: Scene? = null
    }

    /**
     * The plank of a shelf a cover stands on: the part of its front [face] under the cover, on the
     * screen. The [plaque] that names the book stands on it, pictured, [plaqueAt] on the screen,
     * made as a [PlaqueDrawable] is for a [favorite] or [finished] book, with the [title] written on
     * it [plaqueText] large, [plaqueInset] in from its ends. [shelved] are the cover and the plaque
     * on the shelf, which the book coming out of them or going back into them stands for.
     */
    class Row(
        val face: Rect, val plaque: Bitmap?, val plaqueAt: Rect,
        val title: CharSequence, val plaqueText: Float, val plaqueInset: Float, val favorite: Boolean, val finished: Boolean,
        val shelved: List<View>,
    )

    /** The shelf a cover stands in, as the screen shows it: [picture], [at] on the screen, without the book itself. */
    class Scene(val picture: Bitmap, val at: Rect)

    /**
     * The reader's menu as it stands over the cover that ends a book, on a screen [screenW] by
     * [screenH]: [height] is the room it takes at the foot of the screen, and [title] is where the
     * plaque with the name of the book is on it, the name written [titleText] large, [titleInset] in
     * from its ends. [picture] is the menu pictured, as high as it is, without the plaque, and
     * [plaque] the plaque pictured on its own; neither, while it is only known where the menu is.
     */
    class EndMenu(
        val screenW: Int, val screenH: Int, val height: Int, val title: RectF, val titleText: Float, val titleInset: Float,
        val picture: Bitmap? = null, val plaque: Bitmap? = null,
    ) {
        /** Whether [other] stands where this one does, on a screen as large. */
        fun samePlace(other: EndMenu) = screenW == other.screenW && screenH == other.screenH && height == other.height && title == other.title
    }

    private val layer = FrameLayout(act)
    private val book = FrameLayout(act)
    private val paper = View(act)

    /**
     * The wall of the case a cover that only grows stands against at the end, over the whole
     * screen, which comes over the shelf as the cover nears its place.
     */
    private val wall = View(act).apply { visibility = View.GONE }

    /**
     * The shelf a cover that only grows comes out of, as if looked at ever closer: what is over the
     * book and under its row grows with the book, and goes off the screen, and its row grows into
     * the menu (see [ZoomedShelf]). The wall comes over it as the book nears its place.
     */
    private val scene = ZoomedShelf(act).apply { visibility = View.GONE }
    private val page = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }

    /**
     * The cover as it swings open on its spine: its front until it stands edge-on to the eye,
     * where it can no longer be seen; past that, its board shows its inside ([Edge]).
     */
    private val cover = Cover(act).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        // A cover cropped to its place does not spill out of it.
        cropToPadding = true
    }

    /**
     * A cover with the [binding] the shelf draws over it (ShelfArt.drawBinding), which goes with it
     * as it swings open, or grows; it is as large as the cover makes it.
     */
    private class Cover(ctx: Activity) : AppCompatImageView(ctx) {
        private val paint = Paint()
        private val area = RectF()
        var binding by changes(false, ::invalidate)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!binding) return
            // Over the picture, which is drawn within the padding.
            area.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
            ShelfArt.drawBinding(canvas, area, paint)
        }
    }

    /** The board of the cover, which shows its edge as it swings, and its inside once past upright. */
    private val edge = Edge(act)
    /** What moves the book along, frame by frame, while it opens or closes; see [run]. */
    private var ticker: Choreographer.FrameCallback? = null

    /** Whether the page has its print, see [print]. */
    private var printed = false

    /**
     * Whether the book is only enlarged out of its place on the shelf, its cover as it is, without
     * opening: it ends as the cover that ends a book read to its end, over the whole screen. The
     * book is as large as the screen, and is made as small, [growScale], as the cover on the shelf;
     * [growFrom] is where the cover is in it, which the book is cut down to, as far as it grows.
     */
    private var grown = false
    private var growScale = 1f
    private var growX = 0f
    private var growY = 0f
    private val growFrom = Rect()

    /** The part of the cover the shelf shows, cut to the shape of its place there, in the book: all there is to see of it at first. */
    private val growSlot = RectF()
    private val clip = Rect()

    /** How high the menu of the reader is that a cover that only grows stands on at the end ([endMenu]); 0 for none. */
    private var menuHeight = 0

    /**
     * The plank of the row a cover that only grows stands on, which grows with it into the plank of
     * the menu, the cover standing on it all the way: from [plankFrom], the front face of the plank
     * under the cover on the shelf, to the foot of the screen, where the menu comes onto it. The
     * plaque of the book rides on it from [plaqueFrom] to [plaqueTo], where the menu has the name
     * of the book, and becomes that name.
     */
    private val plank = RisingPlank(act).apply { visibility = View.GONE }
    private val plankFrom = Rect()
    private val plaqueFrom = RectF()
    private val plaqueTo = RectF()
    private var endMenu: EndMenu? = null

    /** Where the cover is on the shelf, within the layer. */
    private var x = 0f
    private var y = 0f
    private var w = 1f
    private var h = 1f

    /** What the layer covers: the activity's content, or the whole of the window it has to itself. */
    private val frame: View get() = layer.parent as? View ?: layer

    init {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        for (v in arrayOf(paper, page, edge, cover)) book.addView(v, fill, fill)
        // The open cover lies beside the book.
        for (group in arrayOf(layer, book)) {
            group.clipChildren = false
            group.clipToPadding = false
        }
        layer.addView(scene, fill, fill)
        layer.addView(wall, fill, fill)
        layer.addView(book, fill, fill)
        layer.addView(plank, fill, fill)
        // Nothing under the layer is touched while it shows.
        layer.isClickable = true
        if (attach != null) {
            attach(layer)
        } else {
            layer.visibility = View.GONE
            act.addContentView(layer, ViewGroup.LayoutParams(fill, fill))
        }
    }

    /**
     * Opens the book whose cover is shown by [from], on paper of the colour [paperColor] with
     * [print] on it, a picture of the page as the reader shows it, if there is one; [then] is
     * called when the page fills the screen, which it goes on doing until [clear].
     */
    fun open(
        from: View, paperColor: Int, print: Bitmap?, grown: Boolean = false, menu: EndMenu? = null, then: () -> Unit,
    ) {
        val spot = spot(from) ?: return then()
        if (grown) spot.scene = pictureScene(from, spot.row)
        open(spot, paperColor, print, grown, menu, then)
    }

    /**
     * Opens the book whose cover stands at [spot], as [open] does. A [grown] book is a cover that
     * only grows, to stand over the screen as the cover that ends the book, and shows no page: the
     * plank of its row grows with it into the plank of the reader's [menu], pictured, which comes
     * onto it at the end, and the cover stands on it all the way, against the wall of the case.
     */
    fun open(
        spot: Spot, paperColor: Int, print: Bitmap?, grown: Boolean = false, menu: EndMenu? = null, then: () -> Unit,
    ) {
        ticker = null
        sliding = false
        this.grown = grown
        book.clipBounds = null
        endMenu = if (grown) menu else null
        menuHeight = endMenu?.height ?: 0
        plank.visibility = if (menuHeight > 0) View.VISIBLE else View.GONE
        if (menuHeight > 0) plank.set(spot.row, endMenu)
        pageIsCover = false
        page.setPadding(0, 0, 0, 0)
        paper(grown, paperColor)
        page.animate().cancel()
        page.alpha = 1f
        // A cover that grows has no page: the print the reader sends later, the cover over the menu
        // as the reader shows it, is what the cover comes to be as it ends (see pose).
        page.setImageBitmap(print)
        printed = print != null
        printedAt = 0f
        cover.alpha = 1f
        cover.visibility = View.VISIBLE
        edge.visibility = if (grown) View.GONE else View.VISIBLE
        sized {
            if (!place(spot)) return@sized then()
            pose(0f)
            show()
            run(0f, 1f, then)
        }
    }

    /**
     * Shows [left], the page a book was left at, over the whole screen. Once the
     * shelf is laid out, [find] gives the cover of that book on it, and the page shrinks back
     * into the book, which closes: the opening played backwards. Without a cover to go to, the
     * page just fades.
     */
    fun close(left: Bitmap, paperColor: Int, grown: Boolean = false, menu: EndMenu? = null, find: () -> View?) {
        ticker = null
        sliding = false
        paper(grown, paperColor)
        page.setImageBitmap(left)
        page.alpha = 1f
        // Shown as it was left, over the whole screen, until the shelf is laid out.
        this.grown = false
        book.clipBounds = null
        // A book left on its cover, at its end, only shrinks into its place: the opening of a grown
        // book played backwards, the menu going back into the row it came from.
        endMenu = if (grown) menu else null
        menuHeight = endMenu?.height ?: 0
        plank.visibility = View.GONE
        pageIsCover = false
        page.setPadding(0, 0, 0, 0)
        printed = true
        printedAt = 0f
        cover.alpha = 1f
        cover.visibility = View.INVISIBLE
        edge.visibility = View.INVISIBLE
        pose(1f)
        show()
        // Once the shelf under the layer is laid out, and before it is drawn.
        layer.doOnPreDraw {
            val found = find()
            val spot = found?.let(::spot)
            if (grown && found != null && spot != null) spot.scene = pictureScene(found, spot.row)
            this.grown = grown
            if (spot != null && place(spot)) {
                // A grown book is the page it was left at, which the cover on the shelf takes over from as it ends.
                cover.visibility = View.VISIBLE
                edge.visibility = if (grown) View.INVISIBLE else View.VISIBLE
                if (menuHeight > 0) {
                    plank.set(spot.row, endMenu)
                    plank.visibility = View.VISIBLE
                }
                pose(1f)
                run(1f, 0f, ::clear)
            } else {
                this.grown = false
                layer.animate().alpha(0f).setDuration(FADE_MS).withEndAction(::clear)
            }
        }
    }

    /**
     * Shuts the book over [left], the page it was at as it is seen, over the whole screen: the page
     * shrinks into the cover standing at [spot], which swings shut over it, as the book closes on
     * the shelf. The layer is taken away once it is shut, and [then] is called.
     */
    fun shut(spot: Spot, left: Bitmap, paperColor: Int, then: () -> Unit) {
        ticker = null
        sliding = false
        paper(false, paperColor)
        page.animate().cancel()
        page.setImageBitmap(left)
        page.setPadding(0, 0, 0, 0)
        page.alpha = 1f
        grown = false
        book.clipBounds = null
        endMenu = null
        menuHeight = 0
        plank.visibility = View.GONE
        pageIsCover = false
        printed = true
        printedAt = 0f
        cover.alpha = 1f
        cover.visibility = View.VISIBLE
        edge.visibility = View.VISIBLE
        sized {
            if (!place(spot)) return@sized then()
            pose(1f)
            show()
            run(1f, 0f) {
                clear()
                then()
            }
        }
    }

    /**
     * Slides the page in from the right until it fills the screen, without a book around it: on paper
     * of the colour [paperColor] with [print] on it, a picture of the page as the reader shows it, if
     * there is one. [then] is called when it fills the screen, which it goes on doing until [clear].
     */
    fun slideIn(paperColor: Int, print: Bitmap?, then: () -> Unit) {
        ticker = null
        sliding = true
        grown = false
        book.clipBounds = null
        endMenu = null
        menuHeight = 0
        plank.visibility = View.GONE
        pageIsCover = false
        paper(false, paperColor)
        page.animate().cancel()
        page.setPadding(0, 0, 0, 0)
        page.alpha = 1f
        page.setImageBitmap(print)
        printed = print != null
        printedAt = 0f
        cover.visibility = View.GONE
        edge.visibility = View.GONE
        sized {
            pose(0f)
            show()
            run(0f, 1f, then)
        }
    }

    /** Whether the page only slides in, see [slideIn]. */
    private var sliding = false

    /**
     * Lays the book on paper of the colour [paperColor]; or, a [grown] one, against the wall of the
     * case, as the reader shows the cover that ends a book.
     */
    private fun paper(grown: Boolean, paperColor: Int) {
        if (grown) {
            paper.background = null
            wall.background = ShelfArt.wall(act)
            wall.alpha = 1f
            wall.visibility = View.VISIBLE
        } else {
            paper.setBackgroundColor(paperColor)
            wall.visibility = View.GONE
            wall.background = null
        }
    }

    /**
     * The reader has pictured its page, [print], while the book opens on blank paper: the print
     * comes onto the page. A page that has its print already keeps it.
     */
    fun print(print: Bitmap) {
        if (printed || layer.visibility != View.VISIBLE) return
        // A book that only grows wants the cover alone, in the shape it has; not a page.
        if (grown && abs(print.width.toFloat() / print.height - growFrom.width().toFloat() / growFrom.height()) > 0.02f) return
        // A page pictured while the reader was turned sideways, or not yet turned, is not one for the upright shelf: stretched over it, it would look squeezed.
        if (!grown && (print.width > print.height) != (frame.width > frame.height)) return
        printed = true
        page.setImageBitmap(print)
        if (grown) {
            // The cover alone as the reader shows it, under the cover of a book that only grows,
            // which becomes it over what is left of the way (see pose).
            pageIsCover = true
            page.setPadding(growFrom.left, growFrom.top, frame.width - growFrom.right, frame.height - growFrom.bottom)
            printedAt = shown
            pose(shown)
            return
        }
        page.alpha = 0f
        page.animate().alpha(1f).setDuration(PRINT_FADE_MS)
    }

    /** How far a book that only grows had come when its print came, see [print]. */
    private var printedAt = 0f

    /** Whether the page is the cover alone, where it stands as the book ends; else the page fills the screen. */
    private var pageIsCover = false

    /**
     * The cover of a book that only grows, read [sharp] from the book as large as it grows (Library.bigCover):
     * it is shown in place of the picture the shelf has, which is as large as the shelf shows it.
     */
    fun sharpen(sharp: Bitmap) {
        this.sharp = sharp
        if (grown) cover.setImageBitmap(sharp)
    }

    /** The cover a book that only grows is shown with, if it has been read as large as it grows; see [sharpen]. */
    private var sharp: Bitmap? = null

    /**
     * The reader has pictured its [menu], which a book that only grows opened with where it is
     * known only, as the reader had it before: what is on it comes onto the plank, over what is
     * left of the way. A menu that stands elsewhere now is not wanted.
     */
    fun menuCame(menu: EndMenu) {
        val known = endMenu ?: return
        if (!grown || layer.visibility != View.VISIBLE || !known.samePlace(menu)) return
        endMenu = menu
        plank.menu(menu, shown)
    }

    /** Takes the layer away, at once. */
    fun clear() {
        ticker = null
        sharp = null
        layer.animate().cancel()
        page.animate().cancel()
        steady(false)
        book.clipBounds = null
        endMenu = null
        plank.visibility = View.GONE
        plank.set(null, null)
        scene.visibility = View.GONE
        scene.set(null, 0, 0)
        wall.visibility = View.GONE
        wall.background = null
        layer.visibility = View.GONE
        page.setImageBitmap(null)
        cover.setImageDrawable(null)
    }

    private fun show() {
        layer.alpha = 1f
        layer.visibility = View.VISIBLE
        steady(true)
    }

    /**
     * Has the screen drawn as often a second as it can while the book opens or closes ([on]), or
     * as the app otherwise asks. A screen left to change its speed by itself may do so in the
     * middle of the book opening, which shows as a flicker, and makes it run unevenly.
     */
    private fun steady(on: Boolean) {
        val mode = if (on) ReaderSettings.fastestMode(act) else 0
        val lp = (if (detached) layer.layoutParams as? WindowManager.LayoutParams else act.window.attributes) ?: return
        if (lp.preferredDisplayModeId == mode) return
        lp.preferredDisplayModeId = mode
        if (!detached) {
            act.window.attributes = lp
            return
        }
        try {
            act.windowManager.updateViewLayout(layer, lp)
        } catch (e: Exception) {
            // The window has gone already.
        }
    }

    /** Does [action] once what the layer covers has a size, at once if it has. */
    private fun sized(action: () -> Unit) {
        val f = frame
        if (f.width > 0 && f.height > 0) action() else f.doOnLayout { action() }
    }

    /** Notes where the cover at [spot] is, within the layer, and what it looks like. False if that cannot be told. */
    private fun place(spot: Spot): Boolean {
        val f = frame
        if (f.width == 0 || f.height == 0) return false
        val origin = IntArray(2)
        f.getLocationOnScreen(origin)
        x = (spot.x - origin[0]).toFloat()
        y = (spot.y - origin[1]).toFloat()
        w = spot.w.toFloat()
        h = spot.h.toFloat()
        // The cover the shelf shows; a book that only grows takes the one it was given as large as it grows, if it was.
        // It is given its picture once it is told how to lay it out: given it before, it
        // would lay it out as it did the last book, until it is laid out anew.
        val picture = if (grown) sharp ?: spot.picture else spot.picture
        cover.setPadding(0, 0, 0, 0)
        if (grown) {
            // The cover as it is, whole, where the reader shows the cover that ends the book:
            // standing on the menu, if there is one to be raised under it, as on its shelf.
            val top = ViewCompat.getRootWindowInsets(f)?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0
            ShelfArt.endCover(f.width, f.height, top, menuHeight, act.resources.displayMetrics.density, growFrom)
            // In the shape it has on the shelf, as the reader shows it.
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setPadding(growFrom.left, growFrom.top, f.width - growFrom.right, f.height - growFrom.bottom)
            cover.rotationY = 0f
            // Made as small as the cover on the shelf, which shows it cut to the shape of its place
            // there, about its middle (centerCrop): the book is cut down to that part of it at first.
            growScale = maxOf(w / growFrom.width(), h / growFrom.height())
            val slotW = w / growScale
            val slotH = h / growScale
            growSlot.set(
                growFrom.exactCenterX() - slotW / 2, growFrom.exactCenterY() - slotH / 2,
                growFrom.exactCenterX() + slotW / 2, growFrom.exactCenterY() + slotH / 2,
            )
            growX = x + w / 2f - growFrom.exactCenterX() * growScale
            growY = y + h / 2f - growFrom.exactCenterY() * growScale
            // With the binding the shelf draws over it, which the reader draws over it too.
            cover.binding = spot.binding
            cover.setImageBitmap(picture)
            book.pivotX = 0f
            book.pivotY = 0f
            cover.invalidate()
            // The plank under the cover on its row, or just under the cover if it stands on none, and the plaque on it.
            val origin = IntArray(2)
            f.getLocationOnScreen(origin)
            val row = spot.row
            if (row != null) {
                plankFrom.set(row.face)
                plankFrom.offset(-origin[0], -origin[1])
                plaqueFrom.set(row.plaqueAt)
                plaqueFrom.offset(-origin[0].toFloat(), -origin[1].toFloat())
            } else {
                plankFrom.set(x.toInt(), (y + h).toInt(), (x + w).toInt(), (y + h + ShelfDecoration.PLANK * act.resources.displayMetrics.density).toInt())
                plaqueFrom.setEmpty()
            }
            // The shelf around it, as it is, until it is looked at closer.
            scene.set(spot.scene, origin[0], origin[1], RectF(x, y, x + w, y + h), plankFrom.height().toFloat())
            scene.visibility = if (spot.scene != null) View.VISIBLE else View.GONE
            // The plaque comes to where the menu has the plaque with the name of the book.
            val menu = endMenu
            if (row != null && menu != null) {
                plaqueTo.set(menu.title)
                plaqueTo.offset(0f, (f.height - menuHeight).toFloat())
            } else {
                plaqueTo.set(plaqueFrom)
            }
            return true
        }
        // The cover is as large as the screen, and made as small as its place on the shelf.
        val fit = spot.fit
        if (fit == null) {
            cover.scaleType = ImageView.ScaleType.FIT_XY
        } else {
            cover.scaleType = ImageView.ScaleType.MATRIX
            cover.imageMatrix = Matrix(fit).apply { postScale(f.width / spot.w.toFloat(), f.height / spot.h.toFloat()) }
        }
        // The cover turns on its spine, seen from far enough for it not to loom over the shelf.
        cover.pivotX = 0f
        cover.pivotY = f.height / 2f
        cover.cameraDistance = f.width * CAMERA * act.resources.displayMetrics.density
        cover.binding = spot.binding
        cover.setImageBitmap(picture)
        cover.invalidate()
        edge.board(foreEdge(spot), cover.cameraDistance, f.height * THICKNESS)
        book.pivotX = 0f
        book.pivotY = 0f
        return true
    }

    /**
     * Plays the book from [from] to [to] (see [pose]), always over the same time, so that closing
     * is opening played backwards; [then] is called at the end.
     */
    private fun run(from: Float, to: Float, then: () -> Unit) {
        // As long as animations are made to last on this phone; at once where they are turned off.
        val lasts = OPEN_MS * durationScale()
        val step = object : Choreographer.FrameCallback {
            private var t = from
            private var last = 0L

            override fun doFrame(now: Long) {
                if (ticker !== this) return
                // Evenly: pose eases each part of the way itself. Each frame goes on by the time since
                // the last one, but never by much more than a frame's: where the phone is held up for a
                // moment, as by the reader starting behind the book, the book waits rather than leaps.
                if (last != 0L) {
                    val by = if (lasts > 0f) ((now - last) / 1_000_000f).coerceAtMost(MAX_STEP_MS) / lasts else 1f
                    t = if (to > from) (t + by).coerceAtMost(to) else (t - by).coerceAtLeast(to)
                    pose(t)
                }
                last = now
                if (t == to) {
                    ticker = null
                    steady(false)
                    then()
                    return
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        ticker = step
        afterFrame { if (ticker === step) Choreographer.getInstance().postFrameCallback(step) }
    }

    /**
     * How many times as long as they are made animations last on this phone, as set in its
     * settings; 0 where they are turned off. Read from the settings themselves where the animator
     * cannot tell it yet.
     */
    private fun durationScale(): Float =
        if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
        else Settings.Global.getFloat(act.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /**
     * Does [action] once the frame being drawn now is on the screen. A window's first frame can be
     * long in coming, and an animation timed from before it would leap past its start.
     */
    private fun afterFrame(action: () -> Unit) {
        var done = false
        val once = Runnable {
            if (!done) {
                done = true
                action()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) layer.viewTreeObserver.registerFrameCommitCallback(once) else layer.doOnPreDraw { layer.post(once) }
        // Not later than this all the same, should no frame be drawn.
        layer.postDelayed(once, FRAME_WAIT_MS)
        layer.invalidate()
    }

    /** How far the book was last shown, see [pose]. */
    private var shown = 0f

    /** Where the cover of a book that only grows is seen, in the layer, as it was last posed. */
    private val seen = RectF()

    /** Shows the book [t] of the way from shut on the shelf, 0, to its page filling the screen, 1. */
    private fun pose(t: Float) {
        val f = frame
        shown = t
        if (sliding) {
            book.scaleX = 1f
            book.scaleY = 1f
            book.translationX = (1 - EASE.getInterpolation(t)) * f.width
            book.translationY = 0f
            return
        }
        if (grown) {
            // Only enlarged: the paper comes about the cover as it grows, which is all there is to see of the book at first.
            val e = EASE.getInterpolation(t)
            val scale = lerp(growScale, 1f, e)
            book.scaleX = scale
            book.scaleY = scale
            book.translationX = lerp(growX, 0f, e)
            book.translationY = lerp(growY, 0f, e)
            // Only the cover is seen at first, as the shelf shows it, and then the whole of it, as it
            // grows; once it is nearly as large as it goes, the wall of the case comes over the shelf.
            val whole = (e / PAPER_FROM).coerceAtMost(1f)
            val left = lerp(growSlot.left, growFrom.left.toFloat(), whole)
            val top = lerp(growSlot.top, growFrom.top.toFloat(), whole)
            val right = lerp(growSlot.right, growFrom.right.toFloat(), whole)
            val bottom = lerp(growSlot.bottom, growFrom.bottom.toFloat(), whole)
            clip.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            book.clipBounds = clip
            // Where the cover is seen, and where the row under it reaches: the shelf around them is
            // looked at as much closer as they have grown, its row grown into the menu.
            val tx = book.translationX
            val ty = book.translationY
            seen.set(tx + left * scale, ty + top * scale, tx + right * scale, ty + bottom * scale)
            val plankBottom = lerp(plankFrom.bottom.toFloat(), f.height.toFloat(), e).coerceAtLeast(seen.bottom)
            scene.pose(seen, plankBottom)
            wall.alpha = ((e - PAPER_FROM) / (1 - PAPER_FROM)).coerceIn(0f, 1f)
            // At the end the cover becomes the one the reader shows, as it pictured it, if it has.
            val sharpFrom = maxOf(SHARP_FROM, EASE.getInterpolation(printedAt))
            cover.alpha = if (printed && sharpFrom < 1f) 1f - ((e - sharpFrom) / (1 - sharpFrom)).coerceIn(0f, 1f) else 1f
            if (menuHeight > 0) {
                // The row under the cover grows into the menu, with the foot of the cover on it all
                // the way: the plank of the menu comes over it, then what is on the menu, and the
                // plaque on it becomes the name of the book on the menu.
                val foot = seen.bottom
                plank.pose(
                    e, 0f, foot, f.width.toFloat(), plankBottom,
                    lerp(plaqueFrom.left, plaqueTo.left, e), lerp(plaqueFrom.top, plaqueTo.top, e) - foot,
                    lerp(plaqueFrom.right, plaqueTo.right, e), lerp(plaqueFrom.bottom, plaqueTo.bottom, e) - foot,
                    0f, f.height - menuHeight - foot,
                )
            }
            return
        }
        // The page grows only once the cover has begun to open.
        val zoom = zoom(t)
        val angle = angle(t)
        // Past the upright, it is the inside of the board that shows, and its front stands edge-on, unseen.
        cover.rotationY = -angle.coerceAtMost(90f)
        edge.turn(-angle)
        // A shelf that was never shown has no size yet to make the book smaller by: it comes back
        // under a book that was opened elsewhere, whose page fills it whatever its size will be.
        val sized = f.width > 0 && f.height > 0
        book.scaleX = if (sized) lerp(w / f.width, 1f, zoom) else 1f
        book.scaleY = if (sized) lerp(h / f.height, 1f, zoom) else 1f
        book.translationX = lerp(x, 0f, zoom)
        book.translationY = lerp(y, 0f, zoom)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The shelf a cover that only grows comes out of (BookOpening.scene), as if looked at ever
     * closer: its picture is drawn in four bands, one over the other. The book's own band is as
     * large as the cover has grown, where it has gone ([pose]); the band over it and the one under
     * its row grow as much, about it, and go off the screen; the row under it grows as high as the
     * plank of the menu has. Before it grows, all of it is as the shelf shows it.
     */
    private class ZoomedShelf(ctx: Activity) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var scene: Scene? = null

        /** Where the picture is in the layer, where the cover stands in it, and how high its row is. */
        private val at = Rect()
        private val slot = RectF()
        private var face = 0f

        /** Where the cover is seen now, and how far down its row reaches. */
        private val cover = RectF()
        private var rowBottom = 0f

        /** Shows [scene], which stands on the screen as it is in the layer moved by [x], [y], around the cover [slot] on a row [face] high. */
        fun set(scene: Scene?, x: Int, y: Int, slot: RectF? = null, face: Float = 0f) {
            this.scene = scene
            if (scene == null || slot == null) return
            at.set(scene.at)
            at.offset(-x, -y)
            this.slot.set(slot)
            this.face = face
            pose(slot, slot.bottom + face)
        }

        /** Looks at it as closely as makes the cover stand where it is seen, [cover], with its row reaching down to [rowBottom]. */
        fun pose(cover: RectF, rowBottom: Float) {
            this.cover.set(cover)
            this.rowBottom = rowBottom
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val picture = scene?.picture ?: return
            if (slot.isEmpty) return
            val across = cover.width() / slot.width()
            // Over the book, the book itself, its row, and under its row: each band, where the
            // picture has it, and where it is shown now, as much higher as it has grown.
            band(canvas, picture, across, at.top.toFloat(), slot.top, cover.top - (slot.top - at.top) * across, across)
            band(canvas, picture, across, slot.top, slot.bottom, cover.top, cover.height() / slot.height())
            if (face > 0f) band(canvas, picture, across, slot.bottom, slot.bottom + face, cover.bottom, (rowBottom - cover.bottom) / face)
            band(canvas, picture, across, slot.bottom + face, at.bottom.toFloat(), rowBottom, across)
        }

        /** Draws the band of [picture] from [from] down to [to], [across] as wide and [high] as high, from [top] down. */
        private fun band(canvas: Canvas, picture: Bitmap, across: Float, from: Float, to: Float, top: Float, high: Float) {
            if (to <= from || high <= 0f) return
            canvas.withClip(0f, top, width.toFloat(), top + (to - from) * high) {
                translate(cover.left, top)
                scale(across, high)
                translate(-slot.left, -from)
                drawBitmap(picture, at.left.toFloat(), at.top.toFloat(), paint)
            }
        }
    }

    /**
     * The plank of a [BookOpening.plank] as it rises: the row it comes from is seen at first, as the
     * zoomed shelf has it, and the plank of the menu comes over it as it grows; then what is on the
     * menu; and the plaque of the book on it, which grows from the plaque on the shelf into the one on the menu.
     * It fills the layer, and draws the plank where it has come to ([pose]), rather than being
     * moved there: laid out anew, as when the layer is first shown, it would be put back.
     */
    private class RisingPlank(ctx: Activity) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val board = ContextCompat.getDrawable(ctx, R.drawable.shelf_plank)!!.mutate()
        private val plate = PlaqueDrawable(d)
        private val words = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = ContextCompat.getColor(ctx, R.color.plaque_text)
            // As the plaques have it (item_book.xml).
            setShadowLayer(1f, 0f, 1f, 0x99FFF6C8.toInt())
        }
        private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val plaqueAt = RectF()
        private var row: Row? = null
        private var menu: EndMenu? = null
        private var e = 0f

        /** How far the book had come when the pictures of the menu came, see [menu]; 0 when it came with them. */
        private var menuCame = 0f

        /** The pictures of [menu] have come, the book [shown] that far: they come onto the plank over what is left of the way. */
        fun menu(menu: EndMenu, shown: Float) {
            this.menu = menu
            menuCame = EASE.getInterpolation(shown)
            invalidate()
        }

        /** How much of what comes at the end is seen, [e] of the way, where it begins at [from], or later if it came later. */
        private fun later(from: Float): Float {
            val start = maxOf(from, menuCame)
            return if (start >= 1f) 1f else ((e - start) / (1 - start)).coerceIn(0f, 1f)
        }

        /** Makes the plank coming from [row] and going to [menu]; it is nowhere until it is posed. */
        fun set(row: Row?, menu: EndMenu?) {
            at.setEmpty()
            menuCame = 0f
            this.row = row
            this.menu = menu
            plate.favorite = row?.favorite == true
            plate.finished = row?.finished == true
        }

        /** Where the plank is in the layer, and the menu, from the top left of the plank. */
        private val at = RectF()
        private var menuX = 0f
        private var menuY = 0f

        /**
         * Poses it [e] of the way from the row, 0, to the menu, 1, from [left], [top] to [right],
         * [bottom] in the layer, with the plaque from [l], [t] to [r], [b] on it, and the menu, where
         * it comes onto it, [menuX] and [menuY] from its top left.
         */
        fun pose(
            e: Float, left: Float, top: Float, right: Float, bottom: Float,
            l: Float, t: Float, r: Float, b: Float, menuX: Float, menuY: Float,
        ) {
            this.e = e
            at.set(left, top, right, bottom)
            plaqueAt.set(l, t, r, b)
            this.menuX = menuX
            this.menuY = menuY
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (at.isEmpty) return
            canvas.withClip(at) {
                translate(at.left, at.top)
                drawPlank(this, at.width().toInt(), at.height().toInt())
            }
        }

        /** Draws the plank, [width] by [height], from its top left. */
        private fun drawPlank(canvas: Canvas, width: Int, height: Int) {
            val row = row ?: return
            // The row of the shelf under it, grown, until the plank of the menu has come over it.
            val boardLook = ((e - BOARD_FROM) / (BOARD_TO - BOARD_FROM)).coerceIn(0f, 1f)
            if (boardLook > 0f) {
                board.setBounds(0, 0, width, height)
                board.alpha = (boardLook * 255).toInt()
                board.draw(canvas)
            }
            // What is on the menu comes onto the plank where it will be, as far as the plank has come.
            val menu = menu
            val menuPicture = menu?.picture
            val menuLook = later(PAPER_FROM)
            if (menuPicture != null && menuLook > 0f) {
                picturePaint.alpha = (menuLook * 255).toInt()
                canvas.drawBitmap(menuPicture, menuX, menuY, picturePaint)
            }
            if (plaqueAt.isEmpty) return
            // The plaque, as large as it has grown, with the name of the book written as large on it.
            plate.setBounds(plaqueAt.left.toInt(), plaqueAt.top.toInt(), plaqueAt.right.toInt(), plaqueAt.bottom.toInt())
            plate.draw(canvas)
            words.textSize = if (menu != null) lerp(row.plaqueText, menu.titleText, e) else row.plaqueText
            val inset = if (menu != null) lerp(row.plaqueInset, menu.titleInset, e) else row.plaqueInset
            val name = TextUtils.ellipsize(row.title, words, (plaqueAt.width() - 2 * inset).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            // In the middle of the plate, above the shadow it casts.
            val middle = (plaqueAt.top + plaqueAt.bottom - PlaqueDrawable.SHADOW_DP * d) / 2
            canvas.drawText(name, 0, name.length, plaqueAt.centerX() - words.measureText(name, 0, name.length) / 2, middle - (words.ascent() + words.descent()) / 2, words)
            // At first, the plaque as the shelf has it, which the one drawn here takes over from.
            val shelfLook = 1f - (e / PLAQUE_FADES).coerceAtMost(1f)
            val picture = row.plaque
            if (shelfLook > 0f && picture != null) {
                picturePaint.alpha = (shelfLook * 255).toInt()
                canvas.drawBitmap(picture, null, plaqueAt, picturePaint)
            }
            // At the end, the plaque as the menu has it, which takes over from the one drawn here.
            val menuPlaque = menu?.plaque ?: return
            val menuPlaqueLook = later(SHARP_FROM)
            if (menuPlaqueLook > 0f) {
                picturePaint.alpha = (menuPlaqueLook * 255).toInt()
                canvas.drawBitmap(menuPlaque, null, plaqueAt, picturePaint)
            }
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    /**
     * The board of a cover, seen as the cover swings on its spine: its edge, the band between the
     * front of the board at its outer edge and its back, which lies [thick] further from the eye;
     * and once the cover is past upright, its back, the inside of the cover, which faces the eye
     * from there. All of it is seen as the cover itself is (View.rotationY), so the band stays on
     * the cover's edge; it is hidden under the cover while the book is shut.
     */
    private class Edge(ctx: Activity) : View(ctx) {
        private val band = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inside = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dpi = ctx.resources.displayMetrics.densityDpi.toFloat()
        private var turned = 0f
        private var thick = 0f

        /** How far the eye is from the cover, in pixels. */
        private var eye = 1f

        /** Makes the board [thick] and of the colour [color], seen from as far as a view with that [distance] is. */
        fun board(color: Int, distance: Float, thick: Float) {
            paint.color = color
            this.thick = thick
            // As View.setCameraDistance has it, in inches, which the camera counts 72 points to.
            eye = abs(distance) / dpi * 72f
            invalidate()
        }

        /** Turns the board on its spine as View.rotationY does. */
        fun turn(degrees: Float) {
            if (turned == degrees) return
            turned = degrees
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0) return
            val a = Math.toRadians(turned.toDouble())
            val cos = cos(a).toFloat()
            val sin = sin(a).toFloat()
            val w = width.toFloat()
            // The outer edge of the front of the board, and of its back, turned on the spine.
            val frontX = w * cos
            val frontZ = w * sin
            val backX = w * cos - thick * sin
            val backZ = w * sin + thick * cos
            band.rewind()
            corner(frontX, frontZ, -1f, true)
            corner(frontX, frontZ, 1f, false)
            corner(backX, backZ, 1f, false)
            corner(backX, backZ, -1f, false)
            band.close()
            canvas.drawPath(band, paint)
            if (turned >= -90f) return
            // The inside, from the back of the board at the spine to its outer edge, lit the more
            // the more it faces the eye. It lies beside the page, never over it: what of it would,
            // so near the spine, is the board's thickness, which the page hides.
            inside.color = shade(INSIDE, INSIDE_DARK + (1 - INSIDE_DARK) * -cos)
            band.rewind()
            corner(-thick * sin, thick * cos, -1f, true)
            corner(backX, backZ, -1f, false)
            corner(backX, backZ, 1f, false)
            corner(-thick * sin, thick * cos, 1f, false)
            band.close()
            canvas.withClip(-SPREAD * w, -SPREAD * height, 0f, SPREAD * height) { drawPath(band, inside) }
        }

        /** Adds to the band the point [x] along and [z] away from the spine, at its top (-1) or bottom ([end] 1), as the eye sees it. */
        private fun corner(x: Float, z: Float, end: Float, first: Boolean) {
            val near = eye / (eye + z)
            val half = height / 2f
            val px = x * near
            val py = half + end * half * near
            if (first) band.moveTo(px, py) else band.lineTo(px, py)
        }
    }

    /** How far, in degrees, the cover has swung open [t] of the way: it is open before the page has grown far. */
    private fun angle(t: Float) = EASE.getInterpolation((t / SWING).coerceIn(0f, 1f)) * OPEN_ANGLE

    /** How far the page has grown towards filling the screen [t] of the way, 0 to 1. */
    private fun zoom(t: Float) = EASE.getInterpolation(((t - ZOOM_FROM) / (1 - ZOOM_FROM)).coerceIn(0f, 1f))

    /**
     * Opens books over [act] in a window of their own, which a thread of its own makes and
     * moves: the reader starts while a book opens (see [Handoff]), and keeps the app's main
     * thread busy for a good part of it, which would stall an opening played there.
     */
    class Detached(private val act: Activity) {
        /** The opening shown now; only touched on the opening thread. */
        private var opening: BookOpening? = null
        private var shown: View? = null

        /**
         * What is to be done once the book shown lies open, until it does; only touched on the
         * opening thread. An opening taken away before then, as when the shelf goes out of sight
         * meanwhile, still has it done: the reader waiting unseen for the book to open would
         * otherwise never show itself.
         */
        private var pending: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        /**
         * Opens the book [file], whose cover is shown by [from], at once, while the reader starts
         * behind it ([handoff]): on the page as the book was last left, if that is kept and looks
         * the same ([look]), else on paper that the page the reader sets out meanwhile comes onto
         * as soon as the reader has pictured it.
         */
        fun open(
            from: View, paperColor: Int, file: File, look: String, handoff: Handoff, grown: Boolean = false, known: EndMenu? = null,
        ) {
            val screen = act.window.decorView
            // A cover read for the book opened before is not this one's.
            thread.post { sharp = null }
            if (grown && known != null && known.screenW == screen.width && known.screenH == screen.height) {
                // A cover that only grows raises the menu of the reader as it does. Where the menu is
                // known, from the last time the book was shown, it grows at once, and what is on the
                // menu comes onto it once the reader has pictured that.
                open(from, paperColor, null, true, known, handoff::opened)
                handoff.whenMenu(WAIT_MS) { menu -> if (menu?.picture != null) thread.post { opening?.menuCame(menu) } }
            } else if (grown) {
                // Else it waits a moment for the reader to picture it.
                handoff.whenMenu(MENU_WAIT_MS) { menu -> open(from, paperColor, null, true, menu, handoff::opened) }
            } else {
                open(from, paperColor, kept(file, look), false, null, handoff::opened)
            }
            handoff.whenPictured { print -> if (print != null) thread.post { opening?.print(print) } }
        }

        /** Opens the book whose cover is shown by [from], as [BookOpening.open] does; [then] is called on the main thread. */
        fun open(
            from: View, paperColor: Int, print: Bitmap?, grown: Boolean = false, menu: EndMenu? = null, then: () -> Unit,
        ) {
            val spot = spot(from) ?: return then()
            // The shelf around a book that only grows, which it is looked at closer as it grows; pictured here, where the shelf is.
            if (grown) spot.scene = pictureScene(from, spot.row)
            play(then) { o, done ->
                o.open(spot, paperColor, print, grown, menu, done)
                sharp?.let { if (grown) o.sharpen(it) }
            }
        }

        /**
         * Slides the page of the book [file] in from the right, as [BookOpening.slideIn] does, while the
         * reader starts behind it ([handoff]): the page as the book was last left, if that is kept and
         * looks the same ([look]), else paper that the reader's page comes onto once it is pictured.
         */
        fun slide(paperColor: Int, file: File, look: String, handoff: Handoff) {
            play(handoff::opened) { o, done -> o.slideIn(paperColor, kept(file, look), done) }
            handoff.whenPictured { print -> if (print != null) thread.post { opening?.print(print) } }
        }

        /**
         * Puts a [BookOpening] in a window of its own over the activity and has [start] play it there,
         * on the opening thread, calling the end it is given once done; [then] is called then, on the
         * main thread.
         */
        private fun play(then: () -> Unit, start: (BookOpening, () -> Unit) -> Unit) {
            val token = act.window.decorView.windowToken ?: return then()
            thread.post {
                remove()
                pending = then
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.token = token
                    windowAnimations = 0
                    if (Build.VERSION.SDK_INT >= 30) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        // Over the whole screen, the bars included, as the reader that takes over is:
                        // kept clear of them, all of it would stand a little higher than in the reader.
                        fitInsetsTypes = 0
                    }
                }
                try {
                    val o = BookOpening(act) {
                        act.windowManager.addView(it, params)
                        shown = it
                    }
                    opening = o
                    start(o) {
                        pending = null
                        main.post(then)
                    }
                } catch (e: Exception) {
                    // The shelf has no window to put one over any more.
                    remove()
                }
            }
        }

        /** Takes the opening away. */
        fun clear() = thread.post(::remove)

        /** The cover of the book opening, read as large as it grows (BookOpening.sharpen); for the next one to open, if it has not yet. */
        private var sharp: Bitmap? = null

        /** Shows the book that only grows with its cover read [sharp], as large as it grows, see [BookOpening.sharpen]. */
        fun sharpen(sharp: Bitmap) = thread.post {
            this.sharp = sharp
            opening?.sharpen(sharp)
        }

        private fun remove() {
            pending?.let {
                pending = null
                main.post(it)
            }
            opening?.clear()
            opening = null
            shown?.let {
                shown = null
                try {
                    act.windowManager.removeViewImmediate(it)
                } catch (e: Exception) {
                    // Gone with the shelf's window already.
                }
            }
        }

        private companion object {
            val thread: Handler by lazy { Handler(HandlerThread("BookOpening").apply { start() }.looper) }
        }
    }

    /**
     * A reader started for a book the moment it is tapped on the shelf, unseen over it, while the
     * book opens there: the page is set out meanwhile rather than after. The reader shows itself
     * once the book is open and its page has been drawn, whichever comes last.
     */
    class Handoff {
        private var show: (() -> Unit)? = null
        private var open = false
        private val began = SystemClock.uptimeMillis()

        /** The reader's page as it pictured it, once it has; see [pictured]. */
        private var print: Bitmap? = null
        private var pictured = false
        private var onPicture: ((Bitmap?) -> Unit)? = null

        /** The reader has set out its page and pictured it as it shows it, [print], or could not: null. */
        fun pictured(print: Bitmap?) {
            if (pictured) return
            pictured = true
            this.print = print
            onPicture?.let {
                onPicture = null
                it(print)
            }
        }

        /** Calls [then] with the picture of the reader's page once it has one, see [pictured]; at once if it has. */
        fun whenPictured(then: (Bitmap?) -> Unit) {
            if (pictured) then(print) else onPicture = then
        }

        /** The reader's menu as it stands over the cover that ends a book, pictured, or null if it could not be; see [menuPictured]. */
        private var menu: EndMenu? = null
        private var menuKnown = false
        private var onMenu: ((EndMenu?) -> Unit)? = null

        /** The reader has pictured its menu as it is over the cover that ends the book, or could not: null. */
        fun menuPictured(menu: EndMenu?) {
            if (menuKnown) return
            menuKnown = true
            this.menu = menu
            onMenu?.let {
                onMenu = null
                it(menu)
            }
        }

        /**
         * Calls [then] with the picture of the menu once the reader has one, see [menuPictured]; at
         * once if it has, and with null if it has none after [wait] milliseconds: the book opens without it.
         */
        fun whenMenu(wait: Long, then: (EndMenu?) -> Unit) {
            if (menuKnown) return then(menu)
            var called = false
            val once = { picture: EndMenu? ->
                if (!called) {
                    called = true
                    onMenu = null
                    then(picture)
                }
            }
            onMenu = once
            Handler(Looper.getMainLooper()).postDelayed({ once(null) }, wait)
        }

        /**
         * Whether the reader is still to show itself: until then a tap meant for its page would
         * reach the shelf under it. Not for longer than a reader takes to start at most, in
         * case it never does.
         */
        val waiting get() = handoff === this && SystemClock.uptimeMillis() - began < WAIT_MS

        /** The reader has drawn its page behind the shelf; [show] makes it seen. */
        fun ready(show: () -> Unit) {
            if (open) done(show) else this.show = show
        }

        /** The book is open on the shelf. */
        fun opened() {
            open = true
            show?.let(::done)
        }

        private fun done(show: () -> Unit) {
            this.show = null
            if (handoff === this) handoff = null
            show()
        }
    }

    companion object {
        /** Set by the reader as it leaves for the shelf, and taken by the shelf as it comes back. */
        var left: Left? = null

        /** The reader being started behind the book opening on the shelf, if one is; see [Handoff]. */
        var handoff: Handoff? = null

        /** How long a page that was left stays worth closing the book from. */
        const val LEFT_MS = 3000L

        /**
         * The page the book last closed was left at, the book, and how the page looked ([look]),
         * for the book to open on again at once, while it is the last one closed.
         */
        private var kept: Triple<String, String, Bitmap>? = null

        /** Keeps [print], the page [file] was left at as it looked ([look]), see [kept]. */
        fun keep(file: File, look: String, print: Bitmap) {
            kept = Triple(file.path, look, print)
        }

        /** The page [file] was last left at, if it is kept and looked as it would now ([look]). */
        fun kept(file: File, look: String): Bitmap? = kept?.takeIf { it.first == file.path && it.second == look }?.third

        /** Lets go of the page kept, which is as large as the screen, e.g. as the phone runs short of memory. */
        fun forget() {
            kept = null
        }

        /** How long the print takes to come onto a page that opened before the reader had pictured it. */
        private const val PRINT_FADE_MS = 150L

        /** The longest the shelf waits for a reader started behind a book opening on it, see [Handoff.waiting]. */
        private const val WAIT_MS = 5000L

        private const val OPEN_MS = 760f

        /** The most the book goes on by from one frame to the next, as if this long had gone by: about two frames. */
        private const val MAX_STEP_MS = 34f

        /** The longest a cover that only grows waits for the reader to picture its menu before it grows without. */
        private const val MENU_WAIT_MS = 450L
        private const val FADE_MS = 180L

        /** The part of the time the cover takes to swing open, and the part after which the page starts to grow. */
        private const val SWING = 0.62f
        private const val ZOOM_FROM = 0.3f
        private const val OPEN_ANGLE = 172f

        /**
         * The part of the way after which the paper comes about a cover that only grows, and the
         * menu onto its plank; and by which that plank has lost the look of the row it comes from.
         */
        private const val PAPER_FROM = 0.6f

        /** The part of the way over which the plank of the menu comes over the row of the shelf grown under a cover that only grows. */
        private const val BOARD_FROM = 0.25f
        private const val BOARD_TO = 0.65f

        /** By which part of the way the plaque that rises with the plank no longer looks as the one on the shelf does. */
        private const val PLAQUE_FADES = 0.25f

        /**
         * The part of the way after which a cover that only grows, and its plaque, become the ones the
         * reader shows, sharper than the pictures the shelf has.
         */
        private const val SHARP_FROM = 0.7f


        /** How many widths of the screen away the cover is seen from as it swings towards the eye. */
        private const val CAMERA = 5f

        /** The endpaper on the inside of the cover. */
        private const val INSIDE = 0xFFD9CDB4.toInt()

        /** How thick the board of a cover is, a part of the height of the book. */
        private const val THICKNESS = 0.03f

        /** How dark the inside of the cover is as it stands edge-on, against facing the eye. */
        private const val INSIDE_DARK = 0.7f

        /** How far beyond the book, in its sizes, the open cover may reach. */
        private const val SPREAD = 4f

        /** [color] with its light made [by] times as much. */
        private fun shade(color: Int, by: Float) =
            Color.rgb((Color.red(color) * by).toInt(), (Color.green(color) * by).toInt(), (Color.blue(color) * by).toInt())

        /** The longest a book waits for its first frame to be shown before it starts to open or close all the same. */
        private const val FRAME_WAIT_MS = 250L

        /** How much darker the edge of the board is than the cover, turned a little away from the light. */
        private const val EDGE_SHADE = 0.85f

        /**
         * The edge of the board of the cover at [spot]: the colour of the cover along its outer
         * edge as it shows on the shelf, which wraps round the board, a little in shade.
         */
        private fun foreEdge(spot: Spot): Int {
            var r = 0
            var g = 0
            var b = 0
            try {
                // A picture the drawing hardware keeps cannot be looked into, but a copy of it can.
                val picture = if (Build.VERSION.SDK_INT >= 26 && spot.picture.config == Bitmap.Config.HARDWARE) {
                    spot.picture.copy(Bitmap.Config.ARGB_8888, false) ?: return EDGE
                } else {
                    spot.picture
                }
                // Where the outer edge of the cover on the shelf is in its picture.
                val back = Matrix()
                spot.fit?.invert(back)
                val at = FloatArray(2)
                for (i in 0 until SAMPLES) {
                    at[0] = spot.w - 1.5f
                    at[1] = spot.h * (i + 0.5f) / SAMPLES
                    if (spot.fit != null) {
                        back.mapPoints(at)
                    } else {
                        at[0] *= picture.width / spot.w.toFloat()
                        at[1] *= picture.height / spot.h.toFloat()
                    }
                    val c = picture[at[0].toInt().coerceIn(0, picture.width - 1), at[1].toInt().coerceIn(0, picture.height - 1)]
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                }
                if (picture !== spot.picture) picture.recycle()
            } catch (e: Exception) {
                return EDGE
            }
            return shade(Color.rgb(r / SAMPLES, g / SAMPLES, b / SAMPLES), EDGE_SHADE)
        }

        private const val SAMPLES = 9

        /** The edge of a board whose cover cannot be looked into. */
        private const val EDGE = 0xFF3A2A1E.toInt()
        private val EASE = AccelerateDecelerateInterpolator()

        /**
         * Where [from] is on the screen and what it looks like; null if it has no size or picture.
         * A cover is shown by its own picture rather than a snapshot: the covers are pictures the
         * drawing hardware keeps, and some phones snapshot those as nothing at all.
         */
        private fun spot(from: View): Spot? {
            if (from.width == 0 || from.height == 0) return null
            val at = IntArray(2)
            from.getLocationOnScreen(at)
            val image = from as? ImageView
            val own = (image?.drawable as? BitmapDrawable)?.bitmap
            if (image != null && own != null) {
                val fit = Matrix()
                if (image.scaleType == ImageView.ScaleType.FIT_XY) {
                    val inner = RectF(0f, 0f, (image.width - image.paddingLeft - image.paddingRight).toFloat(), (image.height - image.paddingTop - image.paddingBottom).toFloat())
                    fit.setRectToRect(RectF(0f, 0f, own.width.toFloat(), own.height.toFloat()), inner, Matrix.ScaleToFit.FILL)
                } else {
                    fit.set(image.imageMatrix)
                }
                fit.postTranslate(image.paddingLeft.toFloat(), image.paddingTop.toFloat())
                return Spot(at[0], at[1], from.width, from.height, own, fit, (from as? RatioImageView)?.binding == true, row(from, at))
            }
            return Spot(at[0], at[1], from.width, from.height, from.snapshot() ?: return null, row = row(from, at))
        }

        /**
         * The plank the cover [from], [at] on the screen, stands on, if it stands on a shelf: its
         * front face is right under the cover (ShelfDecoration), and the plaque of the book on it.
         */
        private fun row(from: View, at: IntArray): Row? {
            // The place on the shelf the cover is in.
            var place: View = from
            var parent = from.parent
            while (parent != null && parent !is RecyclerView) {
                place = parent as? View ?: return null
                parent = parent.parent
            }
            if (parent !is RecyclerView) return null
            val plaque = place.findViewById<TextView>(R.id.plaque) ?: return null
            val top = at[1] + from.height
            val face = (ShelfDecoration.PLANK * from.resources.displayMetrics.density).toInt()
            val plaqueAt = IntArray(2)
            plaque.getLocationOnScreen(plaqueAt)
            val plate = plaque.background as? PlaqueDrawable
            return Row(
                Rect(at[0], top, at[0] + from.width, top + face),
                plaque.snapshot(), Rect(plaqueAt[0], plaqueAt[1], plaqueAt[0] + plaque.width, plaqueAt[1] + plaque.height),
                plaque.text, plaque.textSize, plaque.paddingStart.toFloat(), plate?.favorite == true, plate?.finished == true,
                listOf(from, plaque),
            )
        }

        /**
         * The screen the cover [from] is shown on, pictured as it is, but for the book itself: its
         * cover and the plaque on its [row]; for a book that only grows, which stands for them.
         * Null if it cannot be pictured.
         */
        fun pictureScene(from: View, row: Row?): Scene? {
            val screen = from.rootView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return null
            val hidden = row?.shelved.orEmpty().map { it to it.alpha }
            for ((v, _) in hidden) v.alpha = 0f
            val picture = try {
                screen.snapshot()
            } finally {
                for ((v, alpha) in hidden) v.alpha = alpha
            } ?: return null
            val at = IntArray(2)
            screen.getLocationOnScreen(at)
            return Scene(picture, Rect(at[0], at[1], at[0] + screen.width, at[1] + screen.height))
        }
    }
}
