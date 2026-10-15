package io.github.edoardonegri.ready

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
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
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
            !Library.hasAccess(this) -> leaveFor(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val root = FrameLayout(this)
        setContentView(root)
        // The book as the widget shows it: standing at the foot of its place, half again as tall as wide.
        val w = minOf(at.width(), (at.height() / 1.5f).toInt())
        val h = (w * 1.5f).toInt()
        val cover = RatioImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF24160D.toInt())
            binding = true
            // The widget still shows the book; this one is only what the opening is drawn from.
            visibility = View.INVISIBLE
        }
        root.addView(cover, FrameLayout.LayoutParams(w, h))
        val opening = BookOpening(this)
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
                    val settings = ReaderSettings(this)
                    whenInPlace { opening.open(cover, settings.lit(settings.palette.bg)) { read(book) } }
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

    /** Hands [book] to the reader, which joins the bookshelf's task, on top of whatever is open there. */
    private fun read(book: File) {
        // Not if the opening was left before the book lay open.
        if (done || isFinishing || isDestroyed) return
        done = true
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, book.path)
                .putExtra(ReaderActivity.EXTRA_FROM_SHELF, true).putExtra(ReaderActivity.EXTRA_FROM_WIDGET, true)
                // Clearing to the top: a task whose first activity was the reader, as it is where the
                // bookshelf was not open before, would otherwise only be brought to the front,
                // showing whatever it holds, the bookshelf or the book read before.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        // The page stays until the reader has taken its place, see onStop.
    }

    override fun onStop() {
        super.onStop()
        if (done) finish()
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
