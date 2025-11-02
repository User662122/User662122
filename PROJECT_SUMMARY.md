# Chess Move Detector Android App - Complete Project

## ✅ Project Status: READY FOR GITHUB

Your complete Android app source code is ready! This project contains everything needed to build an APK using GitHub Actions.

---

## 📱 What This App Does

1. **Select two chessboard images** from your phone's storage
2. **Detect chess pieces** on both boards using OpenCV computer vision
3. **Compare the boards** and identify what moves were made
4. **Display detailed results** showing piece positions and detected moves

---

## 🛠 Technology Stack

- **Language**: Kotlin
- **Build System**: Gradle 8.2
- **OpenCV Library**: `com.quickbirdstudios:opencv-android:4.5.3.0`
- **Min Android Version**: Android 7.0 (API 24)
- **Target Android Version**: Android 14 (API 34)

---

## 📁 Complete File Structure

```
ChessMoveDetector/
├── .github/
│   └── workflows/
│       └── build.yml                    # GitHub Actions workflow for building APK
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/chessmove/detector/
│   │       │   ├── MainActivity.kt      # UI logic, image selection
│   │       │   └── ChessMoveDetector.kt # Your chess detection algorithm
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml # UI layout
│   │       │   ├── values/
│   │       │   │   ├── strings.xml
│   │       │   │   ├── colors.xml
│   │       │   │   └── themes.xml
│   │       │   ├── mipmap-*/            # App icons (all sizes)
│   │       │   └── drawable/
│   │       └── AndroidManifest.xml      # App configuration
│   ├── build.gradle.kts                 # App dependencies
│   └── proguard-rules.pro              # Code optimization rules
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts                     # Project configuration
├── settings.gradle.kts                  # Project settings
├── gradle.properties                    # Gradle properties
├── gradlew                             # Gradle wrapper (Linux/Mac)
├── .gitignore                          # Git ignore rules
└── README.md                           # Documentation
```

---

## 🎯 Your Chess Detection Algorithm - FULLY PRESERVED

Your original code has been integrated without changing the detection logic:

### Functions Included:
- `shrinkPolygon()` - Shrinks polygon inward
- `detectLargestSquareLike()` - Finds chessboard in image
- `normalizeSensitivity()` - Normalizes sensitivity parameters
- `detectPiecesOnBoard()` - Detects white/black pieces on each square
- `getBoardStateFromBitmap()` - Processes Android Bitmap (adapted from file path version)
- `detectUciMoves()` - Compares two boards and identifies moves

### Sensitivity Parameters (unchanged):
```kotlin
var WHITE_DETECTION_SENSITIVITY = 95
var BLACK_DETECTION_SENSITIVITY = 50
var EMPTY_DETECTION_SENSITIVITY = 50
```

---

## 🚀 How to Build the APK on GitHub

### Step 1: Push Code to GitHub

```bash
# Initialize git repository
git init

# Add all files
git add .

# Commit
git commit -m "Initial commit - Chess Move Detector Android App"

# Add your GitHub repository as remote
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO_NAME.git

# Push using your PAT token (already configured in Replit)
git push -u origin main
```

### Step 2: GitHub Actions Will Automatically Build

Once you push to GitHub:

1. ✅ GitHub Actions workflow triggers automatically
2. ✅ Sets up Java 17 environment
3. ✅ Downloads Gradle and all dependencies
4. ✅ Builds the debug APK
5. ✅ Uploads APK as downloadable artifact

### Step 3: Download Your APK

1. Go to your GitHub repository
2. Click on **"Actions"** tab
3. Click on the latest workflow run
4. Scroll down to **"Artifacts"**
5. Download **"chess-move-detector-debug"**
6. Extract the ZIP file
7. Install `app-debug.apk` on your Android phone

---

## 📲 Installing on Your Phone

### Option 1: Direct Install (Developer Mode)
1. Enable "Install from Unknown Sources" in Android Settings
2. Transfer the APK to your phone
3. Open the APK file and install

### Option 2: ADB Install
```bash
adb install app-debug.apk
```

---

## 🎮 How to Use the App

1. **Launch the app** on your Android device
2. **Tap "Select First Board Image"** → Choose a chessboard photo
3. **Tap "Select Second Board Image"** → Choose another chessboard photo (after a move)
4. **Tap "Detect Move"** button
5. **View the results** showing:
   - Detected white pieces on both boards
   - Detected black pieces on both boards
   - Moves identified between the two positions

---

## 🔧 Manual Build (Optional - Android Studio)

If you want to build locally instead of using GitHub Actions:

1. Download Android Studio
2. Clone this repository
3. Open in Android Studio
4. Wait for Gradle sync
5. Build → Build Bundle(s) / APK(s) → Build APK(s)
6. APK will be in `app/build/outputs/apk/debug/`

---

## 📝 Permissions

The app requests:
- **READ_EXTERNAL_STORAGE** (Android 12 and below)
- **READ_MEDIA_IMAGES** (Android 13+)

These are needed to select chessboard images from your photo gallery.

---

## ⚙️ GitHub Actions Workflow Details

The `.github/workflows/build.yml` file:

```yaml
- Triggers on: Push to main/master, Pull Requests, Manual trigger
- Uses: Ubuntu latest runner
- Java Version: 17 (Temurin distribution)
- Build Command: ./gradlew assembleDebug
- Artifact Retention: 30 days
```

---

## 🎨 App UI Features

- Material Design 3 components
- Two image preview cards
- Image selection buttons
- Large "Detect Move" button
- Scrollable results display
- Clean, modern interface

---

## 🔑 Important Notes

✅ **Your detection code is unchanged** - Only adapted to work with Android Bitmaps instead of file paths
✅ **No web app** - This is pure Android source code
✅ **GitHub Actions ready** - Push and build happens automatically
✅ **QuickBird Studios OpenCV** - Using version 4.5.3.0 as requested
✅ **All sensitivity parameters preserved** - Your exact values maintained

---

## 📞 Next Steps

1. **Create a GitHub repository** (if you haven't already)
2. **Push this code** to your repository
3. **Wait for GitHub Actions** to build the APK
4. **Download and install** the APK on your Android phone
5. **Test with chessboard photos!**

---

## 🐛 Troubleshooting

**If GitHub Actions fails:**
- Check the Actions tab for error logs
- Ensure your repository has Actions enabled
- Verify the workflow file is in `.github/workflows/`

**If the app crashes:**
- Make sure you've granted storage permissions
- Ensure your chessboard images are clear and well-lit
- Check Android version is 7.0 or higher

**If detection doesn't work:**
- Use well-lit, clear photos of chessboards
- Ensure the entire board is visible in the image
- Try adjusting the sensitivity parameters in `ChessMoveDetector.kt`

---

## 📄 Files Ready for GitHub

All files are ready in your current directory. Simply push to GitHub and you're done!

**Total Files Created**: 25+
**Lines of Code**: 1000+
**Ready to Build**: ✅ YES

---

Your Android Chess Move Detector app is complete and ready to go! 🎉
