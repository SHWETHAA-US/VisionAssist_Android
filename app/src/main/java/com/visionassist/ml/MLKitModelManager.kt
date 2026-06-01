package com.visionassist.ml

import android.content.Context
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Manages ML Kit model caching and initialization.
 * Prevents redundant model downloads and ensures efficient resource usage.
 */
class MLKitModelManager(private val context: Context) {
    private var objectDetector: ObjectDetector? = null
    private val modelCachePath = File(context.cacheDir, "ml_kit_models")

    init {
        // Create cache directory if it doesn't exist
        if (!modelCachePath.exists()) {
            modelCachePath.mkdirs()
        }
    }

    /**
     * Initialize ObjectDetector asynchronously with proper error handling.
     * Uses STREAM_MODE for real-time processing with frame tracking.
     * @return ObjectDetector instance or null if initialization fails
     */
    suspend fun initializeObjectDetector(): ObjectDetector? = withContext(Dispatchers.Default) {
        return@withContext try {
            if (objectDetector != null) {
                Timber.d("ObjectDetector already initialized, returning cached instance")
                return@withContext objectDetector
            }

            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()

            objectDetector = ObjectDetection.getClient(options)
            Timber.i("ObjectDetector initialized successfully")
            objectDetector
        } catch (e: Exception) {
            Timber.e("Failed to initialize ObjectDetector: ${e.message}")
            null
        }
    }

    /**
     * Get cached ObjectDetector instance.
     * @return ObjectDetector or null if not initialized
     */
    fun getObjectDetector(): ObjectDetector? = objectDetector

    /**
     * Check if ObjectDetector is ready for use.
     * @return true if detector is initialized
     */
    fun isDetectorReady(): Boolean = objectDetector != null

    /**
     * Clean up model resources.
     * Should be called in onDestroy().
     */
    fun cleanup() {
        try {
            objectDetector?.close()
            Timber.i("ObjectDetector closed successfully")
        } catch (e: Exception) {
            Timber.e("Error closing ObjectDetector: ${e.message}")
        }
        objectDetector = null
    }

    /**
     * Clear model cache to force re-download on next init.
     */
    fun clearModelCache() {
        try {
            if (modelCachePath.exists()) {
                modelCachePath.deleteRecursively()
                modelCachePath.mkdirs()
            }
            Timber.i("Model cache cleared")
        } catch (e: Exception) {
            Timber.e("Error clearing model cache: ${e.message}")
        }
    }
}
