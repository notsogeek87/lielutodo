package com.lielu.todo.data.settings

enum class UpcomingLayout { AGENDA, THREE_DAYS }

enum class UpcomingColoring(
    val label: String,
) {
    PRIORITY("Priorité"),
    PROJECT("Projet"),
}

enum class UpcomingSort(
    val label: String,
) {
    SMART("Intelligent"),
    DATE("Date"),
    PRIORITY("Priorité"),
    NAME("Nom"),
}

/** Réglages de l'écran « Prochainement » (fenêtre « Affichage »), propres à l'appareil. */
data class UpcomingSettings(
    val layout: UpcomingLayout = UpcomingLayout.AGENDA,
    val showDone: Boolean = false,
    val futureOccurrences: Boolean = false,
    val coloring: UpcomingColoring = UpcomingColoring.PRIORITY,
    val sort: UpcomingSort = UpcomingSort.SMART,
)
