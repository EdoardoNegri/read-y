package io.github.edoardonegri.ready

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import kotlin.math.roundToInt

/**
 * The shortcut the launcher offers on a long press on the app's icon: going on with the book read
 * last, by its name and with its cover. Its page slides in from the right over the home screen
 * (WidgetOpenActivity), as it does for the button on the top rail of the bookshelf, and the book
 * is closed on its shelf. It is kept up to date as books are read, and taken away while there is none
 * to go on with.
 */
object ContinueShortcut {
    /** What the shortcut asks for; the bookshelf, which it once opened, goes on with the book read last for it. */
    const val ACTION = "io.github.edoardonegri.ready.CONTINUE"

    private const val ID = "continue"

    /** The book, and its name, the shortcut shows now, so that it is not made anew for the same one. */
    @Volatile
    private var shown: String? = null

    /** Shows the book read last in the shortcut, or takes the shortcut away if there is none. Blocking. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val book = if (Library.hasAccess()) Library.lastRead(app)?.takeIf { it.isDoc } else null
            if (book == null) {
                if (shown != "") ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(ID))
                shown = ""
                return
            }
            val title = Library.meta(app, book).title
            val key = book.path + "\n" + title
            if (key == shown) return
            val intent = Intent(app, WidgetOpenActivity::class.java).setAction(ACTION)
                .putExtra(ReaderActivity.EXTRA_PATH, book.path).putExtra(WidgetOpenActivity.EXTRA_SLIDE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val shortcut = ShortcutInfoCompat.Builder(app, ID)
                .setShortLabel(title)
                .setLongLabel(app.getString(R.string.continue_book, title))
                .setIcon(icon(app, book))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            shown = key
        } catch (e: Exception) {
            // The launcher takes no shortcuts, or not now: the app does without.
        }
    }

    /**
     * The cover of [book] standing against the wall of a case, as the icon of the
     * shortcut: in the middle of it, where every launcher shows it, whatever shape it cuts the icon to.
     */
    private fun icon(ctx: Context, book: File): IconCompat {
        val side = (ICON_DP * ctx.resources.displayMetrics.density).roundToInt()
        val bmp = createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ShelfArt.wall(ctx).apply { setBounds(0, 0, side, side) }.draw(c)
        val high = side * COVER_PART
        val wide = high / ShelfSpec.BOOK
        val area = RectF((side - wide) / 2, (side - high) / 2, (side + wide) / 2, (side + high) / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = ShelfArt.LEATHER
        c.drawRect(area, paint)
        Library.cover(ctx, book)?.let { cover ->
            // A picture the drawing hardware keeps cannot be drawn here, but a copy of it can.
            val drawable = if (Build.VERSION.SDK_INT >= 26 && cover.config == Bitmap.Config.HARDWARE) cover.copy(Bitmap.Config.ARGB_8888, false) else cover
            drawable?.let { Library.drawCropped(c, it, area, paint) }
        }
        ShelfArt.drawBinding(c, area, paint)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }

    /** How large an adaptive icon is drawn, and how much of its height the cover takes, within the part every launcher shows. */
    private const val ICON_DP = 108
    private const val COVER_PART = 0.56f
}
