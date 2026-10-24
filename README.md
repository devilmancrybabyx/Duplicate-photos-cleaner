# Photo Dedup Pro 📸

A modern, high-performance Android duplicate and similar photo cleaner built with **Jetpack Compose (Material 3)**, **Perceptual Hashing (dHash)**, **SHA-256**, and **Android Scoped Storage MediaStore API**.

---

## 🚀 Features

- **⚡ Perceptual Difference Hashing (dHash)**:
  - Downscales images to $9 \times 8$ grayscale matrices.
  - Computes 64-bit gradient hash across adjacent pixels.
  - Groups visually similar photos, burst shots, resized images, and WhatsApp compressions using **Hamming Distance** ($\le 8$).
- **🔒 Cryptographic SHA-256 Duplication Verification**:
  - Matches exact duplicates via file size + SHA-256 hash.
- **🛡️ Safe & Scoped Storage Compliant**:
  - Leverages Android MediaStore `createDeleteRequest` (Android 11+ / API 30+).
  - Deletion requests are confirmed via the Android OS dialog, moving photos to system trash or securely deleting them with full user consent.
  - Backward compatible with older Android versions.
- **🎨 Modern Jetpack Compose Material 3 UI**:
  - Clean AMOLED dark theme with custom selection badges (Green for Keep, Red for Delete).
  - **HorizontalPager Gallery Modal**: Long-press any photo thumbnail to inspect in full-screen and swipe between photos in the group.
  - **Smart Selection**: Automatically marks redundant photos for deletion while keeping the best photo in each cluster.
  - **Multi-criteria Sorting**: Sort by Newest First, Oldest First, Largest First, or Smallest First per group or globally.
  - Fast thumbnail rendering powered by **Coil**.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (BOM `2025.02.00`) with Material 3
- **Image Loading**: Coil Compose 2.7.0
- **Asynchronous Architecture**: Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Default`) + Flow / StateFlow + Android ViewModel
- **Target SDK**: Android 15 (API 35), Minimum SDK: Android 8.0 (API 26)

---

## 📦 Releases & Installation

Download the latest APK directly from the [GitHub Releases](https://github.com/devilmancrybabyx/Duplicate-photos-cleaner/releases) page:
1. Download `PhotoDedupPro-v1.0.0-release.apk`.
2. Tap the downloaded file to install on your Android device (ensure "Install unknown apps" permission is enabled if prompted).
3. Grant photo access permission when prompted and tap **Start Smart Photo Scan**.

---

## 🔨 Building from Source

```bash
# Clone the repository
git clone https://github.com/devilmancrybabyx/Duplicate-photos-cleaner.git
cd Duplicate-photos-cleaner

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

The compiled APKs will be located in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## 📄 License

MIT License. Free for personal and commercial use.
