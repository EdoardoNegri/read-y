package com.example.read_y

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
import java.io.File
import kotlin.math.roundToInt

/** Home-screen widget: a small bookcase holding the favorite books; tapping one opens it. */
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

        /** Puts the shelves of every placed widget together again, e.g. when the favorites change. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, FavoritesWidget::class.java))
            if (ids.isNotEmpty()) Library.io.execute { show(app, ids) }
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
                if (Build.VERSION.SDK_INT >= 31) {
                    val shelves = Shelves(ctx, id)
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

                // The path of the tapped book is filled in per item, so the template must be mutable.
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                views.setPendingIntentTemplate(
                    R.id.shelves,
                    PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.empty,
                    PendingIntent.getActivity(
                        ctx, 1, Intent(ctx, MainActivity::class.java),
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
 * four books on a shelf, a narrower one fewer (the others move to the shelf below), and every cell
 * of height shows one more shelf, empty if there are no books left for it. All sizes are in dp and
 * mirror widget_favorites.xml and widget_row.xml.
 */
private class WidgetShelf(ctx: Context, manager: AppWidgetManager, id: Int) {
    val columns: Int

    /** Width of one cover; it is half again as tall. */
    val coverW: Float

    /** Height of the place a book stands in: the cover, and free space above it if the cell is narrow. */
    val placeH: Float

    /** Shelves the widget has room for. */
    val shelves: Int

    init {
        val o = manager.getAppWidgetOptions(id)
        // The launcher reports the portrait size as min width x max height. The home screen may be
        // portrait while this app is not, so the size for a landscape home screen is not used.
        val reportedW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val reportedH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val w = if (reportedW > 0) reportedW else 250
        val h = if (reportedH > 0) reportedH else 110
        val inner = (w - 2 * POST).coerceAtLeast(1)

        // The launcher never says how many cells a size is. A widget is dropped four cells wide
        // and two tall (favorites_widget_info.xml), so its first size gives the size of a cell.
        val prefs = prefs(ctx)
        var two = prefs.getInt(TWO + id, 0)
        var four = prefs.getInt(FOUR + id, 0)
        if (two == 0 && reportedH > 0) prefs.edit().putInt(TWO + id, h).apply()
        if (four == 0 && reportedW > 0) prefs.edit().putInt(FOUR + id, inner).apply()
        if (two == 0) two = h
        if (four == 0) four = inner
        val cellW = four / 4f
        // One shelf per cell of height. They share the widget's height exactly, so that it ends
        // on the plank of the last one it shows, and the books are as tall as that leaves room for.
        shelves = (h / (two / 2f)).roundToInt().coerceAtLeast(1)
        placeH = (h.toFloat() / shelves - TOP - PLANK).coerceAtLeast(18f)
        // A cell less is a little less than a place less, because of the gaps between cells.
        columns = (inner / cellW + 0.3f).toInt().coerceIn(1, MAX_COLUMNS)
        // A place can be narrower than a cell, most of all in a widget one cell wide, where the
        // posts take their share; the book then stays narrow enough to stand in it.
        coverW = minOf(placeH / 1.5f, inner.toFloat() / columns - 2 * SIDE).coerceAtLeast(12f)
    }

    companion object {
        private const val POST = 11
        private const val TOP = 8
        private const val PLANK = 8
        private const val SIDE = 3

        /** The number of places widget_row.xml has. */
        const val MAX_COLUMNS = 8
        private const val TWO = "row"
        private const val FOUR = "four"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun forget(ctx: Context, ids: IntArray) {
            prefs(ctx).edit().apply { for (id in ids) remove(TWO + id).remove(FOUR + id) }.apply()
        }
    }
}

/** The shelves of one placed widget as they are right now, with the favorite books standing on them. Blocking. */
private class Shelves(private val ctx: Context, id: Int) {
    private val shelf = WidgetShelf(ctx, AppWidgetManager.getInstance(ctx), id)
    private val items = if (Library.hasAccess(ctx)) Library.favorites(ctx) else emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Memory taken by the pictures of the shelves made so far. */
    var bytes = 0
        private set

    /** As many shelves as the books need, and at least those the widget has room for, empty or not. */
    val count = if (items.isEmpty()) 0 else maxOf((items.size + shelf.columns - 1) / shelf.columns, shelf.shelves)

    /** A whole shelf (widget_row.xml), showing as many of its places as the widget is cells wide. */
    fun row(position: Int): RemoteViews {
        val row = RemoteViews(ctx.packageName, R.layout.widget_row)
        val first = position * shelf.columns
        for (i in PLACES.indices) {
            val f = if (i < shelf.columns) items.getOrNull(first + i) else null
            row.setViewVisibility(PLACES[i], if (i < shelf.columns) View.VISIBLE else View.GONE)
            // An empty place keeps its room on the shelf through an empty picture of the same
            // size, and an invisible cover cannot be tapped.
            row.setViewVisibility(COVERS[i], if (f == null) View.INVISIBLE else View.VISIBLE)
            if (f == null) {
                if (i < shelf.columns) row.setImageViewBitmap(COVERS[i], sized(Bitmap.Config.ALPHA_8))
                continue
            }
            row.setImageViewBitmap(COVERS[i], cover(f))
            row.setContentDescription(COVERS[i], Library.meta(ctx, f).title)
            row.setOnClickFillInIntent(COVERS[i], Intent().putExtra(ReaderActivity.EXTRA_PATH, f.path))
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
        return Bitmap.createBitmap(w, h, config).apply { density = dm.densityDpi * w / shown }
            .also { bytes += it.allocationByteCount }
    }

    /**
     * The cover as it looks on the shelf, standing at the foot of its place: cropped to the
     * book shape, bound, with the reading progress along its foot.
     */
    private fun cover(f: File): Bitmap {
        val bmp = sized(Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = RectF(0f, bmp.height - bmp.width * 1.5f, bmp.width.toFloat(), bmp.height.toFloat())
        val d = bmp.width / shelf.coverW
        paint.color = 0xFF24160D.toInt()
        c.drawRect(area, paint)
        BitmapFactory.decodeFile(
            Library.coverFile(ctx, f).path,
            BitmapFactory.Options().apply { inSampleSize = 2 },
        )?.let { Library.drawCropped(c, it, area, paint) }
        ShelfArt.drawBinding(c, area, d, paint)
        Library.drawProgress(c, area, Library.state(ctx, f).percent, d, paint)
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
