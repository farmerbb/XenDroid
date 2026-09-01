package xendroid.compose.gamepad

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice

/**
 * Plays the motor speeds the guest asked for on the pad that owns them.
 *
 * The guest sets a level and leaves it, so effects are issued as a repeating
 * waveform and cancelled on zero rather than re-sent per poll. Main thread only.
 */
class RumbleDriver(context: Context) {

    private val appContext = context.applicationContext
    private val systemVibrator: Vibrator? = resolveSystemVibrator()
    private val lastAmplitude = HashMap<Int, Int>()
    private val lastIssuedAt = HashMap<Int, Long>()

    /**
     * @param state left/right pairs indexed by device slot, as returned by native
     * @param vibratorFor device slot -> vibrator, null for slots with no motor
     */
    fun apply(state: IntArray, vibratorFor: (Int) -> Vibrator?) {
        val now = SystemClock.uptimeMillis()
        for (slot in 0 until state.size / 2) {
            val target = amplitudeFor(maxOf(state[slot * 2], state[slot * 2 + 1]))
            val current = lastAmplitude[slot] ?: 0
            if (target == current) continue
            // Issuing an effect replaces the one playing, stopping the motor and
            // spinning it up again from rest. Start and stop must be immediate, but
            // a level change between them is held back: at poll rate the restarts
            // alone keep the motor from ever reaching full strength.
            if (target != 0 && current != 0 &&
                now - (lastIssuedAt[slot] ?: 0L) < REISSUE_MIN_MS) continue
            val vibrator = vibratorFor(slot)
            Log.i(TAG, "slot=$slot guest=${state[slot * 2]}/${state[slot * 2 + 1]} " +
                "amplitude=$target vibrator=${vibrator != null}")
            lastAmplitude[slot] = target
            lastIssuedAt[slot] = now
            if (vibrator == null) continue
            runCatching {
                if (target == 0) vibrator.cancel() else play(vibrator, target)
            }
        }
    }

    /** Quantized so small fluctuations do not restart the motor, and floored
     *  because below roughly a fifth of full scale an LRA barely moves. */
    private fun amplitudeFor(strongest: Int): Int {
        if (strongest <= 0) return 0
        val step = ((strongest.toLong() * STEPS + 32767) / 65535).toInt().coerceIn(1, STEPS)
        return MIN_AMPLITUDE + (255 - MIN_AMPLITUDE) * (step - 1) / (STEPS - 1)
    }

    // Tagged as game usage so the system touch-feedback setting does not filter it.
    private fun play(vibrator: Vibrator, amplitude: Int) {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, PULSE_MS),
            intArrayOf(0, amplitude),
            1,  // repeat the on-phase until cancelled
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, gameAudioAttributes)
        }
    }

    private val gameAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun stopAll(vibratorFor: (Int) -> Vibrator?) {
        lastAmplitude.keys.toList().forEach { slot ->
            runCatching { vibratorFor(slot)?.cancel() }
        }
        lastAmplitude.clear()
        lastIssuedAt.clear()
    }

    /** Falls back to the console's own motor for the on-screen overlay, and when
     *  [fallbackToSystem] for a motorless pad: a handheld's built-in controls
     *  rumble through the system vibrator. */
    fun vibratorForDevice(deviceId: Int?, fallbackToSystem: Boolean = false): Vibrator? {
        if (deviceId == null) return systemVibrator
        val device = InputDevice.getDevice(deviceId) ?: return null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            device.vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
            ?: systemVibrator.takeIf { fallbackToSystem }
    }

    private fun resolveSystemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }

    private companion object {
        const val PULSE_MS = 60_000L
        const val REISSUE_MIN_MS = 100L
        const val STEPS = 8
        const val MIN_AMPLITUDE = 48
        const val TAG = "XenDroidRumble"
    }
}
