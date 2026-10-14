package com.nya.dstranslate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Одна запись истории переводов режима «Текст». */
data class HistoryEntry(
    val id: Long,
    val from: Lang,
    val source: String,
    val target: String,
    val at: Long
)

/** История хранится в SharedPreferences компактным JSON-массивом. */
class HistoryStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("ds_history", Context.MODE_PRIVATE)

    fun load(): MutableList<HistoryEntry> {
        val raw = sp.getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                HistoryEntry(
                    id = o.optLong("id"),
                    from = if (o.optString("from") == "zh") Lang.ZH else Lang.RU,
                    source = o.optString("source"),
                    target = o.optString("target"),
                    at = o.optLong("at")
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("from", e.from.tag)
                    .put("source", e.source)
                    .put("target", e.target)
                    .put("at", e.at)
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 200
    }
}
