# Chess Move Detector Android App

An Android application that detects chess moves between two chessboard images using OpenCV computer vision.

## Features

- Select two chessboard images from your device
- Automatically detect the chessboard in images
- Identify piece positions (white and black pieces)
- Detect moves between two board states
- Display detailed detection results

## Building the App

### Local Build

1. Open the project in Android Studio
2. Sync Gradle files
3. Build > Build Bundle(s) / APK(s) > Build APK(s)
4. The debug APK will be in `app/build/outputs/apk/debug/`

### GitHub Actions Build

This project includes a GitHub Actions workflow that automatically builds the debug APK:

1. Push your code to GitHub
2. Go to Actions tab in your repository
3. The workflow will run automatically on push
4. Download the APK artifact from the workflow run

Or manually trigger the workflow:
- Go to Actions tab
- Select "Build Debug APK"
- Click "Run workflow"

## Technical Details

### Libraries Used

- **OpenCV 4.5.3.0**: QuickBird Studios OpenCV library from Maven Central (`com.quickbirdstudios:opencv-android:4.5.3.0`)
- **AndroidX**: Modern Android libraries
- **Material Components**: Material Design UI components
- **Kotlin Coroutines**: For asynchronous processing

### Detection Algorithm

The app uses the same detection logic as the original code:

1. **Board Detection**: Finds the largest square-like contour in the image
2. **Perspective Transform**: Warps the board to a top-down view
3. **Orientation Detection**: Determines which side has white pieces
4. **Piece Detection**: Analyzes each square using:
   - Brightness differences
   - Edge detection
   - Standard deviation of pixel values
   - Corner patch analysis
5. **Move Detection**: Compares two board states to identify moves

### Sensitivity Controls

The detection algorithm uses three sensitivity parameters (defined in `ChessMoveDetector.kt`):

- `WHITE_DETECTION_SENSITIVITY = 95`: Higher values detect more white pieces
- `BLACK_DETECTION_SENSITIVITY = 50`: Higher values detect more black pieces
- `EMPTY_DETECTION_SENSITIVITY = 50`: Higher values detect more empty squares

## Permissions

The app requires permission to read images from storage:
- `READ_EXTERNAL_STORAGE` (Android 12 and below)
- `READ_MEDIA_IMAGES` (Android 13+)

## Requirements

- Android 7.0 (API 24) or higher
- Storage permission to access images

## Usage

1. Launch the app
2. Tap "Select First Board Image" and choose a chessboard photo
3. Tap "Select Second Board Image" and choose another chessboard photo
4. Tap "Detect Move"
5. View the detected pieces and moves in the results section

## GitHub Actions Workflow

The `.github/workflows/build.yml` file configures automatic APK building:

- Triggers on push to main/master branch
- Can be manually triggered
- Uploads the debug APK as an artifact
- Artifacts are retained for 30 days

## Project Structure

```
ChessMoveDetector/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/chessmove/detector/
│   │       │   ├── MainActivity.kt
│   │       │   └── ChessMoveDetector.kt
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       ├── colors.xml
│   │       │       └── themes.xml
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/
│   └── workflows/
│       └── build.yml
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## License

This project uses OpenCV which is licensed under Apache 2.0 License.
