package com.lielu.todo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.lielu.todo.LieluApplication
import com.lielu.todo.MainActivity
import com.lielu.todo.QuickAddActivity
import com.lielu.todo.R
import com.lielu.todo.ui.common.dueLabel
import java.time.LocalDate

/** Widget liste : tâches du jour et en retard, avec case à cocher et bouton d'ajout. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val adapter =
                Intent(context, TodayWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
            views.setRemoteAdapter(R.id.widget_list, adapter)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, WidgetActionActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_add,
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, QuickAddActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(id, views)
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodayWidgetFactory(applicationContext)
}

private class TodayWidgetFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {
    private var items = emptyList<com.lielu.todo.data.model.Task>()

    private fun load() {
        val repository = (context.applicationContext as LieluApplication).container.todoRepository
        items = widgetTasks(repository.state.value.tasks, LocalDate.now())
    }

    override fun onCreate() = load()

    override fun onDataSetChanged() = load()

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val task = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_item)
        views.setTextViewText(R.id.item_title, task.title)
        views.setTextViewText(R.id.item_due, task.dueDate?.let { dueLabel(it, LocalDate.now()) }.orEmpty())
        views.setOnClickFillInIntent(
            R.id.item_check,
            Intent()
                .putExtra(
                    WidgetActionActivity.EXTRA_TASK_ID,
                    task.id,
                ).putExtra(WidgetActionActivity.EXTRA_COMPLETE, true),
        )
        views.setOnClickFillInIntent(
            R.id.item_text,
            Intent()
                .putExtra(
                    WidgetActionActivity.EXTRA_TASK_ID,
                    task.id,
                ).putExtra(WidgetActionActivity.EXTRA_COMPLETE, false),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = items[position].id

    override fun hasStableIds() = true
}

/** Widget d'une seule ligne : ouvre l'ajout rapide. */
class QuickAddWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_quick_add)
            views.setOnClickPendingIntent(
                R.id.quick_add_root,
                PendingIntent.getActivity(
                    context,
                    3,
                    Intent(context, QuickAddActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(id, views)
        }
    }
}

/** Demande aux widgets liste de se redessiner : appelé à chaque changement des tâches. */
fun refreshWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
    if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
}
