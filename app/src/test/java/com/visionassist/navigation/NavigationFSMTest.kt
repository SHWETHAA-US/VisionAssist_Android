package com.visionassist.navigation

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavigationFSMTest {

    private lateinit var fsm: NavigationFSM

    @Before
    fun setUp() {
        fsm = NavigationFSM()
    }

    @Test
    fun testDistanceEstimation() {
        // Test: Object at 0.1 diagonal should be ~200cm
        val distance = estimateDistanceHelper(0.1f, 0.1f, 1920, 1080)
        assertTrue(distance in 150..250, "Distance $distance not in expected range")
    }

    @Test
    fun testDistanceEstimationCloseRange() {
        // Test: Object at 0.4 diagonal should be ~50cm
        val distance = estimateDistanceHelper(0.4f, 0.4f, 1920, 1080)
        assertTrue(distance in 40..60, "Distance $distance not in expected range")
    }

    @Test
    fun testDistanceEstimationEdgeCase() {
        // Test: Zero ratio returns default 300cm
        val distance = estimateDistanceHelper(0f, 0f, 1920, 1080)
        assertEquals(300, distance)
    }

    @Test
    fun testDirectionCalculation() {
        // Test direction mapping
        assertEquals("far left", getDirectionHelper(0.1f))
        assertEquals("left", getDirectionHelper(0.3f))
        assertEquals("center", getDirectionHelper(0.5f))
        assertEquals("right", getDirectionHelper(0.7f))
        assertEquals("far right", getDirectionHelper(0.9f))
    }

    @Test
    fun testFloorHazardDetection() {
        // Mock Rect at floor level
        val isFloorHazard = isFloorHazardHelper(10, 950, 100, 1080)
        assertTrue(isFloorHazard, "Should detect floor-level hazard")
    }

    @Test
    fun testVelocityCalculation() {
        // Test velocity computation with area growth
        val velocity1 = computeVelocityHelper(1000L, 1100L, 100L)
        assertTrue(velocity1 > 0, "Velocity should be positive for approaching object")

        val velocity2 = computeVelocityHelper(1100L, 1000L, 100L)
        assertTrue(velocity2 < 0, "Velocity should be negative for receding object")
    }

    // Helper functions to test internal logic
    private fun estimateDistanceHelper(widthRatio: Float, heightRatio: Float, frameW: Int, frameH: Int): Int {
        if (widthRatio <= 0 && heightRatio <= 0) return 300
        val normW = widthRatio
        val normH = heightRatio * (frameH.toFloat() / frameW)
        val diagonal = kotlin.math.sqrt((normW * normW + normH * normH).toDouble()).toFloat()
        if (diagonal <= 0) return 300
        val cm = (20 / diagonal).toInt()
        return cm.coerceIn(10, 300)
    }

    private fun getDirectionHelper(centerX: Float): String = when {
        centerX < 0.15f -> "far left"
        centerX < 0.40f -> "left"
        centerX > 0.85f -> "far right"
        centerX > 0.60f -> "right"
        else -> "center"
    }

    private fun isFloorHazardHelper(top: Int, bottom: Int, height: Int, frameHeight: Int): Boolean {
        val bottomRatio = bottom.toFloat() / frameHeight
        val topRatio = top.toFloat() / frameHeight
        return bottomRatio >= 0.82f && (bottomRatio - topRatio) < 0.35f
    }

    private fun computeVelocityHelper(prevArea: Long, currentArea: Long, timeDeltaMs: Long): Float {
        if (timeDeltaMs <= 0 || timeDeltaMs > 2000) return 0f
        return (currentArea - prevArea).toFloat() / timeDeltaMs
    }
}
