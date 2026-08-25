package com.piercingxx.xxclock.data

import android.content.Context
import android.content.SharedPreferences
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single persistence point: SharedPreferences + org.json (no Room, no external deps).
 *
 * Layout:
 *  KEY_ALARMS  : JSON array of alarm objects
 *  KEY_TIMERS  : JSON array of timer objects
 *  KEY_RUNTIME : JSON object { alarmId -> { scheduledFire, snoozedUntil, ringing } }
 *
 * Runtime state is deliberately separate from definitions so a cold start can
 * reconstruct where each alarm lifecycle is (scheduled / snoozed / ringing).
 */
class ClockStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ alarms

    @Synchronized
    fun alarms(): List<Alarm> = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
        .sortedWith(compareBy({ it.hour }, { it.minute }))

    @Synchronized
    fun getAlarm(id: Long): Alarm? = alarms().firstOrNull { it.id == id }

    @Synchronized
    fun saveAlarm(alarm: Alarm) {
        val current = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
            .filterNot { it.id == alarm.id }
        prefs.edit().putString(KEY_ALARMS, JSONArray(current.map(::alarmToJson) + alarmToJson(alarm)).toString()).apply()
    }

    @Synchronized
    fun deleteAlarm(id: Long) {
        val remaining = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
            .filterNot { it.id == id }
        prefs.edit().putString(KEY_ALARMS, JSONArray(remaining.map(::alarmToJson)).toString()).apply()
        clearRuntime(id)
    }

    // ------------------------------------------------------------------ timers

    @Synchronized
    fun timers(): List<TimerItem> = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)

    @Synchronized
    fun getTimer(id: Long): TimerItem? = timers().firstOrNull { it.id == id }

    @Synchronized
    fun saveTimer(timer: TimerItem) {
        val current = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)
            .filterNot { it.id == timer.id }
        prefs.edit().putString(KEY_TIMERS, JSONArray(current.map(::timerToJson) + timerToJson(timer)).toString()).apply()
    }

    @Synchronized
    fun deleteTimer(id: Long) {
        val remaining = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)
            .filterNot { it.id == id }
        prefs.edit().putString(KEY_TIMERS, JSONArray(remaining.map(::timerToJson)).toString()).apply()
        clearRuntime(id)
    }

    // ------------------------------------------------------- per-alarm runtime

    @Synchronized
    fun scheduledFire(id: Long): Long = runtimeFor(id).optLong("scheduledFire", 0L)

    @Synchronized
    fun setScheduledFire(id: Long, atMs: Long) = mutateRuntime(id) { it.put("scheduledFire", atMs) }

    @Synchronized
    fun clearScheduledFire(id: Long) = mutateRuntime(id) { it.remove("scheduledFire") }

    @Synchronized
    fun snoozedUntil(id: Long): Long = runtimeFor(id).optLong("snoozedUntil", 0L)

    @Synchronized
    fun setSnoozedUntil(id: Long, untilMs: Long) = mutateRuntime(id) { it.put("snoozedUntil", untilMs) }

    @Synchronized
    fun isRinging(id: Long): Boolean = runtimeFor(id).optBoolean("ringing", false)

    @Synchronized
    fun setRinging(id: Long, ringing: Boolean) = mutateRuntime(id) { it.put("ringing", ringing) }

    /** The single alarm or timer currently ringing, if any (newest-wins policy allows one). */
    @Synchronized
    fun ringingId(): Long? {
        val runtime = decodeObject(prefs.getString(KEY_RUNTIME, null))
        for (key in runtime.keys()) {
            if (runtime.getJSONObject(key).optBoolean("ringing", false)) return key.toLongOrNull()
        }
        return null
    }

    @Synchronized
    fun clearRuntime(id: Long) = mutateRuntime(id) { it.remove("scheduledFire"); it.remove("snoozedUntil"); it.remove("ringing") }

    // ------------------------------------------------------------------ internals

    private fun runtimeFor(id: Long): JSONObject =
        decodeObject(prefs.getString(KEY_RUNTIME, null)).optJSONObject(id.toString()) ?: JSONObject()

    private fun mutateRuntime(id: Long, mutation: (JSONObject) -> Unit) {
        val root = decodeObject(prefs.getString(KEY_RUNTIME, null))
        val entry = root.optJSONObject(id.toString()) ?: JSONObject()
        mutation(entry)
        root.put(id.toString(), entry)
        prefs.edit().putString(KEY_RUNTIME, root.toString()).apply()
    }

    private fun decodeObject(raw: String?): JSONObject =
        if (raw.isNullOrBlank()) {
            JSONObject()
        } else {
            // Corruption tolerance: a truncated prefs blob must never crash startup.
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        }

    private fun <T> decodeList(raw: String?, fromJson: (JSONObject) -> T): MutableList<T> {
        val out = mutableListOf<T>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()?.let { out += it }
                }
            }
        }
        return out
    }

    private fun alarmFromJson(o: JSONObject) = Alarm(
        id = o.getLong("id"),
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        daysMask = o.getInt("daysMask"),
        label = o.optString("label", ""),
        enabled = o.getBoolean("enabled"),
        vibrate = o.getBoolean("vibrate"),
    )

    private fun alarmToJson(a: Alarm) = JSONObject()
        .put("id", a.id)
        .put("hour", a.hour)
        .put("minute", a.minute)
        .put("daysMask", a.daysMask)
        .put("label", a.label)
        .put("enabled", a.enabled)
        .put("vibrate", a.vibrate)

    private fun timerFromJson(o: JSONObject) = TimerItem(
        id = o.getLong("id"),
        durationMs = o.getLong("durationMs"),
        state = o.optString("state", TimerItem.STATE_IDLE),
        endsAtEpochMs = o.optLong("endsAtEpochMs", 0L),
        remainingMs = o.optLong("remainingMs", 0L),
        label = o.optString("label", ""),
    )

    private fun timerToJson(t: TimerItem) = JSONObject()
        .put("id", t.id)
        .put("durationMs", t.durationMs)
        .put("state", t.state)
        .put("endsAtEpochMs", t.endsAtEpochMs)
        .put("remainingMs", t.remainingMs)
        .put("label", t.label)

    companion object {
        private const val FILE_NAME = "xx_clock"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_TIMERS = "timers"
        private const val KEY_RUNTIME = "runtime"

        @Volatile
        private var instance: ClockStore? = null

        fun get(context: Context): ClockStore =
            instance ?: synchronized(this) {
                instance ?: ClockStore(context).also { instance = it }
            }
    }
}
