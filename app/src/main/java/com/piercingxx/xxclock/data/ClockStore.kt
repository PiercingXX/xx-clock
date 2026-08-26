package com.piercingxx.xxclock.data

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The persistence surface the coordinator and repositories consume. Exists so
 * JVM tests can substitute an in-memory backend (family rule: seams instead of
 * Android mocks); [ClockStore] is the only production implementation.
 */
interface ClockStoreBackend {
    fun alarms(): List<Alarm>
    fun getAlarm(id: Long): Alarm?
    fun saveAlarm(alarm: Alarm)
    fun deleteAlarm(id: Long)

    fun timers(): List<TimerItem>
    fun getTimer(id: Long): TimerItem?
    fun saveTimer(timer: TimerItem)
    fun deleteTimer(id: Long)

    fun scheduledFire(id: Long): Long
    fun setScheduledFire(id: Long, atMs: Long)
    fun clearScheduledFire(id: Long)

    fun snoozedUntil(id: Long): Long
    fun setSnoozedUntil(id: Long, untilMs: Long)

    fun isRinging(id: Long): Boolean
    fun setRinging(id: Long, ringing: Boolean)

    /** The single alarm or timer currently ringing, if any (newest-wins allows one). */
    fun ringingId(): Long?

    fun clearRuntime(id: Long)
}

/**
 * Storage-agnostic strictly-increasing id source over ONE shared alarm/timer
 * namespace (P2.11). Each mint takes max(persisted slot, floor) + 1, so ids
 * survive process death, never regress, and stay above the floor: production
 * floors at max(highest stored alarm/timer id, wall clock), which permanently
 * retires every legacy timestamp id. Lambdas instead of SharedPreferences keep
 * the sequence drivable from JVM tests (seams-over-mocks rule).
 */
internal class MonotonicIdSequence(
    private val load: () -> Long,
    private val store: (Long) -> Unit,
    private val floorOf: () -> Long,
) {
    private val lock = Any()

    fun next(): Long = synchronized(lock) {
        val next = maxOf(load(), floorOf()) + 1L
        store(next)
        next
    }
}

/**
 * Single persistence point: SharedPreferences + org.json (no Room, no external deps).
 *
 * Layout:
 *  KEY_ALARMS  : JSON array of alarm objects
 *  KEY_TIMERS  : JSON array of timer objects
 *  KEY_RUNTIME : JSON object { alarmId -> { scheduledFire, snoozedUntil, ringing } }
 *  KEY_NEXT_ID : monotonic counter for new alarm/timer ids (P2.11)
 *
 * Runtime state is deliberately separate from definitions so a cold start can
 * reconstruct where each alarm lifecycle is (scheduled / snoozed / ringing).
 *
 * All of it lives in DEVICE-PROTECTED storage: alarms must be readable and
 * re-armable by LOCKED_BOOT_COMPLETED before the user's first unlock (direct
 * boot), so they never wait on credential-encrypted storage. The one exception
 * window is data written there by older installs; [get] performs a one-time
 * post-unlock pull of that data forward.
 */
class ClockStore private constructor(context: Context) : ClockStoreBackend {

    private val prefs: SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        // Bind the shared id counter to device-protected prefs: mints work
        // pre-unlock and survive process death, and the floor re-checks stored
        // ids plus the wall clock so legacy timestamp ids are never reissued.
        idSequence = MonotonicIdSequence(
            load = { prefs.getLong(KEY_NEXT_ID, 0L) },
            store = { prefs.edit().putLong(KEY_NEXT_ID, it).apply() },
            floorOf = {
                maxOf(
                    decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
                        .maxOfOrNull { it.id } ?: 0L,
                    decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)
                        .maxOfOrNull { it.id } ?: 0L,
                    System.currentTimeMillis(),
                )
            },
        )
    }

    // ------------------------------------------------------------------ alarms

    @Synchronized
    override fun alarms(): List<Alarm> = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
        .sortedWith(compareBy({ it.hour }, { it.minute }))

    @Synchronized
    override fun getAlarm(id: Long): Alarm? = alarms().firstOrNull { it.id == id }

    @Synchronized
    override fun saveAlarm(alarm: Alarm) {
        val current = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
            .filterNot { it.id == alarm.id }
        prefs.edit().putString(KEY_ALARMS, JSONArray(current.map(::alarmToJson) + alarmToJson(alarm)).toString()).apply()
    }

    @Synchronized
    override fun deleteAlarm(id: Long) {
        val remaining = decodeList(prefs.getString(KEY_ALARMS, null), ::alarmFromJson)
            .filterNot { it.id == id }
        prefs.edit().putString(KEY_ALARMS, JSONArray(remaining.map(::alarmToJson)).toString()).apply()
        clearRuntime(id)
    }

    // ------------------------------------------------------------------ timers

    @Synchronized
    override fun timers(): List<TimerItem> = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)

    @Synchronized
    override fun getTimer(id: Long): TimerItem? = timers().firstOrNull { it.id == id }

    @Synchronized
    override fun saveTimer(timer: TimerItem) {
        val current = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)
            .filterNot { it.id == timer.id }
        prefs.edit().putString(KEY_TIMERS, JSONArray(current.map(::timerToJson) + timerToJson(timer)).toString()).apply()
    }

    @Synchronized
    override fun deleteTimer(id: Long) {
        val remaining = decodeList(prefs.getString(KEY_TIMERS, null), ::timerFromJson)
            .filterNot { it.id == id }
        prefs.edit().putString(KEY_TIMERS, JSONArray(remaining.map(::timerToJson)).toString()).apply()
        clearRuntime(id)
    }

    // ------------------------------------------------------- per-alarm runtime

    @Synchronized
    override fun scheduledFire(id: Long): Long = runtimeFor(id).optLong("scheduledFire", 0L)

    @Synchronized
    override fun setScheduledFire(id: Long, atMs: Long) = mutateRuntime(id) { it.put("scheduledFire", atMs) }

    @Synchronized
    override fun clearScheduledFire(id: Long) = mutateRuntime(id) { it.remove("scheduledFire") }

    @Synchronized
    override fun snoozedUntil(id: Long): Long = runtimeFor(id).optLong("snoozedUntil", 0L)

    @Synchronized
    override fun setSnoozedUntil(id: Long, untilMs: Long) = mutateRuntime(id) { it.put("snoozedUntil", untilMs) }

    @Synchronized
    override fun isRinging(id: Long): Boolean = runtimeFor(id).optBoolean("ringing", false)

    @Synchronized
    override fun setRinging(id: Long, ringing: Boolean) = mutateRuntime(id) { it.put("ringing", ringing) }

    /** The single alarm or timer currently ringing, if any (newest-wins policy allows one). */
    @Synchronized
    override fun ringingId(): Long? {
        val runtime = decodeObject(prefs.getString(KEY_RUNTIME, null))
        for (key in runtime.keys()) {
            if (runtime.getJSONObject(key).optBoolean("ringing", false)) return key.toLongOrNull()
        }
        return null
    }

    @Synchronized
    override fun clearRuntime(id: Long) = mutateRuntime(id) { it.remove("scheduledFire"); it.remove("snoozedUntil"); it.remove("ringing") }

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
        // Absent (pre-sound alarms) or blank both mean "system default sound".
        soundUri = o.optString("soundUri", "").takeIf { it.isNotBlank() },
    )

    private fun alarmToJson(a: Alarm) = JSONObject()
        .put("id", a.id)
        .put("hour", a.hour)
        .put("minute", a.minute)
        .put("daysMask", a.daysMask)
        .put("label", a.label)
        .put("enabled", a.enabled)
        .put("vibrate", a.vibrate)
        // putOpt: a null soundUri simply omits the key, keeping the on-disk
        // shape identical to pre-sound versions until a tone is chosen.
        .putOpt("soundUri", a.soundUri)

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
        private const val KEY_NEXT_ID = "next_id"

        /** Everything an older install may have kept in credential-encrypted prefs. */
        private val MIGRATED_KEYS = arrayOf(KEY_ALARMS, KEY_TIMERS, KEY_RUNTIME)

        /**
         * Where [nextId] draws from. The default below is a process-local
         * fallback for JVM tests and pre-init mints; building the singleton
         * store swaps in the persisted device-protected counter. Tests replace
         * this field rather than touching Android.
         */
        @Volatile
        internal var idSequence: MonotonicIdSequence = MonotonicIdSequence(
            load = { 0L },
            store = {},
            floorOf = { System.currentTimeMillis() },
        )

        /** Next id from the shared alarm/timer namespace (P2.11). */
        fun nextId(): Long = idSequence.next()

        @Volatile
        private var instance: ClockStore? = null

        /** Set once the credential-encrypted pull-forward has been done or ruled out. */
        @Volatile
        private var legacyMigrationChecked = false

        /**
         * The Application object ([ClockApp]), whose default [getSharedPreferences]
         * is credential-encrypted. Receiver paths pass a device-protected context
         * whose applicationContext is also DE; CE has to come from here.
         * `createCredentialProtectedStorageContext()` is @hide, so we stash this
         * from Application.onCreate instead of calling it.
         */
        @Volatile
        private var credentialApp: Context? = null

        fun attachCredentialApp(app: Context) {
            credentialApp = app.applicationContext
        }

        fun get(context: Context): ClockStore {
            val store =
                instance ?: synchronized(this) {
                    instance ?: ClockStore(context).also { instance = it }
                }
            migrateLegacyCredentialStorageIfNeeded(context)
            return store
        }

        /**
         * One-time, idempotent pull-forward of alarm data written to
         * credential-encrypted storage by installs predating device-protected
         * persistence. Runs only after first unlock. Writes go through the
         * singleton's prefs object so a store created during locked boot sees
         * the copied rows without a second SharedPreferences cache.
         *
         * If the process started at LOCKED_BOOT_COMPLETED, Application.onCreate
         * will not run again at unlock — ACTION_USER_UNLOCKED is what lands
         * here after the user authenticates.
         */
        private fun migrateLegacyCredentialStorageIfNeeded(context: Context) {
            if (legacyMigrationChecked) return
            val app = context.applicationContext
            if (app.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
            synchronized(this) {
                if (legacyMigrationChecked) return
                val dePrefs = instance?.prefs
                    ?: app.createDeviceProtectedStorageContext()
                        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                // Anything already in DE wins; migration never overwrites it.
                if (!dePrefs.getString(KEY_ALARMS, null).isNullOrBlank()) {
                    legacyMigrationChecked = true
                    return
                }
                val ceContext = credentialApp
                    ?: app.takeUnless { it.isDeviceProtectedStorage }
                    ?: return
                val legacy = ceContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                val editor = dePrefs.edit()
                for (key in MIGRATED_KEYS) {
                    legacy.getString(key, null)?.let { editor.putString(key, it) }
                }
                editor.apply()
                legacyMigrationChecked = true
            }
        }
    }
}
