# Chess Move Detector - Android App Project

## Project Overview
This is a complete Android application source code that detects chess moves between two chessboard images using OpenCV computer vision library.

## Purpose
- Provide full Android app source code ready for GitHub Actions build
- Uses OpenCV 4.10.0 from Maven Central (official org.opencv library)
- User's chess detection algorithm integrated with Android UI
- Builds debug APK via GitHub Actions workflow

## Key Features
- Select two chessboard images from device storage
- Detect chess pieces (white/black) on both boards
- Compare board states and identify moves
- Display detailed detection results

## Technology Stack
- **Language**: Kotlin
- **Build System**: Gradle 8.2
- **OpenCV**: 4.5.3.0 (com.quickbirdstudios:opencv-android from Maven Central)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

## Project Structure
```
ChessMoveDetector/
├── app/
│   ├── src/main/
│   │   ├── java/com/chessmove/detector/
│   │   │   ├── MainActivity.kt (UI and image selection)
│   │   │   └── ChessMoveDetector.kt (Chess detection logic)
│   │   ├── res/ (UI layouts, strings, themes)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts (App dependencies)
├── .github/workflows/build.yml (GitHub Actions)
├── build.gradle.kts (Project config)
├── settings.gradle.kts
└── README.md
```

## Detection Algorithm
Uses the original user-provided chess detection logic:
1. Board detection via contour finding
2. Perspective transformation to top-down view
3. Piece detection using brightness, edges, and statistical analysis
4. Move detection by comparing two board states

## Building
### GitHub Actions (Automated)
- Push to GitHub repository
- GitHub Actions automatically builds debug APK
- Download APK from workflow artifacts

### Local Build (Android Studio)
- Open project in Android Studio
- Sync Gradle
- Build > Build APK

## Important Notes
- This is NOT a web app - it's Android app source code
- Uses QuickBird Studios OpenCV library version 4.5.3.0
- OpenCV initialization uses OpenCVLoader.initLocal() for Android
- All user's detection logic preserved, only adapted for Android/Bitmap
- This project is source code only - build via GitHub Actions workflow
