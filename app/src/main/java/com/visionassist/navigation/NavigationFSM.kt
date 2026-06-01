package com.visionassist.navigation

import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject
import timber.log.Timber

/**
 * Finite State Machine for navigation logic.
 * Handles state transitions with sealed classes for type safety.
 */
class NavigationFSM {
    private var currentState: NavigationState = NavigationState.Detecting
    private var lockedTrackingId: Int? = null
    private var lockedArea: Int = 0
    private var lastNavSpokenAt: Long = 0

    companion object {
        private const val NAV_COOLDOWN_MS = 2500L
        private const val CROSSING_SHRINK_RATIO = 0.40f
        private const val VELOCITY_CRITICAL = 400f
        private const val VELOCITY_HIGH = 150f
        private const val FLOOR_HAZARD_RATIO = 0.82f
    }

    /**
     * Process detected objects and manage state transitions.
     * @param objects List of detected objects
     * @param frameWidth Frame width
     * @param frameHeight Frame height
     * @param currentTime Current system time
     * @return NavigationEvent to be handled by UI
     */
    fun processObjects(
        objects: List<DetectedObject>?,
        frameWidth: Int,
        frameHeight: Int,
        currentTime: Long
    ): NavigationEvent {
        return when (currentState) {
            NavigationState.Detecting -> handleDetecting(objects, frameWidth, frameHeight, currentTime)
            NavigationState.Locked,
            NavigationState.Guiding,
            NavigationState.WaitingForClear -> handleTracking(objects, frameWidth, frameHeight, currentTime)
            NavigationState.Clear -> NavigationEvent.None
        }
    }

    private fun handleDetecting(
        objects: List<DetectedObject>?,
        frameWidth: Int,
        frameHeight: Int,
        currentTime: Long
    ): NavigationEvent {
        if (objects == null || objects.isEmpty()) {
            if (currentTime - lastNavSpokenAt > 4500) {
                lastNavSpokenAt = currentTime
                return NavigationEvent.PathClear("Path is clear. Move forward safely.")
            }
            return NavigationEvent.None
        }

        val priority = selectPriorityObject(objects, frameWidth) ?: return NavigationEvent.None
        val box = priority.boundingBox ?: return NavigationEvent.None

        lockedTrackingId = priority.trackingId
        lockedArea = box.width() * box.height()

        val label = getLabel(priority)
        val distance = estimateDistance(box, frameWidth, frameHeight)
        val direction = getDirection(box.centerX().toFloat() / frameWidth)
        val floorHazard = isFloorLevelHazard(box, frameHeight)

        currentState = NavigationState.Locked
        lastNavSpokenAt = currentTime

        Timber.d("Object locked: $label at ${distance}cm, $direction")

        return NavigationEvent.ObjectDetected(
            label = label,
            distance = distance,
            direction = direction,
            floorHazard = floorHazard
        )
    }

    private fun handleTracking(
        objects: List<DetectedObject>?,
        frameWidth: Int,
        frameHeight: Int,
        currentTime: Long
    ): NavigationEvent {
        if (objects == null || objects.isEmpty()) {
            return handleCrossing(currentTime)
        }

        val locked = findLockedObject(objects) ?: return handleCrossing(currentTime)
        val box = locked.boundingBox ?: return NavigationEvent.None
        val currentArea = box.width() * box.height()

        if (lockedArea > 0 && currentArea < lockedArea * CROSSING_SHRINK_RATIO) {
            return handleCrossing(currentTime)
        }

        currentState = NavigationState.WaitingForClear

        if (currentTime - lastNavSpokenAt >= NAV_COOLDOWN_MS) {
            val direction = getDirection(box.centerX().toFloat() / frameWidth)
            val floorHazard = isFloorLevelHazard(box, frameHeight)
            lastNavSpokenAt = currentTime

            return NavigationEvent.Guidance(
                message = "$direction guidance",
                floorHazard = floorHazard
            )
        }

        return NavigationEvent.None
    }

    private fun handleCrossing(currentTime: Long): NavigationEvent {
        currentState = NavigationState.Clear
        lastNavSpokenAt = currentTime
        lockedTrackingId = null
        lockedArea = 0

        Timber.d("Object crossed, path clear")
        return NavigationEvent.PathClear("Path clear.")
    }

    private fun selectPriorityObject(
        objects: List<DetectedObject>,
        frameWidth: Int
    ): DetectedObject? {
        var best: DetectedObject? = null
        var bestScore: Long = -1

        for (obj in objects) {
            val box = obj.boundingBox ?: continue
            val area = (box.width().toLong() * box.height())
            val centerX = box.centerX().toFloat() / frameWidth
            val inCenter = centerX >= 0.25f && centerX <= 0.75f
            val score = if (inCenter) area * 2 else area

            if (score > bestScore) {
                bestScore = score
                best = obj
            }
        }
        return best
    }

    private fun findLockedObject(objects: List<DetectedObject>): DetectedObject? {
        if (lockedTrackingId != null) {
            return objects.find { it.trackingId == lockedTrackingId }
        }
        return objects.maxByOrNull { (it.boundingBox?.width() ?: 0) * (it.boundingBox?.height() ?: 0) }
    }

    private fun getLabel(obj: DetectedObject): String {
        return obj.labels.firstOrNull()?.text?.lowercase() ?: "object"
    }

    private fun estimateDistance(box: Rect, frameWidth: Int, frameHeight: Int): Int {
        val boxWidthRatio = box.width().toFloat() / frameWidth
        val boxHeightRatio = box.height().toFloat() / frameHeight

        if (boxWidthRatio <= 0 && boxHeightRatio <= 0) return 300

        val normW = boxWidthRatio
        val normH = boxHeightRatio * (frameHeight.toFloat() / frameWidth)
        val diagonal = kotlin.math.sqrt((normW * normW + normH * normH).toDouble()).toFloat()

        if (diagonal <= 0) return 300
        val cm = (20 / diagonal).toInt()
        return cm.coerceIn(10, 300)
    }

    private fun isFloorLevelHazard(box: Rect, frameHeight: Int): Boolean {
        val bottomRatio = box.bottom.toFloat() / frameHeight
        val topRatio = box.top.toFloat() / frameHeight
        return bottomRatio >= FLOOR_HAZARD_RATIO && (bottomRatio - topRatio) < 0.35f
    }

    private fun getDirection(centerX: Float): String = when {
        centerX < 0.15f -> "far left"
        centerX < 0.40f -> "left"
        centerX > 0.85f -> "far right"
        centerX > 0.60f -> "right"
        else -> "center"
    }
}

/**
 * Sealed classes for navigation events to handle UI updates.
 */
sealed class NavigationEvent {
    data object None : NavigationEvent()
    data class ObjectDetected(
        val label: String,
        val distance: Int,
        val direction: String,
        val floorHazard: Boolean
    ) : NavigationEvent()

    data class Guidance(
        val message: String,
        val floorHazard: Boolean
    ) : NavigationEvent()

    data class PathClear(val message: String) : NavigationEvent()
}
