package io.github.ssayala.ledticker.data

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin wrapper over the system Vibrator for confirmation feedback, the
 * Android counterpart of the iOS Haptics enum. Patterns are short and
 * distinct so success/warning/error read differently by feel.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = run {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    }

    private fun play(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amplitude)) }
    }

    /** Light tap for non-consequential confirmation (chip tap, mode switch). */
    fun tap() = oneShot(12, 90)

    /** Crisp double tick for a successful, ACKed action. */
    fun success() = play(longArrayOf(0, 18, 60, 18), intArrayOf(0, 160, 0, 200))

    /** Single firm pulse for a cautionary action (clear, cancel). */
    fun warning() = oneShot(28, 200)

    /** Longer rumble for a failure. */
    fun error() = play(longArrayOf(0, 40, 50, 40, 50, 40), intArrayOf(0, 230, 0, 230, 0, 230))
}
