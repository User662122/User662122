# Chess Move Detector - Android App

This is a complete Android application that detects chess moves between two chess board images using computer vision with OpenCV.

## Important Notice

**This is Android source code that must be built and run in Android Studio.** Android apps cannot run on Replit - they require Android Studio and an Android device or emulator.

## What This App Does

1. Displays a main screen with buttons to select 2 images from your mobile device storage
2. Uses the provided chess detection logic (unchanged) to analyze the images
3. Detects chess moves between the two board positions
4. Displays results on screen in the format:
   - "White moved: e2e4"
   - "Black moved: e7e5"
   - "White captured: e4d5"
   - etc.

## Project Structure

```
ChessDetector/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/chessdetector/
│   │   │   ├── ChessMoveDetector.kt  (Your exact logic - unchanged)
│   │   │   └── MainActivity.kt        (UI and image selection)
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       └── colors.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 🚀 Build Methods

### Method 1: GitHub Actions (Automatic APK Build) ⚡ **RECOMMENDED**

**Perfect for CI/CD - OpenCV downloads automatically!**

1. **Push this code to GitHub**:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```

2. **GitHub Actions will automatically**:
   - Download OpenCV 4.9.0 from Maven Central
   - Build the debug APK
   - Upload it as an artifact

3. **Download your APK**:
   - Go to your GitHub repository
   - Click on **Actions** tab
   - Click on the latest workflow run
   - Scroll down to **Artifacts**
   - Download `chess-detector-debug-apk`
   - Extract and install `app-debug.apk` on your Android device

**Workflow triggers:**
- ✅ Automatic on every push to main/master branch
- ✅ Automatic on pull requests
- ✅ Manual trigger from Actions tab (workflow_dispatch)

### Method 2: Build Locally in Android Studio

**Note:** OpenCV is already configured as a Maven dependency - **no manual SDK download needed!**

1. **Open in Android Studio**:
   - File → Open → Select this project folder

2. **Sync and Build**:
   - Click **File → Sync Project with Gradle Files**
   - Gradle will automatically download OpenCV 4.9.0
   - Build the project: **Build → Make Project**

3. **Run on Device**:
   - Connect Android device via USB (with USB debugging enabled) OR start an AVD emulator
   - Click the **Run** button (green play icon)
   - Select your device
   - The app will install and launch

## How to Use the App

1. Open the app on your Android device
2. Tap **"Select First Image"** and choose the first chess board image from your gallery
3. Tap **"Select Second Image"** and choose the second chess board image
4. Tap **"Detect Chess Moves"** button
5. View the detected moves displayed on screen

## Permissions

The app requests these permissions:
- `READ_EXTERNAL_STORAGE` - To read images from device storage
- `READ_MEDIA_IMAGES` - For Android 13+ devices

## Requirements

- **Minimum Android Version**: Android 7.0 (API 24)
- **Target Android Version**: Android 14 (API 34)
- **OpenCV Version**: 4.8.0 or higher
- **Kotlin**: 1.9.20
- **Android Gradle Plugin**: 8.2.0

## Features

- ✅ Clean, simple user interface
- ✅ Easy image selection from device storage
- ✅ Real-time move detection using OpenCV
- ✅ Support for standard chess move notation
- ✅ Detects regular moves and captures
- ✅ Handles board orientation automatically
- ✅ Your exact chess detection logic (100% unchanged)

## Troubleshooting

### OpenCV not found error
- Make sure you imported the OpenCV module correctly
- Verify `settings.gradle` includes `include ':opencv'`
- Clean and rebuild: **Build → Clean Project** then **Build → Rebuild Project**

### Image selection not working
- Grant storage permissions when prompted
- On Android 13+, ensure READ_MEDIA_IMAGES permission is granted

### App crashes on startup
- Check that OpenCV SDK is properly imported
- Verify minimum SDK version is 24 or higher
- Check Logcat for specific error messages

## Next Steps to Deploy

This is a development-ready Android app. To distribute it:

1. **Generate signed APK**:
   - Build → Generate Signed Bundle / APK
   - Create a keystore
   - Sign your app

2. **Publish to Google Play Store** (optional):
   - Create a Google Play Developer account
   - Follow Play Console publishing guidelines
   - Upload your signed APK/AAB

## Notes

- The chess detection logic in `ChessMoveDetector.kt` is exactly as you provided - **no changes made**
- All image processing happens on-device using OpenCV
- No internet connection required after installation
- Works with any chess board images captured from a camera

## Support

For Android Studio setup help, visit: https://developer.android.com/studio
For OpenCV Android documentation: https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html
