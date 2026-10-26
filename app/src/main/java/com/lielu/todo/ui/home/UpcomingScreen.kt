package com.lielu.todo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.settings.UpcomingColoring
import com.lielu.todo.data.settings.UpcomingLayout
import com.lielu.todo.data.settings.UpcomingSettings
import com.lielu.todo.data.settings.UpcomingSort
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.ui.common.ScreenTitle
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import com.lielu.todo.ui.theme.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val GUTTER = 52.dp
private val HOUR_HEIGHT = 64.dp
private const val DAYS_SHOWN = 3
private const val SWIPE_THRESHOLD_DP = 60
private const val HOURS_BEFORE_NOW = 3
private const val MINUTES_PER_HOUR = 60f
private val monthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)
private val agendaDay = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.FRENCH)
private val shortDay = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)
private val dayLetters = listOf("D", "L", "M", "M", "J", "V", "S")

private fun LocalDate.monthLabel() = YearMonth.from(this).format(monthTitle).replaceFirstChar { it.uppercase() }

/** « Prochainement » façon Todoist : vue Agenda (bande de dates + liste) ou grille horaire sur 3 jours. */
@Composable
internal fun UpcomingTab(
    state: TodoState,
    today: LocalDate,
    settings: UpcomingSettings,
    onSettings: (UpcomingSettings) -> Unit,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    actions: @Composable () -> Unit,
    filterTitle: String?,
) {
    if (!state.loaded) {
        LoadingState()
        return
    }
    var showDisplay by remember { mutableStateOf(false) }
    var selectedEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val zone = remember { ZoneId.systemDefault() }
    val titleActions: @Composable () -> Unit = {
        BadgedBox(badge = { if (settings != UpcomingSettings()) Badge() }) {
            IconButton(onClick = { showDisplay = true }) { Icon(Icons.Outlined.Tune, contentDescription = "Affichage") }
        }
        actions()
    }
    val title = filterTitle?.let { "Projet : $it" }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    when (settings.layout) {
        UpcomingLayout.AGENDA ->
            AgendaView(
                state,
                today,
                settings,
                selected,
                { selectedEpoch = it.toEpochDay() },
                onToggle,
                onOpenTask,
                title,
                titleActions,
                zone,
            )
        UpcomingLayout.THREE_DAYS ->
            ThreeDaysView(
                state,
                today,
                settings,
                selected,
                { selectedEpoch = it.toEpochDay() },
                onOpenTask,
                title,
                titleActions,
                zone,
            )
    }
    if (showDisplay) {
        DisplaySheet(settings, onSave = onSettings, onDismiss = { showDisplay = false })
    }
}

// --- Vue Agenda -------------------------------------------------------------------------------------------

@Composable
private fun AgendaView(
    state: TodoState,
    today: LocalDate,
    settings: UpcomingSettings,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    subtitle: String?,
    actions: @Composable () -> Unit,
    zone: ZoneId,
) {
    val colors = LocalTodoColors.current
    val scope = rememberCoroutineScope()
    val projects = remember(state.projects) { state.projects.associate { it.id to it.title } }
    val overdue =
        remember(state.tasks, today) {
            state.tasks
                .filter { !it.done && it.plannedAt != null && it.plannedAt!!.localDate(zone).isBefore(today) }
                .sortedBy { it.plannedAt }
        }
    val entries =
        remember(state.tasks, settings, today) {
            val occurrences =
                upcomingOccurrences(state.tasks, settings, today, today.plusDays(AGENDA_DAYS - 1L), zone)
            agendaEntries(occurrences, today, AGENDA_DAYS, settings.sort, zone)
        }
    val dayIndex =
        remember(entries) {
            entries.withIndex().filter { it.value is AgendaEntry.Header }.associate { it.value.day to it.index }
        }
    // Les lignes « En retard » (titre + tâches) précèdent les jours dans la liste.
    val offset = if (overdue.isEmpty()) 0 else overdue.size + 1
    val firstDay = maxOf(selected, today)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = offset + (dayIndex[firstDay] ?: 0))
    var expanded by remember { mutableStateOf(false) }

    // La date sélectionnée suit le défilement.
    LaunchedEffect(entries, offset) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            onSelect(entries.getOrNull(index - offset)?.day ?: today)
        }
    }
    val jump: (LocalDate) -> Unit = { target ->
        val day = maxOf(target, today)
        onSelect(day)
        scope.launch { listState.scrollToItem(offset + (dayIndex[day] ?: entries.lastIndex)) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTitle("Prochainement", subtitle, actions = actions)
        MonthHeader(selected.monthLabel(), expanded) { expanded = !expanded }
        if (expanded) {
            MonthPanel(selected, today, onToday = { jump(today) }) {
                jump(it)
                expanded = false
            }
        } else {
            WeekStrip(selected, today, onPick = jump, onShiftWeek = { jump(selected.plusDays(7L * it)) })
        }
        DragHandle { expanded = !expanded }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (overdue.isNotEmpty()) {
                item { SectionHeader("En retard", color = colors.overdue, count = overdue.size) }
                items(overdue, key = { "late-${it.id}" }) {
                    TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) })
                }
            }
            items(entries, key = {
                when (it) {
                    is AgendaEntry.Header -> "day-${it.day}"
                    is AgendaEntry.Empty -> "empty-${it.day}"
                    is AgendaEntry.Item -> "${it.day}-${it.occurrence.key}"
                }
            }) { entry ->
                when (entry) {
                    is AgendaEntry.Header -> AgendaDayHeader(entry.day, today)
                    is AgendaEntry.Empty -> Spacer(Modifier.height(32.dp))
                    is AgendaEntry.Item -> {
                        val occurrence = entry.occurrence
                        val task =
                            if (occurrence.future) {
                                occurrence.task.copy(
                                    dueDate = occurrence.at,
                                )
                            } else {
                                occurrence.task
                            }
                        TaskRow(
                            task,
                            today,
                            projects[task.projectId],
                            // Une occurrence future n'existe pas encore : on ne peut ni la terminer ni la rouvrir.
                            { d -> if (!occurrence.future) onToggle(occurrence.task, d) },
                            { onOpenTask(task.id) },
                            modifier = if (occurrence.future) Modifier.alpha(0.6f) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaDayHeader(
    day: LocalDate,
    today: LocalDate,
) {
    val suffix =
        when (day.toEpochDay() - today.toEpochDay()) {
            0L -> " • Aujourd'hui"
            1L -> " • Demain"
            else -> ""
        }
    Text(
        text = day.format(agendaDay).replaceFirstChar { it.uppercase() } + suffix,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// --- Bande de dates, mois -----------------------------------------------------------------------------------

@Composable
private fun MonthHeader(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = if (expanded) "Réduire le calendrier" else "Afficher le mois",
        )
    }
}

@Composable
private fun DragHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun DayLetters() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        dayLetters.forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun RowScope.DayCell(
    day: LocalDate,
    selected: Boolean,
    today: LocalDate,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.weight(1f).height(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        36.dp,
                    ).background(if (selected) scheme.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                color =
                    when {
                        selected -> scheme.onPrimary
                        day == today -> scheme.primary
                        dimmed -> scheme.outline
                        else -> scheme.onSurface
                    },
            )
        }
    }
}

/** Les 7 jours de la semaine de [selected] ; un balayage horizontal passe à la semaine suivante ou précédente. */
@Composable
private fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onShiftWeek: (Int) -> Unit,
) {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val shift by rememberUpdatedState(onShiftWeek)
    Column(
        modifier =
            Modifier.pointerInput(threshold) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > threshold) shift(if (total < 0) 1 else -1)
                    },
                ) { _, delta -> total += delta }
            },
    ) {
        DayLetters()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            weekDays(selected).forEach { day ->
                DayCell(day, day == selected, today, dimmed = day.isBefore(today)) { onPick(day) }
            }
        }
    }
}

/** Calendrier d'un mois, avec flèches de navigation ; [onToday] ramène à aujourd'hui. */
@Composable
private fun MonthPanel(
    selected: LocalDate,
    today: LocalDate,
    onToday: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Mois précédent")
            }
            Text(
                month.atDay(1).monthLabel(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Mois suivant")
            }
            TextButton(onClick = onToday) { Text("Aujourd'hui") }
        }
        DayLetters()
        monthWeeks(month).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                week.forEach { day ->
                    DayCell(day, day == selected, today, dimmed = YearMonth.from(day) != month || day.isBefore(today)) {
                        onPick(day)
                    }
                }
            }
        }
    }
}

// --- Vue 3 jours ------------------------------------------------------------------------------------------

@Composable
private fun ThreeDaysView(
    state: TodoState,
    today: LocalDate,
    settings: UpcomingSettings,
    start: LocalDate,
    onStart: (LocalDate) -> Unit,
    onOpenTask: (Long) -> Unit,
    subtitle: String?,
    actions: @Composable () -> Unit,
    zone: ZoneId,
) {
    val days = remember(start) { (0 until DAYS_SHOWN).map { start.plusDays(it.toLong()) } }
    val occurrences =
        remember(state.tasks, settings, start) {
            upcomingOccurrences(state.tasks, settings, start, start.plusDays(DAYS_SHOWN - 1L), zone)
        }
    val projectColors = remember(state.projects) { state.projects.associate { it.id to it.hexColor } }
    val projectColor = { id: Long -> projectColors[id].orEmpty() }
    var expanded by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val currentStart by rememberUpdatedState(start)
    val onStartChange by rememberUpdatedState(onStart)

    Column(
        modifier =
            Modifier.fillMaxSize().pointerInput(threshold) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (abs(total) > threshold) {
                            onStartChange(
                                currentStart.plusDays(
                                    if (total <
                                        0
                                    ) {
                                        DAYS_SHOWN.toLong()
                                    } else {
                                        -DAYS_SHOWN.toLong()
                                    },
                                ),
                            )
                        }
                    },
                ) { _, delta -> total += delta }
            },
    ) {
        ScreenTitle("Prochainement", subtitle, actions = actions)
        MonthHeader(start.monthLabel(), expanded) { expanded = !expanded }
        if (expanded) {
            MonthPanel(start, today, onToday = { onStart(today) }) {
                onStart(it)
                expanded = false
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Spacer(Modifier.width(GUTTER))
            days.forEach { day -> ColumnHeader(day, today) }
        }
        AllDayRow(days, occurrences, settings, zone, projectColor, onOpenTask)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TimeGrid(days, today, occurrences, settings, zone, projectColor, onOpenTask)
    }
}

@Composable
private fun RowScope.ColumnHeader(
    day: LocalDate,
    today: LocalDate,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day.format(shortDay), style = MaterialTheme.typography.bodyMedium, color = scheme.outline)
        Box(
            modifier =
                Modifier
                    .padding(start = 6.dp)
                    .size(30.dp)
                    .background(if (day == today) scheme.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (day == today) scheme.onPrimary else scheme.onSurface,
            )
        }
    }
}

private fun blockColor(
    occurrence: Occurrence,
    settings: UpcomingSettings,
    colors: com.lielu.todo.ui.theme.TodoColors,
    projectHex: String,
    fallback: Color,
): Color =
    when (settings.coloring) {
        UpcomingColoring.PRIORITY -> colors.forPriority(occurrence.task.priority)
        UpcomingColoring.PROJECT -> parseHexColor(projectHex, fallback)
    }

@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    settings: UpcomingSettings,
    zone: ZoneId,
    projectColor: (Long) -> String,
    onOpenTask: (Long) -> Unit,
) {
    val colors = LocalTodoColors.current
    val outline = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(
            "Toute la journée",
            style = MaterialTheme.typography.labelSmall,
            color = outline,
            modifier = Modifier.width(GUTTER).padding(start = 4.dp),
        )
        days.forEach { day ->
            Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                occurrences
                    .filter { it.day(zone) == day && it.minuteOfDay(zone) == null }
                    .sortedFor(settings.sort)
                    .forEach { occurrence ->
                        val color =
                            blockColor(occurrence, settings, colors, projectColor(occurrence.task.projectId), outline)
                        EventBlock(occurrence, color, Modifier.fillMaxWidth(), singleLine = true, onOpenTask)
                    }
            }
        }
    }
}

@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    occurrences: List<Occurrence>,
    settings: UpcomingSettings,
    zone: ZoneId,
    projectColor: (Long) -> String,
    onOpenTask: (Long) -> Unit,
) {
    val colors = LocalTodoColors.current
    val scheme = MaterialTheme.colorScheme
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000)
            value = LocalTime.now()
        }
    }
    val initial =
        with(LocalDensity.current) { (HOUR_HEIGHT * maxOf(0, LocalTime.now().hour - HOURS_BEFORE_NOW)).roundToPx() }
    val scroll = rememberScrollState(initial)
    val placed =
        remember(occurrences, days) {
            days.map { day ->
                placeDay(occurrences.filter { it.day(zone) == day && it.minuteOfDay(zone) != null }, zone)
            }
        }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
            for (hour in 0 until 24) {
                Text(
                    "%02d:00".format(hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    modifier = Modifier.offset(x = 4.dp, y = HOUR_HEIGHT * hour).width(GUTTER),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = GUTTER).offset(y = HOUR_HEIGHT * hour + 8.dp),
                    color = scheme.outlineVariant,
                )
            }
            Row(Modifier.padding(start = GUTTER).fillMaxSize()) {
                days.forEachIndexed { index, day ->
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        VerticalDivider(color = scheme.outlineVariant, modifier = Modifier.fillMaxHeight())
                        placed[index].forEach { slot ->
                            val laneWidth = maxWidth / slot.lanes
                            val color =
                                blockColor(
                                    slot.occurrence,
                                    settings,
                                    colors,
                                    projectColor(slot.occurrence.task.projectId),
                                    scheme.outline,
                                )
                            EventBlock(
                                slot.occurrence,
                                color,
                                Modifier
                                    .offset(
                                        x = laneWidth * slot.lane,
                                        y =
                                            HOUR_HEIGHT * (slot.startMinute / MINUTES_PER_HOUR) + 8.dp,
                                    ).width(laneWidth)
                                    .height(HOUR_HEIGHT * (BLOCK_MINUTES / MINUTES_PER_HOUR)),
                                singleLine = false,
                                onOpenTask,
                            )
                        }
                        if (day == today) NowLine(now, thick = true)
                    }
                }
            }
            if (today in days) {
                Text(
                    "%02d:%02d".format(now.hour, now.minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier =
                        Modifier
                            .offset(x = 4.dp, y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / MINUTES_PER_HOUR))
                            .background(scheme.background)
                            .width(GUTTER - 8.dp),
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

/** Trait de l'heure courante dans la colonne d'aujourd'hui. */
@Composable
private fun NowLine(
    now: LocalTime,
    thick: Boolean,
) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .offset(y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / MINUTES_PER_HOUR) + 8.dp - 1.dp)
            .height(if (thick) 2.dp else 1.dp)
            .background(color),
    )
}

@Composable
private fun EventBlock(
    occurrence: Occurrence,
    color: Color,
    modifier: Modifier,
    singleLine: Boolean,
    onOpenTask: (Long) -> Unit,
) {
    val task = occurrence.task
    Surface(
        modifier = modifier.padding(1.dp).clickable { onOpenTask(task.id) },
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = if (task.done || occurrence.future) 0.12f else 0.22f),
    ) {
        Row {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
            Text(
                task.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (singleLine) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// --- Fenêtre « Affichage » --------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySheet(
    saved: UpcomingSettings,
    onSave: (UpcomingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(saved) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Affichage",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SheetLabel("Présentation")
                SheetCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        LayoutOption(Icons.Outlined.ViewAgenda, "Agenda", draft.layout == UpcomingLayout.AGENDA) {
                            draft = draft.copy(layout = UpcomingLayout.AGENDA)
                        }
                        Spacer(Modifier.width(32.dp))
                        LayoutOption(Icons.Outlined.ViewWeek, "3 jours", draft.layout == UpcomingLayout.THREE_DAYS) {
                            draft = draft.copy(layout = UpcomingLayout.THREE_DAYS)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(Icons.Outlined.CheckCircle, "Tâches achevées", draft.showDone) {
                    draft =
                        draft.copy(showDone = it)
                }
                if (draft.layout == UpcomingLayout.THREE_DAYS) {
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(
                        Icons.Outlined.Palette,
                        "Coloration",
                        draft.coloring,
                        UpcomingColoring.entries,
                        { it.label },
                    ) { draft = draft.copy(coloring = it) }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(Icons.Filled.Repeat, "Futures occurrences", draft.futureOccurrences) {
                    draft = draft.copy(futureOccurrences = it)
                }
                SheetLabel("Trier")
                ChoiceRow(Icons.Filled.SwapVert, "Trier", draft.sort, UpcomingSort.entries, { it.label }) {
                    draft = draft.copy(sort = it)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalButton(onClick = { draft = UpcomingSettings() }) { Text("Tout réinitialiser") }
                Button(onClick = {
                    onSave(draft)
                    onDismiss()
                }) { Text(if (draft == saved) "Terminé" else "Enregistrer") }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

@Composable
private fun LayoutOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.outline
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 56.dp, height = 88.dp)
                    .border(2.dp, tint, RoundedCornerShape(10.dp))
                    .background(
                        if (selected) scheme.primaryContainer else scheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, modifier = Modifier.padding(top = 8.dp))
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SheetCard {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChange(
                            !checked,
                        )
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    value: T,
    options: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SheetCard {
        Box {
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable { open = true }.padding(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        label(value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            onPick(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
