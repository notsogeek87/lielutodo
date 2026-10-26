package com.lielu.todo.widget

import android.content.Context

/** Configuration de chaque widget liste (par id de widget), propre à l'appareil donc hors sauvegarde. */
class WidgetConfigStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_config", Context.MODE_PRIVATE)

    fun get(widgetId: Int): WidgetConfig =
        WidgetConfig(
            period =
                WidgetPeriod.entries.firstOrNull { it.name == prefs.getString("period_$widgetId", null) }
                    ?: WidgetPeriod.OVERDUE_TODAY,
            projectId = prefs.getLong("project_$widgetId", 0),
            includeSubProjects = prefs.getBoolean("sub_$widgetId", true),
        )

    fun set(
        widgetId: Int,
        config: WidgetConfig,
    ) {
        prefs
            .edit()
            .putString("period_$widgetId", config.period.name)
            .putLong("project_$widgetId", config.projectId)
            .putBoolean("sub_$widgetId", config.includeSubProjects)
            .apply()
    }

    fun remove(widgetIds: IntArray) {
        val editor = prefs.edit()
        widgetIds.forEach {
            editor.remove("period_$it").remove("project_$it").remove("sub_$it")
        }
        editor.apply()
    }
}
