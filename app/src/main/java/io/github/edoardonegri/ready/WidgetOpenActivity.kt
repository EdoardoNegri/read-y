package io.github.edoardonegri.ready

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import java.io.File
import kotlin.math.roundToInt

/**
 * What a tap on the home-screen widget opens. It is see-through and lies over the home screen: a
 * tapped book opens right there, where it stands on the widget, as a book does on the bookshelf
 * ([BookOpening]), and the reader takes over once its page fills the screen. The reader is started
 * before anything else, and the book opens once it has set out its page; nothing shows until
 * then. Left again, the
 * reader goes to the bookshelf, to the shelf the book stands on, where the book is closed.
 * The place for choosing the books leads on to [WidgetBooksActivity]. The shortcut on the app's
 * icon that goes on with the book read last (ContinueShortcut) opens it here too, its page sliding
 * in from the right over the home screen ([EXTRA_SLIDE]).
 */
class WidgetOpenActivity : ComponentActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Made anew after it handed the book to the reader, it has nothing more to do: starting the
        // reader again would take the one there away and open the book anew.
        if (savedInstanceState?.getBoolean(DONE) == true) {
            finish()
            return
        }
        // It is simply there: some home screens would have it slide or grow into place.
        appearAtOnce()
        // The whole screen is its own, so that a place on the screen is the same place in it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val book = intent.getStringExtra(ReaderActivity.EXTRA_PATH)?.let(::File)
        // The bounds the home screen gives are those of the widget unscaled, from its corner.
        val shown = intent.getFloatExtra(EXTRA_SHOWN, 1f).takeIf { it in 0.4f..1f } ?: 1f
        val at = intent.sourceBounds?.let {
            Rect(it.left, it.top, it.left + (it.width() * shown).roundToInt(), it.top + (it.height() * shown).roundToInt())
        }
        when {
            // Without the library there is nothing to read or to choose from; the bookshelf asks for it.
            !Library.hasAccess() -> leaveFor(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            book == null -> leaveFor(Intent(this, WidgetBooksActivity::class.java))
            intent.getBooleanExtra(EXTRA_SLIDE, false) && book.isDoc && savedInstanceState == null -> slide(book)
            // The home screen did not say where the book stands, or it is gone: the reader says so.
            at == null || at.isEmpty || !book.isDoc || savedInstanceState != null -> read(book)
            else -> open(book, at)
        }
    }

    private fun leaveFor(next: Intent) {
        startActivity(next)
        finish()
    }

    /** Slides the page of [book] in from the right, over the home screen, once the window is there to show it in. */
    private fun slide(book: File) {
        val handoff = readBehind(book)
        window.decorView.post {
            if (isDestroyed) return@post
            readyWindow(handoff)
            openBook(book, null, handoff)
        }
    }

    /**
     * Starts the reader of [book] before anything else is done here, unseen over this window, where
     * the book opens once the reader has set out its page ([openBook]): the book is read, the reader
     * made and its page set out while the window settles and the cover is fetched, rather than after.
     * Null where the reader cannot start unseen, before Android 11: it starts once the book lies open.
     */
    private fun readBehind(book: File): BookOpening.Handoff? {
        BookPrep.prepare(book)
        if (Build.VERSION.SDK_INT < 30) {
            if (Library.isFlowing(book)) window.decorView.post { FlowEngine.prepare(this) }
            return null
        }
        leaveShelf()
        val handoff = BookOpening.Handoff()
        BookOpening.handoff = handoff
        read(book, handoff)
        // The reader's web view is made while the system gets the reader going.
        if (Library.isFlowing(book)) FlowEngine.prepare(this)
        return handoff
    }

    /**
     * Takes away whatever the app still has open elsewhere, the bookshelf or a book, before the
     * reader starts in its place. Started in the task of a bookshelf that is still there, the
     * reader, unseen, would have Android show the picture it keeps of that task until the reader
     * has drawn: the bookshelf, over the home screen, for a moment, before the book opens.
     */
    private fun leaveShelf() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            for (task in getSystemService(ActivityManager::class.java).appTasks) {
                val id = task.taskInfo?.taskId ?: continue
                if (id != taskId) task.finishAndRemoveTask()
            }
        } catch (e: Exception) {
            // It stays, and may show for a moment.
        }
    }

    /** Opens [book] from [at], the place on the screen its picture on the widget has. */
    private fun open(book: File, at: Rect) {
        val handoff = readBehind(book)
        val root = FrameLayout(this)
        setContentView(root)
        // The book as the widget shows it: standing at the foot of its place, half again as tall as wide.
        val w = minOf(at.width(), (at.height() / 1.5f).toInt())
        val h = (w * 1.5f).toInt()
        val cover = RatioImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ShelfArt.LEATHER)
            binding = true
            // The widget still shows the book; this one is only what the opening is drawn from.
            visibility = View.INVISIBLE
        }
        root.addView(cover, FrameLayout.LayoutParams(w, h))
        val app = applicationContext
        Library.io.execute {
            Library.meta(app, book)
            val picture = Library.cover(app, book)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                cover.setImageBitmap(picture)
                root.doOnLayout {
                    val origin = IntArray(2)
                    root.getLocationOnScreen(origin)
                    cover.translationX = (at.centerX() - w / 2 - origin[0]).toFloat()
                    cover.translationY = (at.bottom - h - origin[1]).toFloat()
                    readyWindow(handoff)
                    whenInPlace { openBook(book, cover, handoff) }
                }
            }
        }
    }

    /** What to do once the window is where it stays; null once that was done, or before there is any. */
    private var start: (() -> Unit)? = null
    private var inPlace = false

    /**
     * Does [action] once the window is in its place. A home screen that moves it there all the
     * same would otherwise move the opening book along; nothing shows in the window until then.
     */
    private fun whenInPlace(action: () -> Unit) {
        if (inPlace) return action()
        start = action
        // Should nothing ever say that it is, which is not waited for.
        window.decorView.postDelayed(::arrived, ARRIVAL_MS)
    }

    private fun arrived() {
        inPlace = true
        start?.let {
            start = null
            it()
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        arrived()
    }

    /**
     * Opens the book whose cover [cover] shows, on the paper the reader shows; without a cover, its
     * page slides in from the right. The reader started behind it ([handoff], see [readBehind])
     * shows itself once the book is open; without one, the book is handed to the reader then.
     */
    private fun openBook(book: File, cover: View?, handoff: BookOpening.Handoff?) {
        if (handoff == null) return openThenRead(book, cover)
        // The book opens once the reader has set out its page, which is all that is waited for: it
        // opens onto that page, the menu of the reader there to come onto it, and evenly, with
        // nothing left for the reader to do meanwhile. Nothing shows until then: the book stands
        // on the widget as it did.
        handoff.whenSetOut(SET_OUT_WAIT_MS) {
            // Left meanwhile, there is no book to open: the reader shows itself as it is.
            if (closing) return@whenSetOut handoff.opened()
            handoff.begun()
            val settings = ReaderSettings(this)
            val paper = settings.lit(settings.palette.bg)
            // The book opens in a window of its own, which the reader over it does not hold up.
            val opening = detached ?: BookOpening.Detached(this).also { detached = it }
            if (cover == null) return@whenSetOut opening.slide(paper, book, settings.look, handoff)
            // A book read to its end opens on its cover, which only grows, as it does on the bookshelf.
            // Where the menu stands over the cover is known from the last time the book was shown there.
            val grown = !settings.vertical && Library.isFinished(this, book)
            opening.open(cover, paper, book, settings.look, handoff, grown, if (grown) Library.endMenu(this, book) else null)
            if (grown) Library.sharpCover(this, book, resources.displayMetrics.widthPixels, opening::sharpen)
        }
    }

    /** Opens the book in this window, and starts its reader once it lies open: where the reader cannot start unseen. */
    private fun openThenRead(book: File, cover: View?) {
        val settings = ReaderSettings(this)
        val paper = settings.lit(settings.palette.bg)
        val opening = BookOpening(this)
        if (cover == null) return opening.slideIn(paper, BookOpening.kept(book, settings.look)) { read(book) }
        val grown = !settings.vertical && Library.isFinished(this, book)
        opening.open(cover, paper, if (grown) null else BookOpening.kept(book, settings.look), grown) { read(book) }
        if (grown) Library.sharpCover(this, book, resources.displayMetrics.widthPixels) { runOnUiThread { opening.sharpen(it) } }
    }

    /** The book opening while the reader starts over it, see [openBook]. */
    private var detached: BookOpening.Detached? = null

    /**
     * Makes the window the book opens in while it waits for its page, so that it opens the moment
     * the page is there; with a reader started behind it ([handoff]) only, as the book otherwise
     * opens in this window.
     */
    private fun readyWindow(handoff: BookOpening.Handoff?) {
        if (handoff != null && detached == null) detached = BookOpening.Detached(this).also { it.prepare() }
    }

    /**
     * Hands [book] to the reader, in a task that only holds the reader from then on. Without
     * [handoff] it is the bookshelf's, in which Android shows a picture of whatever was open there
     * as the task comes to the front, until the reader has drawn its page. With [handoff] the
     * reader starts unseen over the book opening, see [readBehind], and is given a task of its own,
     * never the bookshelf's: once the app has been left for a while and its process is gone,
     * Android keeps that task with its bookshelf, which [leaveShelf] cannot take away, and the
     * bookshelf would be brought back under the see-through reader, to grow out of the widget
     * before the book opens. Leaving the book leads to the bookshelf all the same.
     */
    private fun read(book: File, handoff: BookOpening.Handoff? = null) {
        // Not if the opening was left before the book lay open.
        if (done || closing) return
        done = true
        val reader = if (handoff != null) OpeningReaderActivity::class.java else ReaderActivity::class.java
        val task = if (handoff != null) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(
            Intent(this, reader).putExtra(ReaderActivity.EXTRA_PATH, book.path)
                .putExtra(ReaderActivity.EXTRA_FROM_SHELF, true).putExtra(ReaderActivity.EXTRA_FROM_WIDGET, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or task or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        // The page stays until the reader has taken its place, see onStop.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DONE, done)
    }

    override fun onStop() {
        super.onStop()
        if (done) finish()
    }

    override fun onDestroy() {
        detached?.clear()
        super.onDestroy()
    }

    companion object {
        /** How much smaller than the bounds it gives the home screen shows the widget; a float. */
        const val EXTRA_SHOWN = "shown"

        /** Set by the shortcut on the app's icon: the page of the book slides in, from nowhere on the screen. */
        const val EXTRA_SLIDE = "slide"

        /**
         * How long the window is given to get into place, if nothing says that it has: longer
         * than any home screen takes, as a book opened in a window that still moves is seen to
         * jump to its place and size.
         */
        private const val ARRIVAL_MS = 1500L

        /**
         * The longest a book waits for its reader to set out its page before it opens all the
         * same: the reader gives up on its page after 2.5 s itself, once it has started.
         */
        private const val SET_OUT_WAIT_MS = 4_000L


        /** Whether the book was handed to the reader already, kept for the activity made anew. */
        private const val DONE = "done"
    }
}

/** Has this activity come on screen at once, without the transition the system would play for it. */
fun Activity.appearAtOnce() {
    if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
}
