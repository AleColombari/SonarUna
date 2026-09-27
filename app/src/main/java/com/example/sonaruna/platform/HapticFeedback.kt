package com.example.sonaruna.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** Touch feedback respects the user's haptic setting and never repeats indefinitely. */
class HapticFeedback(context: Context) {
    private val context = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        this.context.getSystemService(Vibrator::class.java)
    }

    fun start() = vibrate(longArrayOf(0L, 45L))
    fun success() = vibrate(longArrayOf(0L, 40L, 90L, 40L))
    fun error() = vibrate(longArrayOf(0L, 180L))

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        if (Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) == 0
        ) return

        val effect = VibrationEffect.createWaveform(pattern, -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(
                    effect,
                    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build(),
                )
            } else {
                device.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }
    }
}
