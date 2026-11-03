package io.github.edoardonegri.ready

import android.app.ActivityManager
import android.app.ApplicationStartInfo
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
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.edit
import androidx.core.os.BundleCompat
import androidx.core.graphics.createBitmap
import androidx.core.os.HandlerCompat
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

    override fun onReceive(ctx: Context, intent: Intent) {
        // A tap on the widget of an app that was force stopped does not reach it: Android cancels
        // what the books would open, and only wakes the app to make the widget anew. Which book
        // was tapped is not told, so the widget asks for it again for a moment, now that it works.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE && wokenAfterForceStop(ctx)) {
            val app = ctx.applicationContext
            val ids = placed(app)
            val pending = goAsync()
            Library.io.execute {
                try {
                    show(app, ids, notice = true)
                } finally {
                    HandlerCompat.postDelayed(Library.main, {
                        Library.io.execute {
                            try {
                                show(app, ids)
                            } finally {
                                pending.finish()
                            }
                        }
                    }, pending, NOTICE_MS)
                }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

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
        /** Whether [wokenAfterForceStop] has looked at how this run of the app started. */
        private var startSeen = false

        /**
         * Whether this run of the app was started to make the widget anew after the app had been
         * force stopped, which only a touch of the widget does: once, for the first that asks.
         * Android tells from version 15 on; before, nothing is said.
         */
        private fun wokenAfterForceStop(ctx: Context): Boolean {
            if (startSeen || Build.VERSION.SDK_INT < 35) return false
            startSeen = true
            val start = try {
                ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessStartReasons(1).firstOrNull()
            } catch (e: Exception) {
                null
            } ?: return false
            return start.wasForceStopped() && start.startType == ApplicationStartInfo.START_TYPE_COLD &&
                start.reason == ApplicationStartInfo.START_REASON_BROADCAST &&
                start.intent?.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }

        /** How long the widget asks for the book again, after a tap only woke the app; see onReceive. */
        private const val NOTICE_MS = 5_000L

        /** About as many covers as the launcher can be sent in one go. */
        private const val MAX_BYTES = 6 shl 20

        /** How long [refresh] waits for more changes to come, e.g. the other books of a shelf that is being moved. */
        private const val SETTLE_MS = 300L

        private val pending = Any()

        /** The widgets of the app placed on the home screen. */
        private fun placed(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, FavoritesWidget::class.java))

        /**
         * Puts the shelves of every placed widget together again, e.g. when a book is put on it:
         * once, a moment after the last of the changes that follow each other closely.
         */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            Library.main.removeCallbacksAndMessages(pending)
            HandlerCompat.postDelayed(Library.main, {
                val ids = placed(app)
                if (ids.isNotEmpty()) Library.io.execute { show(app, ids) }
            }, pending, SETTLE_MS)
        }

        /**
         * What widget [id] shows laid out as [shelf]: the case, and in it the shelves with their
         * books, those of more than [most] bytes of covers left out.
         */
        private fun views(ctx: Context, id: Int, shelf: WidgetShelf, notice: Boolean, most: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            // The case has the shape of this picture, that of the widget. It says it is larger
            // than any screen, and is fitted into the widget, which it then fills.
            views.setImageViewBitmap(
                R.id.shape,
                createBitmap(shelf.width, shelf.height, Bitmap.Config.ALPHA_8).apply { density = 4 },
            )
            if (Build.VERSION.SDK_INT >= 31) {
                val shelves = Shelves(ctx, shelf)
                val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                for (i in 0 until shelves.count) {
                    items.addItem(i.toLong(), shelves.row(i))
                    if (shelves.bytes > most) break
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
            views.setViewVisibility(R.id.notice, if (notice) View.VISIBLE else View.GONE)

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
            return views
        }

        /**
         * Sends each widget its whole content. From Android 12 the shelves travel with it, which
         * replaces everything the launcher shows in one step. Asking it to fetch them again from
         * [FavoritesService] instead, the only way before, leaves it showing covers and sizes
         * from earlier fetches on recent versions. Blocking.
         */
        private fun show(ctx: Context, ids: IntArray, notice: Boolean = false) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (id in ids) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // One for each way the home screen may show the widget, which it picks from as it turns;
                    // the covers they all show are as many as one alone may.
                    val shelves = WidgetShelf.bySize(ctx, manager, id)
                    val each = MAX_BYTES / shelves.size
                    val one = shelves[null]
                    manager.updateAppWidget(
                        id,
                        if (one != null) views(ctx, id, one, notice, each)
                        else RemoteViews(shelves.entries.associate { (size, shelf) -> size!! to views(ctx, id, shelf, notice, each) }),
                    )
                } else {
                    manager.updateAppWidget(id, views(ctx, id, WidgetShelf.upright(ctx, manager, id), notice, MAX_BYTES))
                }
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(ids, R.id.shelves)
        }
    }
}

/**
 * How the shelves are laid out in one placed widget, shown [width] by [height] dp, the home screen
 * [sideways] or not. A book has one cell of the home screen to itself and is as tall as that
 * allows, whatever the widget's size: a widget four cells wide holds four places on a shelf, a
 * narrower one fewer (the others move to the shelf below), and every cell of height shows one more
 * shelf, empty if there are no books left for it. The case fills the widget, so a place is as wide
 * and as tall as its share of it; a book is never stretched to fit, and stands at the foot of its
 * place with free room above it if that is taller than it needs (widget_favorites.xml). All sizes
 * are in dp and mirror widget_favorites.xml and widget_row.xml.
 */
private class WidgetShelf(
    ctx: Context, id: Int, val width: Int, val height: Int, sideways: Boolean,
    /**
     * How much smaller than it says the home screen shows the widget, 1 if it is shown as it is.
     * Samsung's does so (0.83 on a screen set to a denser grid): the sizes it reports are those
     * of the widget unscaled, and so are the bounds it gives of a place that is tapped.
     */
    val shown: Float,
) {
    val columns: Int

    /** Width of one cover; it is half again as tall. */
    val coverW: Float

    /** Height of the place a book stands in: the cover, and free space above it if the cell is narrow. */
    val placeH: Float

    /** Shelves the widget has room for. */
    val shelves: Int

    init {
        val between = (width - 2 * POST).coerceAtLeast(1)

        // The launcher never says how many cells a size is. A widget is dropped two cells wide
        // and two tall (favorites_widget_info.xml), so the size it has then gives the size of a
        // cell, and is remembered, for a home screen upright and for one sideways. Not every size
        // is one of two cells, though: a home screen may drop the widget otherwise or report
        // another size first, and a widget placed long ago was dropped at another size. So a size
        // is only taken for one of two cells if it can be one on this screen, and what was
        // remembered is dropped if it cannot. Until there is one, the cells are taken to be of
        // the usual size. A tablet has more cells across than a phone, each a smaller part of
        // the screen, and taller ones.
        val config = ctx.resources.configuration
        val tablet = config.smallestScreenWidthDp >= TABLET
        val long = maxOf(config.screenWidthDp, config.screenHeightDp)
        val screenW = (if (sideways) long else config.smallestScreenWidthDp).coerceAtLeast(1)
        val screenH = (if (sideways) config.smallestScreenWidthDp else long).coerceAtLeast(1)
        val wide = if (tablet) 0.17f..0.42f else 0.28f..0.52f
        val tall = if (tablet) 150..420 else if (sideways) 90..270 else 130..270
        fun twoWide(inner: Int) = (inner + 2 * POST) / screenW.toFloat() in wide
        fun twoTall(height: Int) = height in tall
        val prefs = prefs(ctx)
        val side = if (sideways) SIDEWAYS else ""
        var two = prefs.getInt(TWO + side + id, 0)
        var pair = prefs.getInt(PAIR + side + id, 0)
        if (!twoTall(two)) {
            two = if (twoTall(height)) height else 0
            prefs.edit { if (two > 0) putInt(TWO + side + id, two) else remove(TWO + side + id) }
        }
        if (!twoWide(pair)) {
            pair = if (twoWide(between)) between else 0
            prefs.edit { if (pair > 0) putInt(PAIR + side + id, pair) else remove(PAIR + side + id).remove(FOUR + id) }
        }
        if (two == 0) two = if (tablet) (screenH * 0.25f).toInt() else 2 * USUAL_CELL_H
        if (pair == 0) pair = (screenW * if (tablet) 0.3f else 0.45f).toInt() - 2 * POST
        val cellW = pair / 2f
        // One shelf per cell of height.
        shelves = (height / (two / 2f)).roundToInt().coerceAtLeast(1)
        // A cell less is a little less than a place less, because of the gaps between cells.
        columns = (between / cellW + 0.3f).toInt().coerceIn(1, MAX_COLUMNS)
        // The shelves share the height of the case exactly, so that it ends on the plank of the
        // last one it shows, and the books are as tall as that leaves room for.
        placeH = (height.toFloat() / shelves - TOP - PLANK).coerceAtLeast(18f)
        // The posts take their share of the width, so the book stays narrow enough to stand in its place.
        coverW = minOf(placeH / 1.5f, between.toFloat() / columns - 2 * SIDE).coerceAtLeast(12f)
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

        /** The narrowest side of a tablet's screen, in dp. */
        private const val TABLET = 600

        /** The number of places widget_row.xml has. */
        const val MAX_COLUMNS = 8
        private const val TWO = "row"
        private const val FOUR = "four"
        private const val PAIR = "pair"

        /** Put between the name of what is remembered for a widget and its id, for a home screen sideways. */
        private const val SIDEWAYS = "w"

        private fun prefs(ctx: Context) = ctx.prefs(Prefs.WIDGET)

        private fun shown(o: Bundle) = o.getFloat(SHOWN, 1f).takeIf { it in 0.4f..1f } ?: 1f

        /**
         * Widget [id] laid out as the home screen shows it upright, the size the launcher reports
         * as min width x max height.
         */
        fun upright(ctx: Context, manager: AppWidgetManager, id: Int): WidgetShelf {
            val o = manager.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
            return WidgetShelf(ctx, id, w, h, sideways = false, shown(o))
        }

        /**
         * Widget [id] laid out for each size the home screen may show it at, by that size, for it
         * to pick from as it turns. That is on a tablet, whose home screen turns with it; a phone's
         * mostly stays upright while this app may not, and its widget is laid out [upright] alone,
         * under null.
         */
        fun bySize(ctx: Context, manager: AppWidgetManager, id: Int): Map<SizeF?, WidgetShelf> {
            val o = manager.getAppWidgetOptions(id)
            val sizes = BundleCompat.getParcelableArrayList(o, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct()
            if (ctx.resources.configuration.smallestScreenWidthDp < TABLET || sizes.size < 2) {
                return mapOf(null to upright(ctx, manager, id))
            }
            // The narrowest is the upright one; a home screen sideways shows the widget wider.
            val narrowest = sizes.minOf { it.width }
            return sizes.associate { size ->
                size to WidgetShelf(ctx, id, size.width.roundToInt(), size.height.roundToInt(), sideways = size.width > narrowest, shown(o))
            }
        }

        fun forget(ctx: Context, ids: IntArray) {
            prefs(ctx).edit {
                for (id in ids) {
                    remove(TWO + id).remove(FOUR + id).remove(PAIR + id)
                    remove(TWO + SIDEWAYS + id).remove(PAIR + SIDEWAYS + id)
                }
            }
        }
    }
}

/** The shelves of one placed widget as they are right now, with its books standing on them. Blocking. */
private class Shelves(private val ctx: Context, private val shelf: WidgetShelf) {
    constructor(ctx: Context, id: Int) : this(ctx, WidgetShelf.upright(ctx, AppWidgetManager.getInstance(ctx), id))

    private val access = Library.hasAccess()
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
        paint.color = ShelfArt.LEATHER
        c.drawRect(area, paint)
        val picture = Library.decodeCover(ctx, f, BitmapFactory.Options().apply { inSampleSize = 2 })
        if (picture != null) ShelfArt.drawBound(c, picture, area, paint) else ShelfArt.drawBinding(c, area, paint)
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
