package com.visionassist.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Manages Bluetooth haptic feedback for navigation.
 * Provides alternative feedback channel to supplement audio guidance.
 */
class BluetoothHapticManager(private val context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    companion object {
        // Haptic patterns
        private const val PATTERN_SHORT = 50L
        private const val PATTERN_MEDIUM = 100L
        private const val PATTERN_LONG = 200L
        private const val PATTERN_GAP = 75L
    }

    /**
     * Check if device has haptic feedback capability.
     */
    fun hasHapticFeedback(): Boolean = vibrator?.hasVibrator() ?: false

    /**
     * Check if Bluetooth is available and enabled.
     */
    fun isBluetoothAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED && (bluetoothAdapter?.isEnabled ?: false)
        } else {
            bluetoothAdapter?.isEnabled ?: false
        }
    }

    /**
     * Haptic feedback for object detection.
     * Pattern: short vibration indicating new object detected.
     */
    fun onObjectDetected() {
        val pattern = longArrayOf(0, PATTERN_SHORT, PATTERN_GAP, PATTERN_SHORT)
        vibrate(pattern)
        Timber.d("Haptic: Object detected")
    }

    /**
     * Haptic feedback for close distance warning.
     * Pattern: rapid double-tap.
     */
    fun onCloseDistanceWarning() {
        val pattern = longArrayOf(0, PATTERN_MEDIUM, PATTERN_GAP, PATTERN_MEDIUM)
        vibrate(pattern)
        Timber.d("Haptic: Close distance warning")
    }

    /**
     * Haptic feedback for critical distance warning.
     * Pattern: continuous urgent vibration.
     */
    fun onCriticalDistance() {
        val pattern = longArrayOf(
            0,
            PATTERN_SHORT, PATTERN_GAP,
            PATTERN_SHORT, PATTERN_GAP,
            PATTERN_SHORT, PATTERN_GAP,
            PATTERN_LONG
        )
        vibrate(pattern)
        Timber.d("Haptic: Critical distance warning")
    }

    /**
     * Haptic feedback for path clear.
     * Pattern: calm single vibration.
     */
    fun onPathClear() {
        val pattern = longArrayOf(0, PATTERN_LONG)
        vibrate(pattern)
        Timber.d("Haptic: Path clear")
    }

    /**
     * Haptic feedback for left direction guidance.
     * Pattern: left-side pulsing (simulated).
     */
    fun onLeftGuidance() {
        val pattern = longArrayOf(
            0,
            PATTERN_MEDIUM, PATTERN_GAP,
            PATTERN_SHORT
        )
        vibrate(pattern)
        Timber.d("Haptic: Left guidance")
    }

    /**
     * Haptic feedback for right direction guidance.
     * Pattern: right-side pulsing (simulated).
     */
    fun onRightGuidance() {
        val pattern = longArrayOf(
            0,
            PATTERN_SHORT, PATTERN_GAP,
            PATTERN_MEDIUM
        )
        vibrate(pattern)
        Timber.d("Haptic: Right guidance")
    }

    /**
     * Haptic feedback for success (e.g., login successful).
     * Pattern: ascending vibration.
     */
    fun onSuccess() {
        val pattern = longArrayOf(
            0,
            PATTERN_SHORT, PATTERN_GAP,
            PATTERN_MEDIUM, PATTERN_GAP,
            PATTERN_LONG
        )
        vibrate(pattern)
        Timber.d("Haptic: Success")
    }

    /**
     * Haptic feedback for error.
     * Pattern: descending vibration.
     */
    fun onError() {
        val pattern = longArrayOf(
            0,
            PATTERN_LONG, PATTERN_GAP,
            PATTERN_MEDIUM, PATTERN_GAP,
            PATTERN_SHORT
        )
        vibrate(pattern)
        Timber.d("Haptic: Error")
    }

    /**
     * Internal method to vibrate with pattern.
     */
    private fun vibrate(pattern: LongArray) {
        try {
            if (!hasHapticFeedback()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Timber.e("Haptic vibration error: ${e.message}")
        }
    }

    /**
     * Cancel all ongoing vibrations.
     */
    fun cancelVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Timber.e("Error canceling vibration: ${e.message}")
        }
    }
}
