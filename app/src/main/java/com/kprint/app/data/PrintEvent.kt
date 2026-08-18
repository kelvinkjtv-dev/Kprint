package com.kprint.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class EventType { PRINTED, ERROR, INFO }

data class PrintEvent(
    val id: String,
    val title: String,
    val detail: String,
    val timestamp: String,
    val type: EventType,
)

class EventLogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(title: String, detail: String, type: EventType, id: String = Instant.now().toEpochMilli().toString()) {
        val updated = mutableListOf(
            PrintEvent(id, title, detail, Instant.now().toString(), type),
        ).apply { addAll(load()) }.take(MAX_EVENTS)
        save(updated)
    }

    @Synchronized
    fun load(): List<PrintEvent> {
        val data = preferences.getString(KEY_EVENTS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(data)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PrintEvent(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            timestamp = item.optString("timestamp"),
                            type = runCatching { EventType.valueOf(item.optString("type")) }
                                .getOrDefault(EventType.INFO),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear() = preferences.edit().remove(KEY_EVENTS).apply()

    private fun save(events: List<PrintEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("title", event.title)
                    .put("detail", event.detail)
                    .put("timestamp", event.timestamp)
                    .put("type", event.type.name),
            )
        }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "kprint_events"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 100
    }
}
