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
 * last, by its name and with its cover, which opens it as the button on the top rail of the
 * bookshelf does (MainActivity, [ACTION]). It is kept up to date as books are read, and taken
 * away while there is none to go on with.
 */
object ContinueShortcut {
    /** What the shortcut asks the bookshelf to do. */
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
            // The cover stands against the wall of the shelf the book stands on, in its wood.
            val wood = book.parentFile?.let { Library.wood(app, it) } ?: Wood.WALNUT
            val key = book.path + "\n" + title + "\n" + wood
            if (key == shown) return
            // Back to the bookshelf as it is, rather than another one over it, which goes on with the book.
            val intent = Intent(app, MainActivity::class.java).setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val shortcut = ShortcutInfoCompat.Builder(app, ID)
                .setShortLabel(title)
                .setLongLabel(app.getString(R.string.continue_book, title))
                .setIcon(icon(app, book, wood))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            shown = key
        } catch (e: Exception) {
            // The launcher takes no shortcuts, or not now: the app does without.
        }
    }

    /**
     * The cover of [book] standing against the wall of the case of [wood], as the icon of the
     * shortcut: in the middle of it, where every launcher shows it, whatever shape it cuts the icon to.
     */
    private fun icon(ctx: Context, book: File, wood: Wood): IconCompat {
        val side = (ICON_DP * ctx.resources.displayMetrics.density).roundToInt()
        val bmp = createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ShelfArt.wall(ctx, wood).apply { setBounds(0, 0, side, side) }.draw(c)
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
