# VisionAssist Android
[![Kotlin](https://img.shields.io/badge/Kotlin-44.4%25-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-55.6%25-007396?logo=java)](https://www.java.com)

**An AI-powered Android application that assists visually impaired users** through real-time object detection, OCR, and speech feedback — built with Google ML Kit, CameraX, and Firebase.

## 🎯 Mission

Enable visually impaired individuals to navigate independently with confidence by providing real-time audio and haptic feedback about their surroundings.

## ✨ Features

- **Real-time Object Detection** with bounding boxes and speech output
- **Image Recognition & OCR** with Text-to-Speech feedback
- **Offline-First Architecture** — core features work without internet
- **Secure Authentication** via Firebase with strong password enforcement
- **Haptic Feedback** for tactile navigation cues
- **Voice Control** for hands-free operation
- **Accessibility-First Design** compatible with TalkBack screen reader

## 📋 System Requirements

- **Android SDK:** minSdkVersion 26 (Android 8.0), targetSdkVersion 34 (Android 14)
- **JDK:** Java 11+
- **Android Studio:** Electric Eel (2022.1.1) or later
- **Gradle:** 8.0+
- **RAM:** 4GB minimum for emulator, 8GB+ recommended
- **Device:** Physical device with camera and microphone recommended

## 🛠 Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|----------|
| **Language** | Java (56%) · Kotlin (44%) | App development |
| **ML Framework** | Google ML Kit | Object detection, image classification |
| **Camera** | CameraX | Modern camera API with lifecycle awareness |
| **Authentication** | Firebase Auth | Secure login/signup |
| **Speech** | Android TTS API | Real-time audio feedback |
| **State Management** | Kotlin Coroutines · StateFlow | Async operations, reactive UI |
| **Testing** | JUnit 4, Espresso, Robolectric | Unit & instrumented tests |
| **CI/CD** | GitHub Actions | Automated testing & builds |
## Demo:
https://github.com/user-attachments/assets/edf363ed-7b7d-41a0-8d3e-4c2654b28126
## 🚀 Quick Start

### Prerequisites
```bash
# Clone the repository
git clone https://github.com/SHWETHAA-US/VisionAssist_Android.git
cd VisionAssist_Android

# Ensure JDK 11+ is installed
java -version

# Open in Android Studio
# File > Open > Select this directory
```

### Firebase Setup (Critical)

1. **Create Firebase Project:**
   - Go to [Firebase Console](https://console.firebase.google.com)
   - Click "Add Project"
   - Name it "VisionAssist" → Continue
   - Select or create a Google Cloud project → Create Firebase

2. **Add Android App:**
   - In Firebase Console, click "Add App" → Android
   - Enter package name: `com.visionassist`
   - Download `google-services.json`
   - Place it in `app/` directory (same level as `build.gradle`)

3. **Enable Authentication:**
   - Firebase Console → Authentication → Sign-in method
   - Enable "Email/Password"
   - (Optional) Enable "Google Sign-In" for frictionless access

4. **Security Rules (Firestore if used):**
   ```firestore
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{userId} {
         allow read, write: if request.auth.uid == userId;
       }
     }
   }
   ```

### Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run on emulator/device
./gradlew installDebug
./gradlew run

# Run unit tests
./gradlew test

# Run instrumented tests (requires emulator)
./gradlew connectedAndroidTest

# Build release APK (unsigned)
./gradlew assembleRelease
```

## 📐 Architecture

```
┌─────────────────────────────────────────────────────┐
│           MainActivity (Kotlin)                      │
│         Voice & Permission handling                  │
└──────────┬────────────┬────────────┬────────────────┘
           │            │            │
      ┌────▼─┐    ┌─────▼────┐   ┌──▼─────┐
      │Camera│    │ ML Kit   │   │Firebase│
      │  (CX)│    │  Models  │   │  Auth  │
      └────┬─┘    └─────┬────┘   └──┬─────┘
           │            │            │
      ┌────▼────────────▼────────────▼──┐
      │  State Management (FSM + Coroutines)  │
      │  Navigation, Model Loading      │
      └────┬────────┬────────────────────┘
           │        │
    ┌──────▼──┐  ┌──▼──────────┐
    │  TTS    │  │   Haptics   │
    │ (Audio) │  │ (Vibration) │
    └─────────┘  └─────────────┘
```

### Module Breakdown

| Module | Responsibility |
|--------|----------------|
| **MainActivity** | Entry point, permission requests, voice recognition orchestration |
| **AuthManager** | Firebase sign-up/login with strong password validation |
| **MLKitModelManager** | Load & cache ML Kit models, inference coordination |
| **NavigationFSM** | Finite State Machine for app state transitions |
| **BluetoothHapticManager** | Haptic feedback patterns for navigation |
| **CameraX Manager** | Frame capture, image preprocessing |

## 📱 Permissions

All permissions are requested at runtime with user-friendly prompts:

| Permission | Reason | Category |
|-----------|--------|----------|
| `CAMERA` | Capture real-time video | Dangerous |
| `RECORD_AUDIO` | Capture voice commands | Dangerous |
| `INTERNET` | Firebase authentication & ML Kit | Normal |
| `ACCESS_FINE_LOCATION` | (Optional) Location-based features | Dangerous |
| `VIBRATE` | Haptic feedback | Normal |
| `BLUETOOTH_SCAN` (Android 12+) | Connect to haptic devices | Dangerous |
| `BLUETOOTH_CONNECT` (Android 12+) | Control haptic devices | Dangerous |

## 🔒 Security

✅ **Implemented:**
- Firebase Authentication with email/password (8+ chars, mixed case)
- Network certificate pinning (Firebase endpoints)
- Cleartext traffic disabled (`usesCleartextTraffic=false`)
- Backup disabled (`allowBackup=false`)
- Code obfuscation via ProGuard/R8
- No hardcoded secrets (Firebase config via `google-services.json`)

⚠️ **Recommendations for Production:**
1. Enable 2FA in Firebase Console
2. Review Firestore Security Rules
3. Monitor Firebase Crashlytics for crashes
4. Use Firebase Test Lab for real-device testing

## 📦 Installation Options

### Option 1: Build Locally
```bash
./gradlew assembleDebug
# APK available at: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option 2: GitHub Releases (Planned)
[Download latest debug APK](https://github.com/SHWETHAA-US/VisionAssist_Android/releases)

### Option 3: Google Play Store (In Progress)
[Play Store Link](https://play.google.com) *(coming soon)*

## 🧪 Testing

### Unit Tests
```bash
./gradlew test
```

Tests cover:
- AuthManager (Firebase signup/login)
- MLKitModelManager (model caching)
- NavigationFSM (state transitions)
- PasswordValidator (password strength)

### Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

Tests cover:
- CameraX image capture flow
- TTS callbacks
- Permission requests
- Fragment navigation

### Manual Testing Checklist
- [ ] Permission requests on first launch
- [ ] Voice input recognition ("Sign Up" / "Login")
- [ ] Firebase authentication (signup, login, logout)
- [ ] Object detection with live feedback
- [ ] TTS announcements clear and audible
- [ ] Haptic feedback on actions
- [ ] Works offline (after model caching)

## 🛠 Troubleshooting

### Build Issues

**"google-services.json not found"**
- Ensure you downloaded it from Firebase Console
- Place it in `app/` directory (not `app/src/`)

**"Could not resolve com.google.firebase:firebase-auth"**
- Run `./gradlew clean`
- Sync Gradle: File → Sync Now

### Runtime Issues

**Camera not opening**
- Grant CAMERA permission when prompted
- Check: Settings → Apps → VisionAssist → Permissions → Camera

**TTS not speaking**
- Grant RECORD_AUDIO permission (voice input requires it)
- Check device volume is not muted
- Verify TTS language pack installed: Settings → Accessibility → Text-to-Speech

**Firebase authentication fails**
- Ensure Firebase project has Email/Password enabled
- Check `google-services.json` is correctly placed
- Verify app's package name matches Firebase project

### Performance Optimization

- **Frame skipping:** Adaptively set based on device capability
- **Model caching:** ML Kit models cached in `app/cache/`
- **Battery drain:** Monitor via Android Profiler (View → Profiler)

## 📖 Documentation

- [REFACTOR_COMPLETE.md](./REFACTOR_COMPLETE.md) — Detailed refactoring notes
- [ARCHITECTURE.md](./docs/ARCHITECTURE.md) — Deep dive into module design
- [Firebase Setup Guide](./docs/FIREBASE_SETUP.md) — Step-by-step Firebase integration
- [Accessibility Guide](./docs/ACCESSIBILITY.md) — TalkBack compatibility

## 🤝 Contributing

We welcome contributions from the community! Please follow these guidelines:

### Workflow
1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Commit with conventional messages: `git commit -m "feat: add new feature"`
4. Push and open a Pull Request
5. Ensure CI passes and at least one review before merge

### Code Standards
- **Language:** Prefer Kotlin; migrate Java to Kotlin incrementally
- **Formatting:** Run `./gradlew ktlintFormat` before committing
- **Testing:** Add unit tests for new features; aim for >80% coverage
- **Accessibility:** Test with TalkBack enabled

### Commit Message Format
```
<type>(<scope>): <subject>

<body>

<footer>
```

Examples:
- `feat(auth): add Google Sign-In support`
- `fix(camera): resolve frame skipping on low-end devices`
- `docs: add Firebase setup guide`
- `chore: upgrade Gradle to 8.1`

## 📊 Project Status

| Milestone | Status | Target |
|-----------|--------|--------|
| Core features (detection, OCR, TTS) | ✅ Complete | v1.0 |
| Firebase Authentication | ✅ Complete | v1.0 |
| CI/CD Pipeline | 🔄 In Progress | v1.0 |
| Kotlin Migration | 🔄 In Progress | v1.1 |
| UI/Instrumented Tests | 🔄 In Progress | v1.1 |
| Play Store Release | 📋 Planned | Q3 2026 |
| Google Accessibility Program | 📋 Planned | Q3 2026 |

## 📞 Support & Contact

- **Issues:** [GitHub Issues](https://github.com/SHWETHAA-US/VisionAssist_Android/issues)
- **Discussions:** [GitHub Discussions](https://github.com/SHWETHAA-US/VisionAssist_Android/discussions)
- **Email:** [shwethaa.us@example.com](mailto:shwethaa.us@example.com)

## 📄 License

This project is licensed under the [MIT License](./LICENSE).

## 🙏 Acknowledgments

- **Google ML Kit** for powerful on-device ML capabilities
- **Firebase** for secure, scalable backend services
- **CameraX** for modern camera integration
- **Accessibility Community** for feedback and guidance

---

**Made with ❤️ for visually impaired users worldwide.**
