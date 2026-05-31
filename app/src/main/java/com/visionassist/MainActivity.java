package com.visionassist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements TextToSpeech.OnInitListener {

    private static final String TAG = "VisionAssist";
    private static final int    PERM_CODE = 101;

    // ── Micro-delay constants (ms) ───────────────────────────────────────────
    // These are the ONLY delay values used in the entire app.
    // All other transitions are event-driven via onDone().
    private static final int DELAY_AFTER_TTS_DONE   = 30;   // recognizer starts
    private static final int DELAY_SPEAK_RESPONSE   = 20;   // after user speaks
    private static final int DELAY_OPEN_CAMERA      = 30;   // after success TTS
    private static final int DELAY_TTS_WARMUP       = 100;  // one-time TTS bind

    // ── States ───────────────────────────────────────────────────────────────
    private static final int STATE_WELCOME  = 0;
    private static final int STATE_ASK_NAME = 1;
    private static final int STATE_ASK_PASS = 2;
    private static final int STATE_CAMERA   = 3;

    private int     appState      = STATE_WELCOME;
    private boolean isSignUp      = false;
    private String  savedUsername = "";

    // ── Views ────────────────────────────────────────────────────────────────
    private LinearLayout loginLayout, cameraLayout;
    private TextView     tvVoiceStatus, tvDetection;
    private EditText     etUsername, etPassword;
    private PreviewView  previewView;

    // ── TTS ──────────────────────────────────────────────────────────────────
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    // ── Main thread handler ───────────────────────────────────────────────────
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ── Camera executor — single background thread for CameraX + ML Kit ──────
    private ExecutorService cameraExecutor;

    // ── Camera provider future — initialized in parallel while TTS speaks ─────
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    // ── ML Kit ───────────────────────────────────────────────────────────────
    private ObjectDetector objectDetector;

    // ── Smart speech state ───────────────────────────────────────────────────
    private long   lastSpokenAt         = 0;
    private String lastSpokenMessage    = "";
    private String lastObjectLabel      = "";
    private String lastDistanceCategory = "";
    private String lastDirection        = "";

    // ── Navigation FSM states ────────────────────────────────────────────────
    private static final int NAV_DETECTING         = 0;
    private static final int NAV_LOCKED            = 1;
    private static final int NAV_GUIDING           = 2;
    private static final int NAV_WAITING_FOR_CLEAR = 3;
    private static final int NAV_CLEAR             = 4;

    private int     navState           = NAV_DETECTING;
    private Integer lockedTrackingId   = null;   // ML Kit tracking ID (nullable Integer)
    private int     lockedArea         = 0;      // bounding-box area at lock time
    private long    lastNavSpokenAt    = 0;

    private static final long  NAV_COOLDOWN_MS       = 2500L;
    private static final float CROSSING_SHRINK_RATIO = 0.40f; // 40 % → object passed
    // ── Velocity tracking ────────────────────────────────────────────────────
    private final java.util.HashMap<Integer, Long> areaHistory = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Long> timeHistory = new java.util.HashMap<>();
    private float currentApproachVelocity = 0f; // pixels² per ms

    // Velocity urgency thresholds
    private static final float VELOCITY_CRITICAL = 400f; // px²/ms → STOP NOW
    private static final float VELOCITY_HIGH     = 150f; // px²/ms → Move quickly
    private static final float FLOOR_HAZARD_RATIO = 0.82f; // bbox bottom > 82% of frame = floor level

    // ── Speech launcher ──────────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> speechLauncher;

    // ═════════════════════════════════════════════════════════════════════════
    //  onCreate — set up views and launchers only; heavy init is async
    // ═════════════════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        loginLayout   = findViewById(R.id.loginLayout);
        cameraLayout  = findViewById(R.id.cameraLayout);
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus);
        tvDetection   = findViewById(R.id.tvDetection);
        etUsername    = findViewById(R.id.etUsername);
        etPassword    = findViewById(R.id.etPassword);
        previewView   = findViewById(R.id.previewView);

        cameraLayout.setVisibility(View.GONE);

        // ── Register speech launcher (must be in onCreate) ───────────────────
        speechLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        ArrayList<String> res = result.getData()
                                .getStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS);
                        if (res != null && !res.isEmpty()) {
                            // Process voice result immediately — no delay
                            handleVoice(res.get(0).toLowerCase().trim());
                        } else {
                            retryListening();
                        }
                    } else {
                        retryListening();
                    }
                }
        );

        // ── Start TTS init (async — does not block UI) ───────────────────────
        tts = new TextToSpeech(this, this);

        // ── Pre-warm camera provider in background IMMEDIATELY ───────────────
        // This runs in parallel while TTS initializes and welcome is spoken.
        // By the time login/signup succeeds, camera is already ready to bind.
        cameraExecutor       = Executors.newSingleThreadExecutor();
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TTS init callback
    // ═════════════════════════════════════════════════════════════════════════
    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed");
            return;
        }

        tts.setLanguage(Locale.US);
        tts.setSpeechRate(1.0f);
        tts.setPitch(1.0f);

        // ── UtteranceProgressListener ────────────────────────────────────────
        // ALL transitions are event-driven here.
        // Only micro-delays (20–30 ms) are used — no large postDelayed values.
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {

            @Override public void onStart(String id) { }

            @Override
            public void onDone(String id) {
                // onDone fires on background thread → post to UI thread
                runOnUiThread(() -> {
                    switch (id) {
                        case "WELCOME":
                            // ~30 ms after welcome finishes → start listening
                            handler.postDelayed(
                                    () -> launchVoice("Say Sign Up or Login"),
                                    DELAY_AFTER_TTS_DONE);
                            break;

                        case "ASK_USERNAME_SIGNUP":
                        case "ASK_USERNAME_LOGIN":
                            handler.postDelayed(
                                    () -> launchVoice("Say your username"),
                                    DELAY_AFTER_TTS_DONE);
                            break;

                        case "ASK_PASSWORD":
                            handler.postDelayed(
                                    () -> launchVoice("Say your password"),
                                    DELAY_AFTER_TTS_DONE);
                            break;

                        case "SIGNUP_SUCCESS":
                        case "LOGIN_SUCCESS":
                            // ~30 ms after success message → open camera
                            handler.postDelayed(
                                    () -> openCamera(),
                                    DELAY_OPEN_CAMERA);
                            break;
                    }
                });
            }

            @Override
            public void onError(String id) {
                // Mirror onDone on error so flow never gets stuck
                runOnUiThread(() -> {
                    switch (id) {
                        case "WELCOME":
                            handler.postDelayed(
                                    () -> launchVoice("Say Sign Up or Login"),
                                    DELAY_AFTER_TTS_DONE);
                            break;
                        case "ASK_USERNAME_SIGNUP":
                        case "ASK_USERNAME_LOGIN":
                            handler.postDelayed(
                                    () -> launchVoice("Say your username"),
                                    DELAY_AFTER_TTS_DONE);
                            break;
                        case "ASK_PASSWORD":
                            handler.postDelayed(
                                    () -> launchVoice("Say your password"),
                                    DELAY_AFTER_TTS_DONE);
                            break;
                        case "SIGNUP_SUCCESS":
                        case "LOGIN_SUCCESS":
                            handler.postDelayed(
                                    () -> openCamera(),
                                    DELAY_OPEN_CAMERA);
                            break;
                    }
                });
            }
        });

        ttsReady = true;

        // ── Check permissions immediately after TTS ready ────────────────────
        checkPermissions();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  speakOut — single method for all UI + TTS updates
    //  Always call from UI thread.
    // ═════════════════════════════════════════════════════════════════════════
    private void speakOut(String text, String utteranceId) {
        if (tts == null || !ttsReady) {
            // TTS not ready yet — retry after minimal delay
            handler.postDelayed(() -> speakOut(text, utteranceId), 50);
            return;
        }
        try {
            tvVoiceStatus.setText(text);
            int result = tts.speak(text,
                    TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak error — retrying: " + utteranceId);
                handler.postDelayed(() -> speakOut(text, utteranceId), 50);
            }
        } catch (Exception e) {
            Log.e(TAG, "speakOut exception: " + e.getMessage());
            handler.postDelayed(() -> speakOut(text, utteranceId), 50);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Permissions
    // ═════════════════════════════════════════════════════════════════════════
    private void checkPermissions() {
        boolean camOk = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean micOk = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (camOk && micOk) {
            // One-time TTS warmup delay — after this everything is instant
            handler.postDelayed(this::greetUser, DELAY_TTS_WARMUP);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                    }, PERM_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                handler.postDelayed(this::greetUser, DELAY_TTS_WARMUP);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP 1 — Welcome
    // ═════════════════════════════════════════════════════════════════════════
    private void greetUser() {
        appState = STATE_WELCOME;
        // Speak immediately — onDone("WELCOME") triggers launchVoice()
        speakOut(
                "Welcome to Vision Assist. " +
                        "Say Sign Up if you are new, " +
                        "or say Login if you already have an account.",
                "WELCOME"
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Speech recognizer launch
    // ═════════════════════════════════════════════════════════════════════════
    private void launchVoice(String prompt) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        // Launch immediately — no delay here
        speechLauncher.launch(intent);
    }

    private void retryListening() {
        switch (appState) {
            case STATE_WELCOME:  launchVoice("Say Sign Up or Login"); break;
            case STATE_ASK_NAME: launchVoice("Say your username");    break;
            case STATE_ASK_PASS: launchVoice("Say your password");    break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP 2 — Handle voice input
    //  All speakOut() calls happen with DELAY_SPEAK_RESPONSE (20 ms)
    //  to let the recognizer dialog fully dismiss before TTS fires.
    // ═════════════════════════════════════════════════════════════════════════
    private void handleVoice(String heard) {

        if (appState == STATE_WELCOME) {

            if (heard.contains("sign up") || heard.contains("signup")) {
                isSignUp = true;
                appState = STATE_ASK_NAME;
                handler.postDelayed(() ->
                                speakOut("Great! Please say your username.",
                                        "ASK_USERNAME_SIGNUP"),
                        DELAY_SPEAK_RESPONSE);

            } else if (heard.contains("login") || heard.contains("log in")) {
                isSignUp = false;
                appState = STATE_ASK_NAME;
                handler.postDelayed(() ->
                                speakOut("Welcome back. Please say your username.",
                                        "ASK_USERNAME_LOGIN"),
                        DELAY_SPEAK_RESPONSE);

            } else {
                handler.postDelayed(() ->
                                speakOut("Please say Sign Up or Login.", "WELCOME"),
                        DELAY_SPEAK_RESPONSE);
            }

        } else if (appState == STATE_ASK_NAME) {

            savedUsername = heard.replaceAll("\\s+", "");
            etUsername.setText(savedUsername);

            if (isSignUp) {
                String pass = savedUsername.length() >= 4
                        ? savedUsername.substring(0, 4) : savedUsername;
                etPassword.setText(pass);
                appState = STATE_CAMERA;

                // ── Pre-warm ML Kit while TTS speaks success ─────────────────
                // By the time onDone("SIGNUP_SUCCESS") fires and camera opens,
                // ObjectDetector is already initialized.
                initObjectDetector();

                handler.postDelayed(() ->
                                speakOut("Welcome " + savedUsername +
                                                ". Account created. Opening camera.",
                                        "SIGNUP_SUCCESS"),
                        DELAY_SPEAK_RESPONSE);

            } else {
                appState = STATE_ASK_PASS;
                handler.postDelayed(() ->
                                speakOut("Got it. Now please say your password.",
                                        "ASK_PASSWORD"),
                        DELAY_SPEAK_RESPONSE);
            }

        } else if (appState == STATE_ASK_PASS) {

            etPassword.setText(heard);
            appState = STATE_CAMERA;

            // ── Pre-warm ML Kit while TTS speaks success ─────────────────────
            initObjectDetector();

            handler.postDelayed(() ->
                            speakOut("Password received. Logging you in. Opening camera.",
                                    "LOGIN_SUCCESS"),
                    DELAY_SPEAK_RESPONSE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Pre-initialize ML Kit ObjectDetector in background
    //  Called while success TTS is speaking — so it's ready when camera opens
    // ═════════════════════════════════════════════════════════════════════════
    private void initObjectDetector() {
        if (objectDetector != null) return; // already initialized
        cameraExecutor.execute(() -> {
            ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build();
            objectDetector = ObjectDetection.getClient(options);
            Log.i(TAG, "ObjectDetector pre-initialized");
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP 3 — Open camera screen
    // ═════════════════════════════════════════════════════════════════════════
    private void openCamera() {
        loginLayout.setVisibility(View.GONE);
        cameraLayout.setVisibility(View.VISIBLE);
        // Camera provider was pre-warmed in onCreate — binds almost instantly
        bindCamera();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Bind CameraX — uses pre-warmed cameraProviderFuture from onCreate
    // ═════════════════════════════════════════════════════════════════════════
    private void bindCamera() {
        // Ensure ObjectDetector is ready before binding
        if (objectDetector == null) {
            initObjectDetector();
            // Wait minimal time and retry
            handler.postDelayed(this::bindCamera, 50);
            return;
        }

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                );

                // Speak camera ready immediately after bind
                runOnUiThread(() ->
                        speakOut("Camera ready. I will guide you now. " +
                                "Point your phone forward.", "CAMERA_READY"));

            } catch (Exception e) {
                Log.e(TAG, "Camera bind error: " + e.getMessage());
                runOnUiThread(() ->
                        speakOut("Camera failed to start.", "CAMERA_ERROR"));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP 4 — Analyze frame with ML Kit (runs on cameraExecutor thread)
    // ═════════════════════════════════════════════════════════════════════════
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null || objectDetector == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        int frameWidth  = imageProxy.getWidth();
        int frameHeight = imageProxy.getHeight();

        objectDetector.process(image)
                .addOnSuccessListener(detectedObjects -> {
                    processDetectedObjects(detectedObjects,
                            frameWidth, frameHeight);
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "ML Kit error: " + e.getMessage());
                    imageProxy.close();
                });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STEP 4 — Process results with smart speech control
    // ═════════════════════════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════════════════════════
//  FSM — core navigation pipeline
// ═════════════════════════════════════════════════════════════════════════
    private void processDetectedObjects(List<DetectedObject> objects,
                                        int frameWidth, int frameHeight) {
        long now = System.currentTimeMillis();

        switch (navState) {

            case NAV_DETECTING: {
                if (objects == null || objects.isEmpty()) {
                    if (now - lastNavSpokenAt > 4500) {
                        lastNavSpokenAt = now;
                        String msg = "Path is clear. Move forward safely.";
                        if (!msg.equals(lastSpokenMessage)) {
                            lastSpokenMessage = msg;
                            runOnUiThread(() -> {
                                tvDetection.setText("Path is clear");
                                speakDetection(msg);
                            });
                        }
                    }
                    return;
                }

                DetectedObject priority = selectPriorityObject(objects, frameWidth);
                if (priority == null) return;

                Rect box = priority.getBoundingBox();
                lockedTrackingId = priority.getTrackingId();
                lockedArea       = box.width() * box.height();

                String label          = getLabel(priority);
                float  boxWidthRatio  = (float) box.width()  / frameWidth;
                float  boxHeightRatio = (float) box.height() / frameHeight;
                int    distanceCm     = estimateDistance(boxWidthRatio, boxHeightRatio, frameWidth, frameHeight);
                String distCat        = getDistanceCategory(distanceCm);
                float  centerX        = (float) box.centerX() / frameWidth;
                String direction      = getDirection(centerX);
                boolean floorHazard   = isFloorLevelHazard(box, frameHeight);

                // Compute velocity for the very first lock (0 on first frame — ok)
                currentApproachVelocity = computeApproachVelocity(lockedTrackingId, lockedArea, now);

                String message = buildMessage(label, distanceCm, distCat, direction,
                        currentApproachVelocity, floorHazard);
                lastSpokenMessage    = message;
                lastObjectLabel      = label;
                lastDistanceCategory = distCat;
                lastDirection        = direction;
                lastNavSpokenAt      = now;
                navState             = NAV_LOCKED;

                String displayText = label.toUpperCase() + " | " + distanceCm + " cm | " +
                        direction.toUpperCase() +
                        (floorHazard ? " | FLOOR" : "") +
                        (currentApproachVelocity >= VELOCITY_HIGH ? " | FAST" : "");

                final String finalMsg  = message;
                final String finalDisp = displayText;
                runOnUiThread(() -> {
                    tvDetection.setText(finalDisp);
                    speakDetection(finalMsg);
                });
                break;
            }

            case NAV_LOCKED:
            case NAV_GUIDING:
            case NAV_WAITING_FOR_CLEAR: {
                if (objects == null || objects.isEmpty()) {
                    handleCrossing(now);
                    return;
                }

                DetectedObject locked = findLockedObject(objects);
                if (locked == null) {
                    handleCrossing(now);
                    return;
                }

                Rect box         = locked.getBoundingBox();
                int  currentArea = box.width() * box.height();

                // Update velocity on every frame while tracking
                currentApproachVelocity = computeApproachVelocity(lockedTrackingId, currentArea, now);

                if (lockedArea > 0 && currentArea < lockedArea * CROSSING_SHRINK_RATIO) {
                    handleCrossing(now);
                    return;
                }

                navState = NAV_WAITING_FOR_CLEAR;

                // If object is rushing toward user → override cooldown and warn NOW
                boolean criticalApproach = currentApproachVelocity >= VELOCITY_CRITICAL;
                long    effectiveCooldown = criticalApproach ? 800L : NAV_COOLDOWN_MS;

                if (now - lastNavSpokenAt >= effectiveCooldown) {
                    float  centerX   = (float) box.centerX() / frameWidth;
                    String direction = getDirection(centerX);
                    boolean floorHazard = isFloorLevelHazard(box, frameHeight);

                    float  bwr = (float) box.width()  / frameWidth;
                    float  bhr = (float) box.height() / frameHeight;
                    int    dist = estimateDistance(bwr, bhr, frameWidth, frameHeight);

                    // Re-speak if direction changed OR critical approach
                    if (!direction.equals(lastDirection) || criticalApproach || floorHazard) {
                        lastDirection   = direction;
                        lastNavSpokenAt = now;

                        final String guide;
                        if (criticalApproach) {
                            guide = getUrgencyPrefix(currentApproachVelocity, dist) +
                                    capitalize(lastObjectLabel) + " approaching fast! Move " +
                                    (direction.contains("left") ? "right" : "left") + " immediately!";
                        } else if (floorHazard) {
                            guide = "Floor hazard ahead. Step carefully.";
                        } else {
                            guide = getNavigationGuide(lastObjectLabel, direction);
                        }
                        runOnUiThread(() -> speakDetection(guide));
                    }
                }
                break;
            }

            case NAV_CLEAR: {
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Compute how fast the object is approaching (area growth rate)
//  Returns pixels² per millisecond. Positive = approaching, negative = receding.
// ─────────────────────────────────────────────────────────────────────────
    private float computeApproachVelocity(Integer trackingId, int currentArea, long now) {
        if (trackingId == null) {
            // No tracking ID — use key 0 as fallback slot
            trackingId = 0;
        }
        Long prevArea = areaHistory.get(trackingId);
        Long prevTime = timeHistory.get(trackingId);

        areaHistory.put(trackingId, (long) currentArea);
        timeHistory.put(trackingId, now);

        if (prevArea == null || prevTime == null) return 0f;
        long dt = now - prevTime;
        if (dt <= 0 || dt > 2000) return 0f; // ignore stale readings

        return (float)(currentArea - prevArea) / dt;
    }

    private String getUrgencyPrefix(float velocity, int distanceCm) {
        if (velocity >= VELOCITY_CRITICAL || distanceCm <= 35) {
            return "STOP! ";
        } else if (velocity >= VELOCITY_HIGH || distanceCm <= 65) {
            return "Warning! ";
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Select priority object: largest area with a 2× boost for center region
// ─────────────────────────────────────────────────────────────────────────
    private DetectedObject selectPriorityObject(List<DetectedObject> objects,
                                                int frameWidth) {
        DetectedObject best      = null;
        long           bestScore = -1;

        for (DetectedObject obj : objects) {
            Rect box = obj.getBoundingBox();
            if (box == null) continue;
            long  area     = (long) box.width() * box.height();
            float centerX  = (float) box.centerX() / frameWidth;
            boolean inCenter = centerX >= 0.25f && centerX <= 0.75f;
            long  score    = inCenter ? area * 2 : area;
            if (score > bestScore) {
                bestScore = score;
                best      = obj;
            }
        }
        return best;
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Find the locked object by tracking ID; fall back to largest if ID null
// ─────────────────────────────────────────────────────────────────────────
    private DetectedObject findLockedObject(List<DetectedObject> objects) {
        if (lockedTrackingId != null) {
            for (DetectedObject obj : objects) {
                if (lockedTrackingId.equals(obj.getTrackingId())) return obj;
            }
            return null;   // tracking ID disappeared from frame
        }
        // Fallback — no tracking ID assigned: pick largest
        DetectedObject closest = null;
        int maxArea = 0;
        for (DetectedObject obj : objects) {
            Rect box = obj.getBoundingBox();
            if (box == null) continue;
            int area = box.width() * box.height();
            if (area > maxArea) { maxArea = area; closest = obj; }
        }
        return closest;
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Obstacle crossed → announce "Path clear" and reset FSM after 3 s
// ─────────────────────────────────────────────────────────────────────────
    private void handleCrossing(long now) {
        navState          = NAV_CLEAR;
        lastNavSpokenAt   = now;
        lastSpokenMessage = "Path clear";

        runOnUiThread(() -> {
            tvDetection.setText("PATH CLEAR");
            speakDetection("Path clear.");
        });

        // Reset fully to DETECTING after TTS finishes (~3 s buffer)
        handler.postDelayed(() -> {
            navState         = NAV_DETECTING;
            lockedTrackingId = null;
            lockedArea       = 0;
            lastSpokenMessage    = "";
            lastObjectLabel      = "";
            lastDistanceCategory = "";
            lastDirection        = "";
        }, 3000);
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Directional re-guidance (only spoken when direction changes mid-track)
// ─────────────────────────────────────────────────────────────────────────
    private String getNavigationGuide(String label, String direction) {
        switch (direction) {
            case "far left":  return capitalize(label) + " is far to your left. Step well to the right.";
            case "left":      return capitalize(label) + " shifted left. Step slightly right.";
            case "far right": return capitalize(label) + " is far to your right. Step well to the left.";
            case "right":     return capitalize(label) + " shifted right. Step slightly left.";
            default:          return capitalize(label) + " is directly ahead. Stop and wait.";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
//  Get label text from ML Kit object (safe null check)
// ─────────────────────────────────────────────────────────────────────────
    private String getLabel(DetectedObject obj) {
        if (obj.getLabels() != null && !obj.getLabels().isEmpty()) {
            return obj.getLabels().get(0).getText().toLowerCase();
        }
        return "object";
    }
    // ─────────────────────────────────────────────────────────────────────────
//  Detect if object is at floor level (step, curb, low obstacle)
// ─────────────────────────────────────────────────────────────────────────
    private boolean isFloorLevelHazard(Rect box, int frameHeight) {
        float bottomRatio = (float) box.bottom / frameHeight;
        float topRatio    = (float) box.top    / frameHeight;
        // Object sits in the lower portion of frame AND is not too tall
        return bottomRatio >= FLOOR_HAZARD_RATIO && (bottomRatio - topRatio) < 0.35f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Distance estimation from bounding box width ratio
    // ─────────────────────────────────────────────────────────────────────────
    private int estimateDistance(float boxWidthRatio, float boxHeightRatio, int frameW, int frameH) {
        if (boxWidthRatio <= 0 && boxHeightRatio <= 0) return 300;

        // Normalize to a square frame so diagonal is scale-invariant
        float normW = boxWidthRatio;
        float normH = boxHeightRatio * ((float) frameH / frameW);
        float diagonal = (float) Math.sqrt(normW * normW + normH * normH);
        if (diagonal <= 0) return 300;

        // Calibrated empirically: at diagonal=0.1 → ~200cm, at 0.4 → ~40cm
        int cm = (int) (20 / diagonal);
        return Math.max(10, Math.min(cm, 300));
    }

    private String getDistanceCategory(int distanceCm) {
        if (distanceCm > 150) return "far";
        if (distanceCm > 50)  return "nearby";
        if (distanceCm > 5)  return "close";
        return "very close";
    }

    private String getDirection(float centerX) {
        if (centerX < 0.15f) return "far left";
        if (centerX < 0.40f) return "left";
        if (centerX > 0.85f) return "far right";
        if (centerX > 0.60f) return "right";
        return "center";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Build intelligent contextual message
    // ─────────────────────────────────────────────────────────────────────────
    private String buildMessage(String label, int distanceCm, String distCat,
                                String direction, float velocity, boolean floorHazard) {

        // ── Floor-level hazard overrides everything ───────────────────────────
        if (floorHazard) {
            if (label.equals("stairs") || label.equals("staircase")) {
                return "Warning! Stairs directly below. Step very carefully.";
            }
            return "Floor obstacle ahead. " + capitalize(label) + " at ground level. Step carefully.";
        }

        // ── Urgency prefix from velocity ──────────────────────────────────────
        String urgency = getUrgencyPrefix(velocity, distanceCm);

        // ── Direction suffix ──────────────────────────────────────────────────
        String dirSuffix;
        switch (direction) {
            case "far left":  dirSuffix = "well to your left.";   break;
            case "left":      dirSuffix = "slightly to your left."; break;
            case "far right": dirSuffix = "well to your right.";  break;
            case "right":     dirSuffix = "slightly to your right."; break;
            default:          dirSuffix = "directly ahead.";      break;
        }

        // ── Avoidance instruction ─────────────────────────────────────────────
        String dirAction;
        switch (direction) {
            case "far left":  dirAction = "Step well to the right.";    break;
            case "left":      dirAction = "Step slightly right.";       break;
            case "far right": dirAction = "Step well to the left.";     break;
            case "right":     dirAction = "Step slightly left.";        break;
            default:          dirAction = "Stop or proceed with caution."; break;
        }

        // ── Very close / critical distance ────────────────────────────────────
        if (distCat.equals("very close")) {
            switch (label) {
                case "person":
                    return urgency + "Person very close. Stop immediately.";
                case "door":
                    return "Door within reach. Reach forward for the handle.";
                case "wall":
                    return urgency + "Wall right in front! Do not step forward.";
                case "stairs": case "staircase":
                    return urgency + "Stairs very close! Hold the railing and move carefully.";
                default:
                    return urgency + capitalize(label) + " very close! Do not move forward.";
            }
        }

        // ── Label-specific messages ───────────────────────────────────────────
        switch (label) {
            case "door":
                if (direction.equals("center")) {
                    return urgency + "Door " + distanceCm + " cm ahead. Move forward to find the handle.";
                }
                return urgency + "Door " + distanceCm + " cm, " + dirSuffix + " " + dirAction;

            case "person":
                return distCat.equals("close")
                        ? urgency + "Person " + distanceCm + " cm ahead. Stop and wait."
                        : urgency + "Person " + distanceCm + " cm, " + dirSuffix + " " + dirAction;

            case "car": case "truck": case "bus":
            case "motorcycle": case "bicycle":
                return urgency + capitalize(label) + " " + distanceCm + " cm, " +
                        dirSuffix + " Stop and wait for it to pass.";

            case "stairs": case "staircase":
                return urgency + "Stairs " + distanceCm + " cm ahead. Hold the railing and move carefully.";

            case "wall":
                return urgency + "Wall " + distanceCm + " cm ahead. " + dirAction;

            case "chair":
                return "Chair " + distanceCm + " cm, " + dirSuffix + " You can sit, or " + dirAction;

            case "table":
                return urgency + "Table " + distanceCm + " cm, " + dirSuffix + " " + dirAction;

            case "bag": case "backpack": case "handbag":
                return "Bag " + distanceCm + " cm, " + dirSuffix + " " + dirAction;

            case "bed":
                return "Bed " + distanceCm + " cm, " + dirSuffix + " You can rest, or " + dirAction;

            case "bottle": case "cup":
                return capitalize(label) + " on the " + direction + ", " + distanceCm + " cm away.";

            default:
                return urgency + capitalize(label) + " detected " + distanceCm +
                        " cm, " + dirSuffix + " " + dirAction;
        }
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase() + text.substring(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Detection TTS — separate utterance ID from flow IDs
    // ─────────────────────────────────────────────────────────────────────────
    private void speakDetection(String text) {
        if (tts == null || !ttsReady || text == null || text.isEmpty()) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                    "DET_" + System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Detection TTS error: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═════════════════════════════════════════════════════════════════════════
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (objectDetector != null) {
            try { objectDetector.close(); }
            catch (Exception e) { Log.e(TAG, "Detector close: " + e.getMessage()); }
        }
    }
}