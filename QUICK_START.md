# Quick Start Guide - Build Your APK in 5 Minutes

## 🚀 Fast Track to Getting Your APK

### Prerequisites
- ✅ GitHub account
- ✅ GitHub PAT token (already configured in Replit)
- ✅ This complete Android app source code (ready!)

---

## Step 1: Create GitHub Repository (2 minutes)

1. Go to https://github.com/new
2. Repository name: `chess-move-detector-android` (or any name you like)
3. Set to **Public** or **Private**
4. **DO NOT** initialize with README (we already have one)
5. Click **"Create repository"**

---

## Step 2: Push Code to GitHub (1 minute)

Copy your repository URL from GitHub, then run these commands in Replit Shell:

```bash
# Initialize git
git init

# Add all files
git add .

# Commit with message
git commit -m "Chess Move Detector Android App with OpenCV"

# Add your GitHub repo (REPLACE WITH YOUR REPO URL)
git remote add origin https://github.com/YOUR_USERNAME/chess-move-detector-android.git

# Push to GitHub using your PAT token (already stored securely)
git push -u origin main
```

**Note**: If you get an error about branch name, try:
```bash
git branch -M main
git push -u origin main
```

---

## Step 3: GitHub Actions Builds APK (2-5 minutes)

GitHub Actions automatically starts building:

1. Go to your repository on GitHub
2. Click **"Actions"** tab at the top
3. You'll see "Build Debug APK" workflow running
4. Wait for the green checkmark ✅ (takes ~2-5 minutes)

---

## Step 4: Download Your APK (30 seconds)

1. Click on the completed workflow run
2. Scroll down to **"Artifacts"** section
3. Click **"chess-move-detector-debug"** to download
4. Extract the ZIP file
5. You'll find `app-debug.apk` inside

---

## Step 5: Install on Android Phone (1 minute)

### Method A: Direct Transfer
1. Transfer `app-debug.apk` to your phone via USB/email/cloud
2. On your phone, go to Settings → Security → Enable "Unknown Sources"
3. Open the APK file
4. Tap "Install"

### Method B: ADB (for developers)
```bash
adb install app-debug.apk
```

---

## ✅ Done! Use Your App

1. Open "Chess Move Detector" app
2. Tap "Select First Board Image" → choose a chessboard photo
3. Tap "Select Second Board Image" → choose another position
4. Tap "Detect Move"
5. See the results!

---

## 🔄 Making Changes

If you want to modify the app:

1. Edit files in Replit
2. Run:
   ```bash
   git add .
   git commit -m "Updated app"
   git push
   ```
3. GitHub Actions rebuilds automatically
4. Download new APK from Actions tab

---

## 🎯 Key Files to Customize

- `app/src/main/java/com/chessmove/detector/ChessMoveDetector.kt` - Detection algorithm
- `app/src/main/java/com/chessmove/detector/MainActivity.kt` - App UI logic
- `app/src/main/res/layout/activity_main.xml` - UI design
- `app/src/main/res/values/strings.xml` - Text strings

Change sensitivity:
```kotlin
var WHITE_DETECTION_SENSITIVITY = 95  // Change these values
var BLACK_DETECTION_SENSITIVITY = 50
var EMPTY_DETECTION_SENSITIVITY = 50
```

---

## 💡 Pro Tips

- **Test builds faster**: Use "workflow_dispatch" to manually trigger builds from Actions tab
- **Keep old APKs**: Artifacts are kept for 30 days, download before they expire
- **Debug issues**: Check Actions logs if build fails
- **Share your app**: Give the APK file to friends to install

---

## 🆘 Need Help?

**Build fails?**
- Check Actions tab for error details
- Ensure repository has Actions enabled (Settings → Actions → Allow all actions)

**APK won't install?**
- Enable "Unknown Sources" or "Install Unknown Apps" permission
- Check if you have enough storage space
- Try uninstalling any previous version first

**App crashes?**
- Grant storage permissions when prompted
- Use Android 7.0 or higher
- Check photos are valid image files

---

## 🎉 That's It!

You now have a fully functional Android app that detects chess moves using computer vision!

**Total time**: ~5-10 minutes
**Cost**: $0 (completely free using GitHub Actions)
**Result**: Professional Android APK ready to install

Enjoy your Chess Move Detector app! ♟️
