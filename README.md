<div align="center">

<img src="art/icon.png" width="128" height="128" alt="Dupe cleaner Logo" />

# Dupe cleaner 📸

**Modern, fast, and intelligent duplicate & similar media cleaner for Android**  
*Built with Jetpack Compose (Material 3), Perceptual Hashing (dHash), Cryptographic SHA-256, and Scoped Storage.*

[![Release](https://img.shields.io/github/v/release/devilmancrybabyx/Duplicate-photos-cleaner?color=4CAF50&label=Latest%20Release)](https://github.com/devilmancrybabyx/Duplicate-photos-cleaner/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/devilmancrybabyx/Duplicate-photos-cleaner/build-and-release.yml?branch=main&label=Build%20APK)](https://github.com/devilmancrybabyx/Duplicate-photos-cleaner/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Author](https://img.shields.io/badge/Developer-devilmancrybabyx-03DAC6.svg?logo=github)](https://github.com/devilmancrybabyx)

</div>

---

## ✨ Features

- **⚡ Perceptual Difference Hashing (dHash) & SHA-256**:
  - Downscales images and video frames to $9 \times 8$ grayscale matrices.
  - Computes 64-bit gradient hash across adjacent pixels to detect visually similar photos, burst shots, resized images, and compressed copies.
  - Dual-layer verification: uses **SHA-256** checksum verification on identical file sizes for 100% exact duplicate verification.
- **🎬 Full Media Support (Photos, Videos & GIFs)**:
  - Scans pictures (JPEG, PNG, WEBP, HEIC), animated GIFs, and videos (MP4, MKV, 3GP, MOV).
  - Uses hardware-accelerated Coil video frame decoders for smooth thumbnail extraction.
- **🔄 Persistent Scan Memory Cache**:
  - Automatically caches scan results so you can exit the app and return without rescanning from scratch.
  - One-tap "Clear Scan Memory Cache" option in the hamburger menu.
- **📤 Share Held Media Directly**:
  - Long-press any photo or video thumbnail to inspect full-screen in the horizontal swipe pager, and tap the **Share** button to send it via any app.
- **🎨 Modern Material 3 Centered UI**:
  - Clean AMOLED dark theme (`#121212`) with smooth radar scanning pulse animation.
  - Hamburger navigation drawer with quick statistics and developer contact information.
  - Compact icon-only group controls: **Keep 1st Only** (`1`), **Delete All** (`DeleteSweep`), and **Keep All** (`DoneAll`).
- **🛡️ Scoped Storage Compliant & Safe Deletion**:
  - Leverages Android MediaStore `createDeleteRequest` (Android 11+ / API 30+).
  - Prompts system confirmation dialog before moving files to trash or permanently deleting them.

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (BOM `2025.02.00`) with Material 3
- **Image & Video Loading**: Coil Compose 2.7.0 (`coil-video`, `coil-gif`)
- **Architecture**: Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Default`) + Flow / StateFlow + Android ViewModel
- **Target SDK**: Android 15 (API 35), Minimum SDK: Android 8.0 (API 26)

---

## 📦 Releases & Installation

Download the latest APK directly from the [GitHub Releases](https://github.com/devilmancrybabyx/Duplicate-photos-cleaner/releases) page:
1. Download **`DupeCleaner-v1.1.4-release.apk`** (or the latest release APK from the Releases tab).
2. Tap the downloaded file to install on your Android device.
3. Grant media permissions and tap **Start Smart Media Scan**.

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

The compiled APKs will be output in:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## 👤 Developer Contact

- **GitHub Profile**: [@devilmancrybabyx](https://github.com/devilmancrybabyx)
- **Repository**: [devilmancrybabyx/Duplicate-photos-cleaner](https://github.com/devilmancrybabyx/Duplicate-photos-cleaner)

---

## 📄 License

MIT License. Free for personal and commercial use.
