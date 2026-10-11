package com.example.read_y

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.io.File

/** Home-screen widget showing the covers of the favorite books; tapping one opens it. */
class FavoritesWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            val service = Intent(ctx, FavoritesService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.grid, service)
            views.setEmptyView(R.id.grid, R.id.empty)

            // The path of the tapped book is filled in per item, so the template must be mutable.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            views.setPendingIntentTemplate(
                R.id.grid,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.header,
                PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(id, views)
        }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.grid)
    }

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, FavoritesWidget::class.java))
            @Suppress("DEPRECATION")
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.grid)
        }
    }
}

class FavoritesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var items: List<File> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            items = if (Library.hasAccess(ctx)) Library.favorites(ctx) else emptyList()
        }

        override fun getCount() = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_item)
            val f = items.getOrNull(position) ?: return views
            val meta = Library.meta(ctx, f)
            // Half-size covers keep the RemoteViews small enough to send to the launcher.
            val cover = BitmapFactory.decodeFile(
                Library.coverFile(ctx, f).path,
                BitmapFactory.Options().apply { inSampleSize = 2 },
            )
            if (cover != null) views.setImageViewBitmap(R.id.cover, cover)
            views.setTextViewText(R.id.title, meta.title)
            views.setOnClickFillInIntent(R.id.item, Intent().putExtra(ReaderActivity.EXTRA_PATH, f.path))
            return views
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
