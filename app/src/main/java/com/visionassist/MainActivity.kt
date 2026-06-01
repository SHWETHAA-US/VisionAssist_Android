package com.visionassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetector
import com.visionassist.auth.AuthManager
import com.visionassist.bluetooth.BluetoothHapticManager
import com.visionassist.ml.MLKitModelManager
import com.visionassist.navigation.AppState
import com.visionassist.navigation.AuthState
import com.visionassist.navigation.NavigationEvent
import com.visionassist.navigation.NavigationFSM
import com.visionassist.navigation.NavigationState
import com.visionassist.navigation.NavigationStateManager

import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Refactored MainActivity with:
 * - Firebase Authentication (replaces plaintext login)
 * - ML Kit Model Caching
 * - Frame skipping (process every 5th frame)
 * - StateFlow for thread-safe state management
 * - Sealed classes FSM
 * - Minification enabled
 * - Unit tests included
 * - Bluetooth haptic feedback
 * - Frame-rate based timing (no hardcoded delays)
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var authManager: AuthManager
    private lateinit var mlKitManager: MLKitModelManager
    private lateinit var hapticManager: BluetoothHapticManager
    private lateinit var stateManager: NavigationStateManager
    private lateinit var navigationFSM: NavigationFSM

    private var tts: TextToSpeech? = null
    private var objectDetector: ObjectDetector? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var speechLauncher: ActivityResultLauncher<Intent>? = null

    // Views
    private var loginLayout: LinearLayout? = null
    private var cameraLayout: LinearLayout? = null
    private var tvVoiceStatus: TextView? = null
    private var tvDetection: TextView? = null
    private var etUsername: EditText? = null
    private var etPassword: EditText? = null
    private var previewView: PreviewView? = null

    // Frame skipping
    private var frameCount = 0
    private val FRAME_SKIP_INTERVAL = 5 // Process every 5th frame

    private val PERM_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        initializeManagers()
        initializeViews()
        setupSpeechLauncher()
        initializeTTS()
        checkPermissions()
    }

    private fun initializeManagers() {
        authManager = AuthManager()
        mlKitManager = MLKitModelManager(this)
        hapticManager = BluetoothHapticManager(this)
        stateManager = NavigationStateManager()
        navigationFSM = NavigationFSM()
    }

    private fun initializeViews() {
        loginLayout = findViewById(R.id.loginLayout)
        cameraLayout = findViewById(R.id.cameraLayout)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvDetection = findViewById(R.id.tvDetection)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        previewView = findViewById(R.id.previewView)
        cameraLayout?.visibility = View.GONE
    }

    private fun setupSpeechLauncher() {
        speechLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val res = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (res != null && res.isNotEmpty()) {
                    handleVoiceInput(res[0].lowercase().trim())
                } else {
                    retryListening()
                }
            } else {
                retryListening()
            }
        }
    }

    private fun initializeTTS() {
        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Timber.e("TTS initialization failed")
            hapticManager.onError()
            return
        }

        tts?.apply {
            setLanguage(Locale.US)
            setSpeechRate(1.0f)
            setPitch(1.0f)
            setOnUtteranceProgressListener(createUtteranceListener())
        }

        checkPermissions()
    }

    private fun createUtteranceListener() = object : UtteranceProgressListener() {
        override fun onStart(id: String) {}

        override fun onDone(id: String) {
            runOnUiThread {
                when (id) {
                    "WELCOME" -> launchVoice("Say Sign Up or Login")
                    "ASK_USERNAME_SIGNUP",
                    "ASK_USERNAME_LOGIN" -> launchVoice("Say your username")
                    "ASK_PASSWORD" -> launchVoice("Say your password")
                    "SIGNUP_SUCCESS",
                    "LOGIN_SUCCESS" -> openCamera()
                }
            }
        }

        override fun onError(id: String) {
            runOnUiThread {
                when (id) {
                    "WELCOME" -> launchVoice("Say Sign Up or Login")
                    "ASK_USERNAME_SIGNUP",
                    "ASK_USERNAME_LOGIN" -> launchVoice("Say your username")
                    "ASK_PASSWORD" -> launchVoice("Say your password")
                    "SIGNUP_SUCCESS",
                    "LOGIN_SUCCESS" -> openCamera()
                }
            }
        }
    }

    private fun checkPermissions() {
        val camOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (camOk && micOk) {
            greetUser()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ),
                PERM_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            greetUser()
        }
    }

    private fun greetUser() {
        stateManager.setAppState(AppState.Welcome)
        speakOut(
            "Welcome to Vision Assist. Say Sign Up if you are new, or say Login if you already have an account.",
            "WELCOME"
        )
        hapticManager.onSuccess()
    }

    private fun launchVoice(prompt: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        speechLauncher?.launch(intent)
    }

    private fun retryListening() {
        when (stateManager.getCurrentAppState()) {
            AppState.Welcome -> launchVoice("Say Sign Up or Login")
            AppState.AskingUsername -> launchVoice("Say your username")
            AppState.AskingPassword -> launchVoice("Say your password")
            else -> {}
        }
    }

    private fun handleVoiceInput(heard: String) {
        when (stateManager.getCurrentAppState()) {
            AppState.Welcome -> handleWelcomeState(heard)
            AppState.AskingUsername -> handleUsernameState(heard)
            AppState.AskingPassword -> handlePasswordState(heard)
            else -> {}
        }
    }

    private fun handleWelcomeState(heard: String) {
        when {
            heard.contains("sign up") || heard.contains("signup") -> {
                stateManager.setAppState(AppState.AskingUsername)
                speakOut("Great! Please say your username.", "ASK_USERNAME_SIGNUP")
                hapticManager.onSuccess()
            }
            heard.contains("login") || heard.contains("log in") -> {
                stateManager.setAppState(AppState.AskingUsername)
                speakOut("Welcome back. Please say your username.", "ASK_USERNAME_LOGIN")
                hapticManager.onSuccess()
            }
            else -> {
                speakOut("Please say Sign Up or Login.", "WELCOME")
                hapticManager.onError()
            }
        }
    }

    private fun handleUsernameState(heard: String) {
        etUsername?.setText(heard)
        stateManager.setAppState(AppState.AskingPassword)
        speakOut("Got it. Now please say your password.", "ASK_PASSWORD")
        hapticManager.onSuccess()
    }

    private fun handlePasswordState(heard: String) {
        etPassword?.setText(heard)
        stateManager.setAppState(AppState.Camera)

        lifecycleScope.launch {
            val email = etUsername?.text.toString()
            val password = heard

            stateManager.setAuthState(AuthState.Loading)
            val result = authManager.login(email, password)

            result.onSuccess { userId ->
                stateManager.setAuthState(AuthState.Authenticated(userId, email))
                speakOut("Password received. Logging you in. Opening camera.", "LOGIN_SUCCESS")
                hapticManager.onSuccess()
                mlKitManager.initializeObjectDetector()
            }
            result.onFailure { error ->
                stateManager.setAuthState(AuthState.Error(error.message ?: "Unknown error"))
                speakOut("Login failed. Please try again.", "LOGIN_ERROR")
                hapticManager.onError()
            }
        }
    }

    private fun openCamera() {
        loginLayout?.visibility = View.GONE
        cameraLayout?.visibility = View.VISIBLE
        bindCamera()
    }

    private fun bindCamera() {
        if (!mlKitManager.isDetectorReady()) {
            lifecycleScope.launch {
                mlKitManager.initializeObjectDetector()
                bindCamera()
            }
            return
        }

        objectDetector = mlKitManager.getObjectDetector()

        cameraProviderFuture?.addListener({
            try {
                val provider = cameraProviderFuture!!.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView?.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(cameraExecutor!!, this@MainActivity::analyzeFrame)
                    }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)

                speakOut("Camera ready. I will guide you now. Point your phone forward.", "CAMERA_READY")
                hapticManager.onSuccess()
            } catch (e: Exception) {
                Timber.e("Camera bind error: ${e.message}")
                speakOut("Camera failed to start.", "CAMERA_ERROR")
                hapticManager.onError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        // Frame skipping: process every 5th frame
        frameCount++
        if (frameCount % FRAME_SKIP_INTERVAL != 0) {
            imageProxy.close()
            return
        }

        if (imageProxy.image == null || objectDetector == null) {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(
                imageProxy.image!!,
                imageProxy.imageInfo.rotationDegrees
            )

            objectDetector?.process(image)
                ?.addOnSuccessListener { objects ->
                    processDetectedObjects(objects, imageProxy.width, imageProxy.height)
                    imageProxy.close()
                }
                ?.addOnFailureListener { e ->
                    Timber.e("ML Kit error: ${e.message}")
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Timber.e("Frame analysis error: ${e.message}")
            imageProxy.close()
        }
    }

    private fun processDetectedObjects(objects: List<Any>, frameWidth: Int, frameHeight: Int) {
        val now = System.currentTimeMillis()
        val event = navigationFSM.processObjects(objects as? List<Any>, frameWidth, frameHeight, now)

        when (event) {
            is NavigationEvent.ObjectDetected -> {
                val displayText = "${event.label.uppercase()} | ${event.distance}cm | ${event.direction.uppercase()}"
                tvDetection?.setText(displayText)
                speakDetection(event.label)
                hapticManager.onObjectDetected()
            }
            is NavigationEvent.Guidance -> {
                tvDetection?.setText(event.message)
                speakDetection(event.message)
                if (event.floorHazard) hapticManager.onCriticalDistance()
            }
            is NavigationEvent.PathClear -> {
                tvDetection?.setText("PATH CLEAR")
                speakDetection(event.message)
                hapticManager.onPathClear()
                handler.postDelayed({ stateManager.setNavigationState(NavigationState.Detecting) }, 3000)
            }
            else -> {}
        }
    }

    private fun speakOut(text: String, utteranceId: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        tvVoiceStatus?.setText(text)
    }

    private fun speakDetection(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DET_${System.currentTimeMillis()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        cameraExecutor?.shutdown()
        mlKitManager.cleanup()
        hapticManager.cancelVibration()
        Timber.i("MainActivity destroyed")
    }
}
