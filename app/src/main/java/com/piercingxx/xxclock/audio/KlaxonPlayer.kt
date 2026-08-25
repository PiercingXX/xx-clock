package com.piercingxx.xxclock.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
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

    /** @return true when audio playback actually started (vibration may still run on false). */
    fun start(vibrate: Boolean, gradualVolume: Boolean): Boolean {
        requestAudioFocus()
        val audioStarted = startPlayback(gradualVolume)
        if (vibrate) startVibration()
        return audioStarted
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try {
            player?.stop()
        } catch (_: IllegalStateException) {
            // already stopped / never started
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
     * Falls back to the notification default, then gives up audio gracefully
     * (vibration continues, service stays foreground).
     */
    private fun startPlayback(gradualVolume: Boolean): Boolean {
        return try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return false
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                if (gradualVolume) {
                    rampStep = 0L
                    val startVolume = initialRampVolume()
                    setVolume(startVolume, startVolume)
                    handler.postDelayed(::rampTick, RAMP_INTERVAL_MS)
                } else {
                    setVolume(1f, 1f)
                }
                start()
            }
            true
        } catch (t: Exception) {
            // IOException / IllegalStateException / SecurityException from media stack.
            releasePlayer()
            false
        }
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
    }
}
