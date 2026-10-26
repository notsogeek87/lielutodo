package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.Task
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class QuickAdd(
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    /** Priorité Vikunja (0 à 5). */
    val priority: Int? = null,
    val projectId: Long? = null,
    val labels: List<Label> = emptyList(),
    /** Répétition (voir [com.lielu.todo.data.model.Task.repeatAfter]) : « tous les jours », « chaque lundi »… */
    val repeatAfter: Long = 0,
    val repeatMode: Int = 0,
) {
    /** L'heure est obligatoire : une date sans heure ne doit jamais être enregistrée, il faut la demander. */
    val needsTime: Boolean get() = date != null && time == null

    /**
     * Échéance à enregistrer. Vikunja n'a qu'un instant, sans « date seule » : sans heure on prend [DATE_ONLY_TIME]
     * (midi, comme son interface web) et l'affichage masque cette heure. Une heure sans date = aujourd'hui, ou
     * demain si elle est déjà passée.
     */
    fun dueInstant(
        now: LocalDateTime,
        zone: ZoneId,
    ): java.time.Instant? {
        if (date == null && time == null) return null
        val day =
            date ?: if (time!!.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return day.atTime(time ?: DATE_ONLY_TIME).atZone(zone).toInstant()
    }

    companion object {
        val DATE_ONLY_TIME: LocalTime = LocalTime.NOON
    }
}

/**
 * Ajout rapide en langage naturel, à la Todoist : « Appeler Marie demain 9h #Perso @urgent p1 ».
 * Vikunja ne le fait pas côté serveur, l'analyse se fait donc ici. Seuls les repères reconnus sont retirés du
 * titre : une date ou un projet inconnu reste dans le texte plutôt que de disparaître en silence.
 */
object QuickAddParser {
    private val weekdays =
        mapOf(
            "lundi" to DayOfWeek.MONDAY,
            "mardi" to DayOfWeek.TUESDAY,
            "mercredi" to DayOfWeek.WEDNESDAY,
            "jeudi" to DayOfWeek.THURSDAY,
            "vendredi" to DayOfWeek.FRIDAY,
            "samedi" to DayOfWeek.SATURDAY,
            "dimanche" to DayOfWeek.SUNDAY,
        )

    // Priorité Todoist p1 (la plus haute) à p4 (aucune) -> priorité Vikunja 4 (urgente) à 0.
    private const val DAY_SECONDS = 86_400L
    private const val WEEK_SECONDS = 7 * DAY_SECONDS

    private val priorityMap = mapOf(1 to 4, 2 to 3, 3 to 2, 4 to 0)

    private val priorityRegex = Regex("""(?<=\s)[pP]([1-4])(?=\s)""")
    private val projectRegex = Regex("""(?<=\s)#(\S+)""")
    private val labelRegex = Regex("""(?<=\s)@(\S+)""")
    private val timeRegex = Regex("""(?<=\s)(?:[àa]\s+)?(\d{1,2})[h:](\d{2})?(?=\s)""", RegexOption.IGNORE_CASE)
    private val dayAfterTomorrow = Regex("""(?<=\s)apr[eè]s[- ]demain(?=\s)""", RegexOption.IGNORE_CASE)
    private val todayRegex = Regex("""(?<=\s)(?:aujourd'?hui|auj)(?=\s)""", RegexOption.IGNORE_CASE)
    private val tomorrowRegex = Regex("""(?<=\s)demain(?=\s)""", RegexOption.IGNORE_CASE)
    private val inRegex = Regex("""(?<=\s)dans\s+(\d{1,3})\s+(jours?|semaines?|mois)(?=\s)""", RegexOption.IGNORE_CASE)
    private val weekdayRegex =
        Regex(
            """(?<=\s)(?:le\s+)?(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?:\s+prochain)?(?=\s)""",
            RegexOption.IGNORE_CASE,
        )
    private val numericDateRegex = Regex("""(?<=\s)(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?=\s)""")
    private val everyDayRegex = Regex("""(?<=\s)(?:tous\s+les\s+jours|chaque\s+jour)(?=\s)""", RegexOption.IGNORE_CASE)
    private val everyWeekRegex =
        Regex("""(?<=\s)(?:toutes\s+les\s+semaines|chaque\s+semaine)(?=\s)""", RegexOption.IGNORE_CASE)
    private val everyMonthRegex = Regex("""(?<=\s)(?:tous\s+les\s+mois|chaque\s+mois)(?=\s)""", RegexOption.IGNORE_CASE)
    private val everyNRegex =
        Regex("""(?<=\s)(?:tous|toutes)\s+les\s+(\d{1,3})\s+(jours|semaines)(?=\s)""", RegexOption.IGNORE_CASE)
    private val everyWeekdayRegex =
        Regex(
            """(?<=\s)chaque\s+(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)(?=\s)""",
            RegexOption.IGNORE_CASE,
        )

    /** « (le) 15 novembre », « 1er mai 2027 » : avant [dayOfMonthRegex], qui ne lirait que « le 15 ». */
    private val monthNameRegex =
        Regex(
            """(?<=\s)(?:le\s+)?(\d{1,2})(?:er)?\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)(?:\s+(\d{4}))?(?=\s)""",
            RegexOption.IGNORE_CASE,
        )
    private val monthNumbers =
        mapOf(
            "janvier" to 1,
            "février" to 2,
            "fevrier" to 2,
            "mars" to 3,
            "avril" to 4,
            "mai" to 5,
            "juin" to 6,
            "juillet" to 7,
            "août" to 8,
            "aout" to 8,
            "septembre" to 9,
            "octobre" to 10,
            "novembre" to 11,
            "décembre" to 12,
            "decembre" to 12,
        )
    private val dayOfMonthRegex = Regex("""(?<=\s)le\s+(\d{1,2})(?:er)?(?=\s)""", RegexOption.IGNORE_CASE)

    fun parse(
        input: String,
        today: LocalDate,
        projects: List<Project>,
        labels: List<Label>,
    ): QuickAdd {
        var s = " ${input.trim()} "
        var priority: Int? = null
        var projectId: Long? = null
        val matchedLabels = mutableListOf<Label>()

        priorityRegex.findAll(s).lastOrNull()?.let { m ->
            priority = priorityMap[m.groupValues[1].toInt()]
            s = s.removeRange(m.range)
        }

        projectRegex.findAll(s).toList().forEach { m ->
            val wanted = normalize(m.groupValues[1])
            val project = projects.firstOrNull { normalize(it.title) == wanted }
            if (project != null && projectId == null) {
                projectId = project.id
                s = s.replaceFirst(m.value, "")
            }
        }

        labelRegex.findAll(s).toList().forEach { m ->
            val wanted = normalize(m.groupValues[1])
            val label = labels.firstOrNull { normalize(it.title) == wanted }
            if (label != null && label !in matchedLabels) {
                matchedLabels += label
                s = s.replaceFirst(m.value, "")
            }
        }

        var time: LocalTime? = null
        timeRegex.find(s)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour in 0..23 && minute in 0..59) {
                time = LocalTime.of(hour, minute)
                s = s.removeRange(m.range)
            }
        }

        var date: LocalDate? = null
        var repeatAfter = 0L
        var repeatMode = 0
        everyNRegex.find(s)?.let { m ->
            val unit = if (m.groupValues[2].lowercase() == "semaines") WEEK_SECONDS else DAY_SECONDS
            repeatAfter = m.groupValues[1].toLong() * unit
            s = s.removeRange(m.range)
        }
        if (repeatAfter == 0L) {
            everyWeekdayRegex.find(s)?.let { m ->
                repeatAfter = WEEK_SECONDS
                val target = weekdays.getValue(m.groupValues[1].lowercase())
                var days = (target.value - today.dayOfWeek.value + 7) % 7
                if (days == 0) days = 7
                date = today.plusDays(days.toLong())
                s = s.removeRange(m.range)
            }
        }
        if (repeatAfter == 0L) {
            listOf(everyDayRegex to DAY_SECONDS, everyWeekRegex to WEEK_SECONDS)
                .firstNotNullOfOrNull { (regex, seconds) -> regex.find(s)?.let { m -> m to seconds } }
                ?.let { (m, seconds) ->
                    repeatAfter = seconds
                    s = s.removeRange(m.range)
                }
        }
        if (repeatAfter == 0L) {
            everyMonthRegex.find(s)?.let { m ->
                repeatMode = Task.REPEAT_MONTHLY
                s = s.removeRange(m.range)
            }
        }

        fun take(
            regex: Regex,
            compute: (MatchResult) -> LocalDate?,
        ) {
            if (date != null) return
            val m = regex.find(s) ?: return
            val result = compute(m) ?: return
            date = result
            s = s.removeRange(m.range)
        }
        take(dayAfterTomorrow) { today.plusDays(2) }
        take(todayRegex) { today }
        take(tomorrowRegex) { today.plusDays(1) }
        take(inRegex) { m ->
            val n = m.groupValues[1].toLong()
            when {
                m.groupValues[2].startsWith("semaine", ignoreCase = true) -> today.plusWeeks(n)
                m.groupValues[2].startsWith("mois", ignoreCase = true) -> today.plusMonths(n)
                else -> today.plusDays(n)
            }
        }
        take(weekdayRegex) { m ->
            val target = weekdays.getValue(m.groupValues[1].lowercase())
            var days = (target.value - today.dayOfWeek.value + 7) % 7
            if (days == 0) days = 7
            today.plusDays(days.toLong())
        }
        take(numericDateRegex) { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearText = m.groupValues[3]
            runCatching {
                if (yearText.isNotEmpty()) {
                    val year = yearText.toInt().let { if (it < 100) 2000 + it else it }
                    LocalDate.of(year, month, day)
                } else {
                    LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
                }
            }.getOrNull()
        }
        take(monthNameRegex) { m ->
            val day = m.groupValues[1].toInt()
            val month = monthNumbers.getValue(m.groupValues[2].lowercase())
            val yearText = m.groupValues[3]
            runCatching {
                if (yearText.isNotEmpty()) {
                    LocalDate.of(yearText.toInt(), month, day)
                } else {
                    LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
                }
            }.getOrNull()
        }
        take(dayOfMonthRegex) { m ->
            val day = m.groupValues[1].toInt()
            runCatching {
                val thisMonth = LocalDate.of(today.year, today.month, day)
                if (thisMonth.isBefore(today)) thisMonth.plusMonths(1) else thisMonth
            }.getOrNull()
        }

        // Vikunja ne répète que les tâches qui ont une échéance : sans date, la première occurrence est aujourd'hui.
        if ((repeatAfter > 0 || repeatMode != 0) && date == null && time == null) date = today
        return QuickAdd(
            title = s.trim().replace(Regex("""\s+"""), " "),
            repeatAfter = repeatAfter,
            repeatMode = repeatMode,
            date = date,
            time = time,
            priority = priority,
            projectId = projectId,
            labels = matchedLabels,
        )
    }

    private fun normalize(text: String): String =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[\s_-]+"""), "")
}
