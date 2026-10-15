package io.github.edoardonegri.ready

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.edit
import androidx.core.os.HandlerCompat
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.roundToInt

/**
 * Home-screen widget: a small bookcase holding the books the reader put on it; tapping one opens
 * it (WidgetOpenActivity). After the last of them stands a place to tap for choosing the books (WidgetBooksActivity).
 */
class FavoritesWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = later { show(ctx, ids) }

    /** Resizing changes how many books fit on a shelf and how many shelves there are. */
    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        later { show(ctx, intArrayOf(id)) }

    override fun onDeleted(ctx: Context, ids: IntArray) = WidgetShelf.forget(ctx, ids)

    /** Runs [work], which reads covers from disk, off the main thread while keeping this receiver alive. */
    private fun later(work: () -> Unit) {
        val pending = goAsync()
        Library.io.execute {
            try {
                work()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** About as many covers as the launcher can be sent in one go. */
        private const val MAX_BYTES = 6 shl 20

        /** How long [refresh] waits for more changes to come, e.g. the other books of a shelf that is being moved. */
        private const val SETTLE_MS = 300L

        private val pending = Any()

        /**
         * Puts the shelves of every placed widget together again, e.g. when a book is put on it:
         * once, a moment after the last of the changes that follow each other closely.
         */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            Library.main.removeCallbacksAndMessages(pending)
            HandlerCompat.postDelayed(Library.main, {
                val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, FavoritesWidget::class.java))
                if (ids.isNotEmpty()) Library.io.execute { show(app, ids) }
            }, pending, SETTLE_MS)
        }

        /**
         * Sends each widget its whole content. From Android 12 the shelves travel with it, which
         * replaces everything the launcher shows in one step. Asking it to fetch them again from
         * [FavoritesService] instead, the only way before, leaves it showing covers and sizes
         * from earlier fetches on recent versions. Blocking.
         */
        private fun show(ctx: Context, ids: IntArray) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (id in ids) {
                val views = RemoteViews(ctx.packageName, R.layout.widget_favorites)
                // The case has the shape of this picture, that of the widget. It says it is larger
                // than any screen, and is fitted into the widget, which it then fills.
                val shelf = WidgetShelf(ctx, manager, id)
                views.setImageViewBitmap(
                    R.id.shape,
                    createBitmap(shelf.width, shelf.height, Bitmap.Config.ALPHA_8).apply { density = 4 },
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    val shelves = Shelves(ctx, shelf)
                    val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                    for (i in 0 until shelves.count) {
                        items.addItem(i.toLong(), shelves.row(i))
                        if (shelves.bytes > MAX_BYTES) break
                    }
                    views.setRemoteAdapter(R.id.shelves, items.build())
                } else {
                    val service = Intent(ctx, FavoritesService::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(R.id.shelves, service)
                }
                views.setEmptyView(R.id.shelves, R.id.empty)

                // Every place on the shelves opens WidgetOpenActivity, the one thing they can be
                // told to open: it opens a tapped book where it stands, and leads on to the
                // choosing of the books. The path of the book is filled in per item, so the
                // template must be mutable.
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                views.setPendingIntentTemplate(
                    R.id.shelves,
                    PendingIntent.getActivity(
                        ctx, 0,
                        Intent(ctx, WidgetOpenActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.empty,
                    PendingIntent.getActivity(
                        ctx, 1, Intent(ctx, WidgetBooksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                manager.updateAppWidget(id, views)
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(ids, R.id.shelves)
        }
    }
}

/**
 * How the shelves are laid out in one placed widget. A book has one cell of the home screen to
 * itself and is as tall as that allows, whatever the widget's size: a widget four cells wide holds
 * four places on a shelf, a narrower one fewer (the others move to the shelf below), and every cell
 * of height shows one more shelf, empty if there are no books left for it. The case fills the
 * widget, so a place is as wide and as tall as its share of it; a book is never stretched to
 * fit, and stands at the foot of its place with free room above it if that is taller than it
 * needs (widget_favorites.xml). All sizes are in dp and mirror widget_favorites.xml and
 * widget_row.xml.
 */
private class WidgetShelf(ctx: Context, manager: AppWidgetManager, id: Int) {
    val columns: Int

    /** Width of one cover; it is half again as tall. */
    val coverW: Float

    /** Height of the place a book stands in: the cover, and free space above it if the cell is narrow. */
    val placeH: Float

    /** Shelves the widget has room for. */
    val shelves: Int

    /** Size of the widget, which the case fills. */
    val width: Int
    val height: Int

    /**
     * How much smaller than it says the home screen shows the widget, 1 if it is shown as it is.
     * Samsung's does so (0.83 on a screen set to a denser grid): the sizes it reports are those
     * of the widget unscaled, and so are the bounds it gives of a place that is tapped.
     */
    val shown: Float

    init {
        val o = manager.getAppWidgetOptions(id)
        // The launcher reports the portrait size as min width x max height. The home screen may be
        // portrait while this app is not, so the size for a landscape home screen is not used.
        val reportedW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val reportedH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        shown = o.getFloat(SHOWN, 1f).takeIf { it in 0.4f..1f } ?: 1f
        val w = if (reportedW > 0) reportedW else 250
        val h = if (reportedH > 0) reportedH else 110
        width = w
        height = h
        val between = (w - 2 * POST).coerceAtLeast(1)

        // The launcher never says how many cells a size is. A widget is dropped two cells wide
        // and two tall (favorites_widget_info.xml), so the size it has then gives the size of a
        // cell, and is remembered. Not every size is one of two cells, though: a home screen may
        // drop the widget otherwise or report another size first, and a widget placed long ago
        // was dropped at another size. So a size is only taken for one of two cells if it can
        // be one on this screen, and what was remembered is dropped if it cannot. Until there
        // is one, the cells are taken to be of the usual size.
        val screen = ctx.resources.configuration.smallestScreenWidthDp
        fun twoWide(inner: Int) = (inner + 2 * POST) / screen.coerceAtLeast(1).toFloat() in 0.28f..0.52f
        fun twoTall(height: Int) = height in 130..270
        val prefs = prefs(ctx)
        var two = prefs.getInt(TWO + id, 0)
        var pair = prefs.getInt(PAIR + id, 0)
        if (!twoTall(two)) {
            two = if (reportedH > 0 && twoTall(h)) h else 0
            prefs.edit { if (two > 0) putInt(TWO + id, two) else remove(TWO + id) }
        }
        if (!twoWide(pair)) {
            pair = if (reportedW > 0 && twoWide(between)) between else 0
            prefs.edit { if (pair > 0) putInt(PAIR + id, pair) else remove(PAIR + id).remove(FOUR + id) }
        }
        if (two == 0) two = 2 * USUAL_CELL_H
        if (pair == 0) pair = (screen * 0.45f).toInt() - 2 * POST
        val cellW = pair / 2f
        // One shelf per cell of height.
        shelves = (h / (two / 2f)).roundToInt().coerceAtLeast(1)
        // A cell less is a little less than a place less, because of the gaps between cells.
        columns = (between / cellW + 0.3f).toInt().coerceIn(1, MAX_COLUMNS)
        // The shelves share the height of the case exactly, so that it ends on the plank of the
        // last one it shows, and the books are as tall as that leaves room for.
        placeH = (h.toFloat() / shelves - TOP - PLANK).coerceAtLeast(18f)
        // The posts take their share of the width, so the book stays narrow enough to stand in its place.
        val inner = (w - 2 * POST).coerceAtLeast(1)
        coverW = minOf(placeH / 1.5f, inner.toFloat() / columns - 2 * SIDE).coerceAtLeast(12f)
    }

    companion object {
        private const val POST = 11
        private const val TOP = 5
        private const val PLANK = 7
        private const val SIDE = 3

        /** Where Samsung's home screen says how small it shows its widgets. */
        private const val SHOWN = "hsResizeRatio"

        /** About the height of a cell of a home screen, for as long as that of this one is not known. */
        private const val USUAL_CELL_H = 105

        /** The number of places widget_row.xml has. */
        const val MAX_COLUMNS = 8
        private const val TWO = "row"
        private const val FOUR = "four"
        private const val PAIR = "pair"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun forget(ctx: Context, ids: IntArray) {
            prefs(ctx).edit { for (id in ids) remove(TWO + id).remove(FOUR + id).remove(PAIR + id) }
        }
    }
}

/** The shelves of one placed widget as they are right now, with its books standing on them. Blocking. */
private class Shelves(private val ctx: Context, private val shelf: WidgetShelf) {
    constructor(ctx: Context, id: Int) : this(ctx, WidgetShelf(ctx, AppWidgetManager.getInstance(ctx), id))

    private val access = Library.hasAccess(ctx)
    private val items = if (access) Library.widgetBooks(ctx) else emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Memory taken by the pictures of the shelves made so far. */
    var bytes = 0
        private set

    /**
     * As many shelves as the books and the place for choosing them need, and at least those the
     * widget has room for, empty or not. None while the library cannot be read.
     */
    val count = if (!access) 0 else maxOf(items.size / shelf.columns + 1, shelf.shelves)

    /** A whole shelf (widget_row.xml), showing as many of its places as the widget is cells wide. */
    fun row(position: Int): RemoteViews {
        val row = RemoteViews(ctx.packageName, R.layout.widget_row)
        val first = position * shelf.columns
        for (i in PLACES.indices) {
            val f = if (i < shelf.columns) items.getOrNull(first + i) else null
            row.setViewVisibility(PLACES[i], if (i < shelf.columns) View.VISIBLE else View.GONE)
            // The place after the last book is the one to tap for choosing the books.
            if (i < shelf.columns && first + i == items.size) {
                row.setViewVisibility(COVERS[i], View.VISIBLE)
                row.setImageViewBitmap(COVERS[i], chooser())
                row.setContentDescription(COVERS[i], ctx.getString(R.string.widget_choose))
                row.setOnClickFillInIntent(COVERS[i], Intent())
                continue
            }
            // An empty place keeps its room on the shelf through an empty picture of the same
            // size, and an invisible cover cannot be tapped.
            row.setViewVisibility(COVERS[i], if (f == null) View.INVISIBLE else View.VISIBLE)
            if (f == null) {
                if (i < shelf.columns) row.setImageViewBitmap(COVERS[i], sized(Bitmap.Config.ALPHA_8))
                continue
            }
            row.setImageViewBitmap(COVERS[i], cover(f))
            row.setContentDescription(COVERS[i], Library.meta(ctx, f).title)
            row.setOnClickFillInIntent(
                COVERS[i],
                Intent().putExtra(ReaderActivity.EXTRA_PATH, f.path).putExtra(WidgetOpenActivity.EXTRA_SHOWN, shelf.shown),
            )
        }
        return row
    }

    /**
     * An empty bitmap of the size of the place a book stands in. The image views take their
     * size from their bitmap, so that books stay the same whatever the width of their column.
     * It is drawn with fewer pixels than that where the screen is dense, and says so through
     * its density, to keep the RemoteViews small enough to send to the launcher.
     */
    private fun sized(config: Bitmap.Config): Bitmap {
        val dm = ctx.resources.displayMetrics
        val shown = (shelf.coverW * dm.density).toInt().coerceAtLeast(1)
        val w = minOf(shown, MAX_COVER_W)
        val h = (w * shelf.placeH / shelf.coverW).roundToInt().coerceAtLeast(1)
        return createBitmap(w, h, config).apply { density = dm.densityDpi * w / shown }
            .also { bytes += it.allocationByteCount }
    }

    /** The place to tap for choosing the books: the outline of a book with a plus on it. */
    private fun chooser(): Bitmap {
        val bmp = sized(Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = bmp.width / shelf.coverW
        val w = bmp.width.toFloat()
        val top = bmp.height - w * 1.5f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x33000000
        c.drawRect(0f, top, w, bmp.height.toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = 0xFFB8922A.toInt()
        c.drawRect(d, top + d, w - d, bmp.height - d, paint)
        paint.strokeWidth = 2.5f * d
        paint.color = 0xFFF6E27A.toInt()
        val arm = w * 0.2f
        val y = top + w * 0.75f
        c.drawLine(w / 2 - arm, y, w / 2 + arm, y, paint)
        c.drawLine(w / 2, y - arm, w / 2, y + arm, paint)
        paint.style = Paint.Style.FILL
        return bmp
    }

    /**
     * The cover as it looks on the shelf, standing at the foot of its place: cropped to the
     * book shape and bound.
     */
    private fun cover(f: File): Bitmap {
        // A book the bookshelf has not shown yet, e.g. after the library came from another phone, has no cover to draw.
        if (!Library.coverFile(ctx, f).exists()) Library.meta(ctx, f)
        val bmp = sized(Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = RectF(0f, bmp.height - bmp.width * 1.5f, bmp.width.toFloat(), bmp.height.toFloat())
        val d = bmp.width / shelf.coverW
        paint.color = 0xFF24160D.toInt()
        c.drawRect(area, paint)
        Library.decodeCover(ctx, f, BitmapFactory.Options().apply { inSampleSize = 2 })?.let { Library.drawCropped(c, it, area, paint) }
        ShelfArt.drawBinding(c, area, d, paint)
        return bmp
    }

    companion object {
        private const val MAX_COVER_W = 220

        private val PLACES = intArrayOf(
            R.id.place0, R.id.place1, R.id.place2, R.id.place3,
            R.id.place4, R.id.place5, R.id.place6, R.id.place7,
        )
        private val COVERS = intArrayOf(
            R.id.cover0, R.id.cover1, R.id.cover2, R.id.cover3,
            R.id.cover4, R.id.cover5, R.id.cover6, R.id.cover7,
        )
    }
}

/** Hands the shelves to the launcher one by one; only used before Android 12 (see [FavoritesWidget]). */
class FavoritesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
    )

    private class Factory(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var shelves = Shelves(ctx, id)

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            shelves = Shelves(ctx, id)
        }

        override fun getCount() = shelves.count
        override fun getViewAt(position: Int) = shelves.row(position)
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
