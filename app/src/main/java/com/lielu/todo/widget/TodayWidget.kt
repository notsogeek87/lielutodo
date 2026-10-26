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
import com.lielu.todo.data.todo.productivity
import com.lielu.todo.ui.common.dueLabel
import java.time.LocalDate

/** Widget liste : tâches du jour et en retard, avec case à cocher et bouton d'ajout. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { updateTodayWidget(context, manager, it) }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        WidgetConfigStore(context).remove(appWidgetIds)
    }
}

/** Dessine (ou redessine après un changement de configuration) un widget liste. */
fun updateTodayWidget(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
) {
    val views = RemoteViews(context.packageName, R.layout.widget_today)
    val container = (context.applicationContext as LieluApplication).container
    val config = WidgetConfigStore(context).get(id)
    val projectTitle =
        container.todoRepository.state.value.projects
            .firstOrNull { it.id == config.projectId }
            ?.title
    views.setTextViewText(R.id.widget_title, projectTitle ?: config.period.title)
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
    manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayWidgetFactory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
}

private class TodayWidgetFactory(
    private val context: Context,
    private val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {
    private var items = emptyList<com.lielu.todo.data.model.Task>()

    private fun load() {
        val state =
            (context.applicationContext as LieluApplication)
                .container.todoRepository.state.value
        items = widgetTasks(state.tasks, LocalDate.now(), WidgetConfigStore(context).get(widgetId), state.projects)
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
    manager.getAppWidgetIds(ComponentName(context, ProductivityWidgetProvider::class.java)).forEach {
        manager.updateAppWidget(it, productivityViews(context))
    }
}

/** Widget productivité : tâches terminées aujourd'hui et sur 7 jours, calculées sur le cache local. */
class ProductivityWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { manager.updateAppWidget(it, productivityViews(context)) }
    }
}

private fun productivityViews(context: Context): RemoteViews {
    val repository = (context.applicationContext as LieluApplication).container.todoRepository
    val stats = productivity(repository.state.value.tasks, LocalDate.now())
    val views = RemoteViews(context.packageName, R.layout.widget_productivity)
    views.setTextViewText(R.id.productivity_today, stats.today.toString())
    views.setTextViewText(R.id.productivity_week, "${stats.week} cette semaine · série de ${stats.streak} j")
    views.setOnClickPendingIntent(
        R.id.productivity_root,
        PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    return views
}
