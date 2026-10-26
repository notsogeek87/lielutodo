package com.lielu.todo.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lielu.todo.data.settings.AppPrefs
import com.lielu.todo.data.todo.TodoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.util.TimeZone

/** Un agenda de l'appareil dans lequel on peut écrire. */
data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
)

/**
 * Recopie les tâches datées dans un agenda de l'appareil (sens tâche -> agenda uniquement). Les événements sont créés
 * sans rappel : seule l'app sonne, avec ses boutons Terminer/Reporter. La correspondance tâche -> événement est
 * gardée ici, hors sauvegarde : elle n'a de sens que sur cet appareil.
 */
class CalendarSync(
    context: Context,
    private val prefs: AppPrefs,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val store = this.context.getSharedPreferences("calendar_sync", Context.MODE_PRIVATE)
    private val lock = Mutex()

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Agendas visibles où l'on peut ajouter des événements. */
    suspend fun calendars(): List<DeviceCalendar> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val projection =
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                )
            val selection =
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
            val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
            val found = mutableListOf<DeviceCalendar>()
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    found +=
                        DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
                }
            }
            found
        }

    /**
     * Met l'agenda en accord avec [state]. Désactivé, sans autorisation ou sans agenda choisi : retire ce qu'on y
     * avait écrit. Tant que l'état n'est pas chargé, ne fait rien (une liste vide effacerait tout à tort).
     */
    suspend fun sync(
        state: TodoState,
        zone: ZoneId = ZoneId.systemDefault(),
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!state.loaded) return@withContext
            val calendarId = prefs.calendarId
            val active = prefs.calendarSyncEnabled && calendarId > 0 && hasPermission()
            var synced = readSynced()
            // Autre agenda choisi : on vide l'ancien avant de remplir le nouveau.
            if (store.getLong(KEY_CALENDAR, 0) != calendarId) {
                if (hasPermission()) {
                    synced.forEach { (_, e) -> deleteEvent(e.eventId) }
                    synced = emptyMap()
                }
            }
            if (!active) {
                if (hasPermission()) synced.forEach { (_, e) -> deleteEvent(e.eventId) }
                writeSynced(emptyMap(), if (hasPermission()) 0 else calendarId)
                return@withContext
            }
            // Un événement supprimé à la main dans l'agenda est recréé à la passe suivante.
            val alive = existingEventIds(calendarId)
            val current = synced.filterValues { it.eventId in alive }.toMutableMap()
            reconcileEvents(planCalendarEvents(state.tasks, zone), current).forEach { action ->
                when (action) {
                    is CalendarAction.Delete -> {
                        deleteEvent(action.eventId)
                        current.remove(action.taskId)
                    }
                    is CalendarAction.Update -> {
                        if (updateEvent(action.eventId, action.event)) {
                            current[action.event.taskId] = SyncedEvent(action.eventId, action.event.fingerprint)
                        }
                    }
                    is CalendarAction.Insert -> {
                        insertEvent(calendarId, action.event)?.let {
                            current[action.event.taskId] = SyncedEvent(it, action.event.fingerprint)
                        }
                    }
                }
            }
            writeSynced(current, calendarId)
        }
    }

    private fun existingEventIds(calendarId: Long): Set<Long> {
        val ids = mutableSetOf<Long>()
        resolver
            .query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(calendarId.toString()),
                null,
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids
    }

    private fun values(event: PlannedEvent): ContentValues =
        ContentValues().apply {
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (event.allDay) "UTC" else TimeZone.getDefault().id,
            )
            // Pas de rappel côté agenda : l'app s'en charge (évite le double rappel).
            put(CalendarContract.Events.HAS_ALARM, 0)
            if (event.allDay) put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
        }

    private fun insertEvent(
        calendarId: Long,
        event: PlannedEvent,
    ): Long? =
        runCatching {
            val v = values(event).apply { put(CalendarContract.Events.CALENDAR_ID, calendarId) }
            resolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
        }.getOrNull()

    private fun updateEvent(
        eventId: Long,
        event: PlannedEvent,
    ): Boolean =
        runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            resolver.update(uri, values(event), null, null) > 0
        }.getOrDefault(false)

    private fun deleteEvent(eventId: Long) {
        runCatching {
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
        }
    }

    private fun readSynced(): Map<Long, SyncedEvent> =
        runCatching {
            val json = JSONObject(store.getString(KEY_EVENTS, "{}").orEmpty())
            json.keys().asSequence().associate { key ->
                val o = json.getJSONObject(key)
                key.toLong() to SyncedEvent(o.getLong("event"), o.getString("fp"))
            }
        }.getOrDefault(emptyMap())

    private fun writeSynced(
        synced: Map<Long, SyncedEvent>,
        calendarId: Long,
    ) {
        val json = JSONObject()
        synced.forEach { (taskId, e) ->
            json.put(taskId.toString(), JSONObject().put("event", e.eventId).put("fp", e.fingerprint))
        }
        store
            .edit()
            .putString(KEY_EVENTS, json.toString())
            .putLong(KEY_CALENDAR, calendarId)
            .apply()
    }

    private companion object {
        const val KEY_EVENTS = "events"
        const val KEY_CALENDAR = "calendar"
    }
}
