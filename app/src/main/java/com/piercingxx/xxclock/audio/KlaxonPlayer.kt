package com.piercingxx.xxclock.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import com.piercingxx.xxclock.Prefs
import kotlin.math.min

/**
 * Alarm-stream klaxon: looping MediaPlayer on USAGE_ALARM (the stream DND allows
 * through by default), repeating vibration with alarm audio attributes, transient
 * audio focus, and an optional setVolume-based crescendo (never touches the
 * system alarm volume slider).
 */
class KlaxonPlayer(private val context: Context) {

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rampStep = 0L
    private var gradualVolume = false
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var attemptToken = 0
    private var preparedCurrent = false
    private var prepareTimeout: Runnable? = null

    /**
     * @param soundUri per-alarm ringtone URI string, or null for the system
     *   default alarm sound (see [ringCandidates] for the fallback order).
     * @return true when the audio pipeline was engaged; actual playback begins
     *   asynchronously once a candidate reaches the prepared state (vibration
     *   may run regardless).
     */
    fun start(vibrate: Boolean, gradualVolume: Boolean, soundUri: String? = null): Boolean {
        requestAudioFocus()
        val engaged = startPlayback(gradualVolume, soundUri)
        if (vibrate) startVibration()
        return engaged
    }

    fun stop() {
        attemptToken++
        handler.removeCallbacksAndMessages(null)
        prepareTimeout = null
        try {
            player?.stop()
        } catch (_: IllegalStateException) {
            // already stopped / never started / still preparing
        }
        releasePlayer()
        vibrator?.cancel()
        vibrator = null
        abandonAudioFocus()
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
            // best-effort; never let cleanup throw
        }
        player = null
    }

    /**
     * Never throws: a corrupt/missing alarm sound must not crash the ring path.
     * Tries the per-alarm tone first, then the system alarm default, then the
     * notification default (the [ringCandidates] order), so a chosen tone that
     * no longer resolves — deleted file, revoked permission — still rings with
     * the default rather than firing silently. Gives up audio gracefully after
     * that (vibration continues, service stays foreground).
     *
     * Candidates prepare asynchronously ([MediaPlayer.prepareAsync]) so a slow
     * or hung tone provider cannot stall the caller; each attempt is bounded
     * by [PREPARE_TIMEOUT_MS] before the walk moves to the next entry.
     */
    private fun startPlayback(gradualVolume: Boolean, soundUri: String?): Boolean {
        this.gradualVolume = gradualVolume
        candidates = ringCandidates(
            chosenUri = soundUri,
            defaultAlarmUri = runCatching {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)?.toString()
            }.getOrNull(),
            defaultNotificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString(),
        )
        if (candidates.isEmpty()) return false
        tryCandidate(0)
        return true
    }

    /** Arms one candidate for async preparation; any setup failure moves to the next. */
    private fun tryCandidate(index: Int) {
        if (index >= candidates.size) return
        candidateIndex = index
        val token = ++attemptToken
        preparedCurrent = false
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(context, Uri.parse(candidates[index]))
            mp.isLooping = true
            mp.setOnPreparedListener { p -> onPrepared(token, p) }
            mp.setOnErrorListener { p, _, _ ->
                onPlayerError(token, p)
                true
            }
        } catch (_: Exception) {
            // SecurityException (unreadable URI) / IOException / IllegalStateException
            // from data-source setup: same fall-through as the media stack failing later.
            releasePlayer()
            tryCandidate(index + 1)
            return
        }
        val timeout = Runnable { abandonPreparation(token, mp) }
        prepareTimeout = timeout
        handler.postDelayed(timeout, PREPARE_TIMEOUT_MS)
        try {
            mp.prepareAsync()
        } catch (_: Exception) {
            abandonPreparation(token, mp)
        }
    }

    /** Candidate never reached prepared within its window: release it, try the next. */
    private fun abandonPreparation(token: Int, mp: MediaPlayer) {
        if (!isCurrent(token, mp)) return
        cancelPrepareTimeout()
        releasePlayer()
        tryCandidate(candidateIndex + 1)
    }

    /**
     * Media-stack failures arrive here instead of as exceptions once preparation
     * is async. Before prepared this replaces the sync-prepare IOException
     * fall-through (advance to next candidate); after prepared the player is
     * already sounding, so just tear it down.
     */
    private fun onPlayerError(token: Int, mp: MediaPlayer) {
        if (!isCurrent(token, mp)) return
        val wasPrepared = preparedCurrent
        cancelPrepareTimeout()
        releasePlayer()
        if (!wasPrepared) tryCandidate(candidateIndex + 1)
    }

    private fun onPrepared(token: Int, mp: MediaPlayer) {
        if (!isCurrent(token, mp)) {
            // Stale dispatch (stopped/superseded between queueing and delivery).
            runCatching { mp.release() }
            return
        }
        cancelPrepareTimeout()
        preparedCurrent = true
        try {
            if (gradualVolume) {
                rampStep = 0L
                val startVolume = initialRampVolume()
                mp.setVolume(startVolume, startVolume)
                handler.postDelayed(::rampTick, RAMP_INTERVAL_MS)
            } else {
                mp.setVolume(1f, 1f)
            }
            mp.start()
        } catch (_: IllegalStateException) {
            // Player torn down underneath us despite the guard; vibration continues.
            releasePlayer()
        }
    }

    /** Identity+generation guard so no callback ever touches a released player. */
    private fun isCurrent(token: Int, mp: MediaPlayer): Boolean =
        token == attemptToken && player === mp

    private fun cancelPrepareTimeout() {
        prepareTimeout?.let { handler.removeCallbacks(it) }
        prepareTimeout = null
    }

    private fun initialRampVolume(): Float = 0.15f

    private fun rampTick() {
        val p = player ?: return
        rampStep++
        val elapsedMs = rampStep * RAMP_INTERVAL_MS
        val fraction = min(1f, elapsedMs.toFloat() / Prefs.VOLUME_RAMP_MS.toFloat())
        val volume = 0.15f + 0.85f * fraction
        p.setVolume(volume, volume)
        if (fraction < 1f) handler.postDelayed(::rampTick, RAMP_INTERVAL_MS)
    }

    private fun startVibration() {
        vibrator = obtainVibrator()
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 1000), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            // Non-deprecated path: vibration attributes carry the alarm usage on T+.
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, attrs)
        }
    }

    private fun obtainVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    companion object {
        private const val RAMP_INTERVAL_MS = 3_000L

        /** Per-candidate budget to reach the prepared state before falling through. */
        private const val PREPARE_TIMEOUT_MS = 2_000L
    }
}
