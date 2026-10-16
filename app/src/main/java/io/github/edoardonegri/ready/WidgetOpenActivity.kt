package io.github.edoardonegri.ready

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import java.io.File
import kotlin.math.roundToInt

/**
 * What a tap on the home-screen widget opens. It is see-through and lies over the home screen: a
 * tapped book opens right there, where it stands on the widget, as a book does on the bookshelf
 * ([BookOpening]), and the reader takes over once its page fills the screen. Left again, the
 * reader goes to the bookshelf, to the shelf the book stands on, where the book is closed.
 * The place for choosing the books leads on to [WidgetBooksActivity].
 */
class WidgetOpenActivity : AppCompatActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            // The home screen did not say where the book stands, or it is gone: the reader says so.
            at == null || at.isEmpty || !book.isDoc || savedInstanceState != null -> read(book)
            else -> open(book, at)
        }
    }

    private fun leaveFor(next: Intent) {
        startActivity(next)
        finish()
    }

    /** Opens [book] from [at], the place on the screen its picture on the widget has. */
    private fun open(book: File, at: Rect) {
        // The book opens only once the window has settled, which takes the better part of a
        // second; the reader's web view is made and the book is read meanwhile, so that the
        // reader has less to do, and its page shows sooner, once the book lies open.
        BookPrep.prepare(book)
        if (Library.isFlowing(book)) window.decorView.post { FlowEngine.prepare(this) }
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
                    whenInPlace { openBook(book, cover) }
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

    /** Opens the book whose cover [cover] shows, on the paper the reader shows, and hands it to the reader. */
    private fun openBook(book: File, cover: View) {
        val settings = ReaderSettings(this)
        val paper = settings.lit(settings.palette.bg)
        if (Build.VERSION.SDK_INT < 30) {
            BookOpening(this).open(cover, paper, BookOpening.kept(book, settings.look), bare = false) { read(book) }
            return
        }
        // The reader starts at once, unseen over the book opening, and sets out its page
        // meanwhile; it shows itself once the book is open (BookOpening.Handoff), as it does
        // from the bookshelf. The book opens in a window of its own, which the reader starting
        // does not hold up.
        val handoff = BookOpening.Handoff()
        BookOpening.handoff = handoff
        read(book, handoff)
        BookOpening.Detached(this).also { detached = it }.open(cover, paper, book, settings.look, handoff)
    }

    /** The book opening while the reader starts over it, see [openBook]. */
    private var detached: BookOpening.Detached? = null

    /**
     * Hands [book] to the reader, in the bookshelf's task, which only holds the reader from then
     * on: Android shows a picture of whatever was open there as the task comes to the front, until
     * the reader has drawn its page, and with [handoff] the reader starts unseen over the book
     * opening, see [openBook]. Leaving the book leads to the bookshelf all the same.
     */
    private fun read(book: File, handoff: BookOpening.Handoff? = null) {
        // Not if the opening was left before the book lay open.
        if (done || isFinishing || isDestroyed) return
        done = true
        val reader = if (handoff != null) OpeningReaderActivity::class.java else ReaderActivity::class.java
        startActivity(
            Intent(this, reader).putExtra(ReaderActivity.EXTRA_PATH, book.path)
                .putExtra(ReaderActivity.EXTRA_FROM_SHELF, true).putExtra(ReaderActivity.EXTRA_FROM_WIDGET, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        // The page stays until the reader has taken its place, see onStop.
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

        /**
         * How long the window is given to get into place, if nothing says that it has: longer
         * than any home screen takes, as a book opened in a window that still moves is seen to
         * jump to its place and size.
         */
        private const val ARRIVAL_MS = 1500L
    }
}

/** Has this activity come on screen at once, without the transition the system would play for it. */
fun Activity.appearAtOnce() {
    if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
}
