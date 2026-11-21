<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0052CC,100:00AAFF&height=200&section=header&text=Yomic%20Mobile&fontSize=70&fontColor=fff&animation=fadeIn&fontAlignY=38&desc=Modern%20Native%20Android%20Manga,%20Manhwa%20%26%20Webtoon%20Reader&descAlignY=60&descSize=19" width="100%"/>

<div align="center">

<img src="./app/src/main/res/drawable/yomic_logo.png" alt="Yomic Mobile Logo" width="112"/>

<br/>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=18&pause=1000&color=0066FF&center=true&vCenter=true&width=740&lines=Read+Manga+%7C+Manhwa+%7C+Manhua+%7C+Webtoon+on+Android;102%2B+Modular+JavaScript+Extensions+(QuickJS+%2B+Jsoup);Built+with+Kotlin%2C+Jetpack+Compose+%26+Material+3;Zero+Ads+%E2%80%94+Lightweight%2C+Open+Source+%26+Free+Forever" alt="Typing SVG"/>

<br/><br/>

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024--35)-3DDC84.svg?style=flat-square&logo=android&logoColor=white)](https://github.com/ArisaAkiyama/yomic-android)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-4285F4.svg?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Extensions](https://img.shields.io/badge/Extensions-102%2B%20Sources-22C55E.svg?style=flat-square)](https://github.com/ArisaAkiyama/extension-yomic)
[![Latest Release](https://img.shields.io/github/v/release/ArisaAkiyama/yomic-android?style=flat-square&color=0066FF&label=Latest)](https://github.com/ArisaAkiyama/yomic-android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/ArisaAkiyama/yomic-android/total?style=flat-square&color=22C55E&label=Downloads)](https://github.com/ArisaAkiyama/yomic-android/releases)
[![Build APK](https://img.shields.io/github/actions/workflow/status/ArisaAkiyama/yomic-android/android-mobile-build.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=CI%20Build)](https://github.com/ArisaAkiyama/yomic-android/actions/workflows/android-mobile-build.yml)
[![Desktop Companion](https://img.shields.io/badge/Desktop-Yomic%20v2%20(Windows)-0078D4.svg?style=flat-square&logo=windows)](https://github.com/ArisaAkiyama/yomic)

<br/>

<p align="center">
  <b>Yomic Mobile</b> is a modern, lightweight, open-source native Android application for reading manga, manhwa, manhua, and webtoons.<br/>
  Powered by <b>Kotlin, Jetpack Compose, Material 3, QuickJS &amp; Jsoup</b> with zero ads, offline <code>.cbz</code> downloads, and 102+ modular JS extensions.
</p>

<br/>

<table>
  <tr>
    <td align="center" width="240">
      <b>📥 Download Android APK</b><br/><br/>
      <a href="https://github.com/ArisaAkiyama/yomic-android/releases/latest">
        <img src="https://img.shields.io/badge/Download-Yomic_Mobile_v1.0.0.apk-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download Yomic Mobile APK"/>
      </a>
    </td>
    <td align="center" width="240">
      <b>💻 Yomic Desktop (Windows)</b><br/><br/>
      <a href="https://github.com/ArisaAkiyama/yomic">
        <img src="https://img.shields.io/badge/Windows_PC-Yomic_v2-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Yomic v2 Desktop"/>
      </a>
    </td>
    <td align="center" width="240">
      <b>☕ Support &amp; Donation</b><br/><br/>
      <a href="https://trakteer.id/Arisa-Akiyama" target="_blank">
        <img src="https://edge-cdn.trakteer.id/images/embed/trbtn-red-1.png?v=14-05-2025" alt="Support on Trakteer" height="40"/>
      </a>
    </td>
  </tr>
</table>

<p align="center">
  <b>If you enjoy using Yomic Mobile, please consider giving the repository a ⭐!</b>
</p>

</div>

---

## 📖 Table of Contents

- [Overview](#-overview)
- [Key Features](#-key-features)
- [Architecture](#️-architecture)
- [Installation](#-installation)
- [Building from Source](#️-building-from-source)
- [Project Structure](#-project-structure)
- [Disclaimer](#️-disclaimer)
- [License](#-license)

---

## 🔍 Overview

**Yomic Mobile** (`id.yomic.mobile`) brings the full comic discovery, library management, offline reading, and tracking capabilities of [Yomic v2 Desktop](https://github.com/ArisaAkiyama/yomic) to Android devices. Built from the ground up with **Kotlin**, **Jetpack Compose**, and **Material 3**, it features an ergonomic **5-tab navigation flow**, a native **QuickJS + Jsoup** extension runtime compatible with 102+ Yomic JavaScript extensions, offline `.cbz` archiving, and cross-platform `.yomicbackup` interoperability.

---

## ✨ Key Features

### 🎨 Adaptive Material 3 UI & Themes
- **Signature Yomic Visual Identity**: **Oceanic Light**, **Antigravity Carbon Dark**, and **Deep-Ocean Reader Dark (`#0A0A0A`)** themes with optional AMOLED Pure Black mode.
- **5-Tab Ergonomic Navigation**:
  1. **Library**: Horizontal category tabs, 3-column Grid / List toggle, instant search, 4-tab filter & sort sheet (`Category | Filter | Source | Sort`), batch actions, and pull-to-refresh sync.
  2. **Updates**: Chronological chapter feed grouped by `Today`, `Yesterday`, and `Earlier` with one-tap download and quick-read triggers.
  3. **History**: Timestamped reading timeline with instant resume and search.
  4. **Browse (2-Tab Hub)**:
     - **Sources**: Installed source catalog (`Komiku`, `Kiryuu`, `MangaDex`, `Softkomik`, `WestManga`, `VoraToon`, etc.), language filters (`ID` / `EN`), server health badges, and MangaDex in-app language switcher.
     - **Extensions**: Remote extension manager connected to [ArisaAkiyama/extension-yomic](https://github.com/ArisaAkiyama/extension-yomic) with one-tap install, update, and removal.
  5. **More**: Quick toggles for **Dark Mode** and **Incognito / Offline Mode**, plus access to **Download Queue**, **Reading Insights**, **Category Manager**, **Announcements**, **Feedback**, **Onboarding Guide**, and **Settings**.
- **Bilingual In-App UI (`ID` / `EN`)**: Instant runtime language switching between English and Indonesian inside the app.

### 📖 Immersive Fullscreen Reader & Image Pipeline
- **3 Reading Modes**: Continuous vertical **Webtoon** (with adjustable page spacing), **Single Page**, and **Dual Page** modes with `LTR` / `RTL` reading direction.
- **Interactive Controls**: 3-Zone Tap Navigation (Left / Center / Right), Double-Tap & Pinch-to-Zoom, configurable Auto-Scroll, Volume Key Page Turning, and Keep Screen Awake.
- **Coil 3 + Custom Image Pipeline**:
  - **On-the-Fly Decryption (`AesImageDecryptor`)**: Transparent AES-CBC and XOR byte stream decryption for protected sources.
  - **Custom Request Headers (`PipeHeaderOkHttpInterceptor`)**: Per-image `Referer`, `User-Agent`, and cookie injection using Yomic's `url|Key=Value` pipe syntax.
  - **Smart Image Processing (`YomicImageProcessor`)**: Automatic whitespace/black border cropping, smart downscaling, and unsharp-mask sharpening.
  - **Direct `.cbz` Streaming (`CbzPageFetcher`)**: Reads pages directly from compressed `.cbz` archives without extracting temporary files to disk.

### 🌐 QuickJS Extension Engine & Network Resilience
- **100% Desktop Extension Compatibility**: Executes Yomic `.js` source plugins natively via **QuickJS (`quickjs-kt`)** paired with a custom **`JsoupDomBridge`** (`querySelector`, `querySelectorAll`, attribute resolution) and headless **`WebViewPageExtractor`** fallback for dynamic SPAs / Next.js hydration.
- **DNS-over-HTTPS (DoH)**: Built-in DoH resolver supporting **Cloudflare (`1.1.1.1`)**, **Google (`8.8.8.8`)**, and **AdGuard** to bypass ISP DNS blocking.
- **Cloudflare Challenge Solver**: Integrated `CloudflareSolverActivity` with persistent `WebViewCookieJar` synchronization.

### 📚 Offline Downloads, Insights, Tracker & Cross-Platform Backup
- **WorkManager Background Jobs**: Reliable multi-chapter `ChapterDownloadWorker` (folder or `.cbz` archive format, auto-download next chapter, auto-delete after read) and scheduled `LibraryUpdateWorker`.
- **Reading Insights Dashboard**: Tracks total chapters read, estimated reading hours, 14-day activity chart, top genre breakdown, and daily reading streak.
- **MyAnimeList (MAL) OAuth2 PKCE Sync**: Bind manga entries and sync chapter progress automatically with encrypted token storage.
- **Biometric App Lock**: Secure application access via fingerprint or face unlock (`androidx.biometric`).
- **Cross-Platform `.yomicbackup`**: Export and restore libraries seamlessly between **Yomic v2 Desktop** and **Yomic Mobile**.

---

## 🏗️ Architecture

```text
┌──────────────────────────────────────────────────────────────────────┐
│                 Jetpack Compose UI Layer (Material 3)                │
│   5-Tab NavHost • Library • Browse • Detail • Reader • Insights      │
└───────────────────────────────┬──────────────────────────────────────┘
                                │  StateFlow / Unidirectional Data Flow
┌───────────────────────────────▼──────────────────────────────────────┐
│            ViewModel & Domain Controllers (YomicViewModel)           │
│   LibraryController • ReaderController • BrowseController • Sync     │
└──────────────┬────────────────────────────────┬──────────────────────┘
               │                                │
┌──────────────▼─────────────────┐ ┌────────────▼──────────────────────┐
│   Data & Persistence Layer     │ │   Extension & Network Engine      │
│ • Room SQLite DB (Daos/Schema) │ │ • QuickJsExtensionEngine          │
│ • DataStore Preferences        │ │ • JsoupDomBridge & WebView        │
│ • WorkManager (Download/Sync)  │ │ • OkHttp + DoH + CookieJar        │
│ • .yomicbackup & MAL PKCE      │ │ • Coil 3 + AES/CBZ/ImageProcessor │
└────────────────────────────────┘ └───────────────────────────────────┘
```

---

## 📦 Installation

1. Download **`Yomic_Mobile_v1.0.0.apk`** from [**GitHub Releases**](https://github.com/ArisaAkiyama/yomic-android/releases/latest) (or grab the latest CI build from [**GitHub Actions**](https://github.com/ArisaAkiyama/yomic-android/actions/workflows/android-mobile-build.yml)):
   - **`Yomic_Mobile_v1.0.0.apk`** (Recommended — R8 minified release build with official extension catalog)
   - **`Yomic_Mobile_v1.0.0-debug.apk`** (Debug build — can be installed side-by-side as `id.yomic.mobile.debug`)
2. Open the downloaded `.apk` file on your Android device (Android 7.0 / API 24 or newer) and install.

---

## 🛠️ Building from Source

You can build **Yomic Mobile** either with a lightweight **CLI-only toolchain (~500 MB, no Android Studio required)** or with **Android Studio**.

### Option 1 — Lightweight CLI Build (No Android Studio)

```powershell
# 1. Clone the repository
git clone https://github.com/ArisaAkiyama/yomic-android.git
cd yomic-android

# 2. Run one-time CLI toolchain setup (Installs OpenJDK 17 + Android CLI + API 35 SDK + optional scrcpy)
.\scripts\setup-android-cli.ps1 -WithScrcpy

# 3. Build, install to connected Android phone via USB/Wireless ADB, and mirror screen
.\scripts\build-and-run.ps1 -Scrcpy

# Or build R8-minified Release APK without installing
.\scripts\build-and-run.ps1 -Release -NoInstall
```

### Option 2 — Standard Gradle Commands

```bash
# Run unit test suite (QuickJS JVM bridge, Room DAOs, Image decryptor, Repositories)
./gradlew :app:testGithubDebugUnitTest

# Assemble GitHub flavor Debug & Release APKs
./gradlew :app:assembleGithubDebug :app:assembleGithubRelease

# Assemble Neutral flavor (no pre-bundled extension repository URL)
./gradlew :app:assembleNeutralRelease
```

---

## 📁 Project Structure

```text
yomic-android/
├── app/
│   ├── schemas/                         # Room SQLite exported schemas
│   └── src/
│       ├── main/
│       │   ├── java/id/yomic/mobile/
│       │   │   ├── data/
│       │   │   │   ├── backup/          # Cross-platform .yomicbackup exporter/importer
│       │   │   │   ├── image/           # Coil 3 pipeline, AES decryptor, CBZ fetcher, cropping
│       │   │   │   ├── local/           # Room Database, Entities, and DAOs
│       │   │   │   ├── network/         # OkHttp client, DoH providers, Cloudflare solver
│       │   │   │   ├── preferences/     # DataStore settings & secure token store
│       │   │   │   ├── repository/      # Library, Reader, Extension, Download, History repos
│       │   │   │   ├── service/         # App update & library update orchestrators
│       │   │   │   └── tracker/         # MyAnimeList OAuth2 PKCE service
│       │   │   ├── di/                  # Dependency container (AppContainer)
│       │   │   ├── engine/              # QuickJS runtime, Jsoup DOM bridge, WebView extractor
│       │   │   ├── model/               # Domain models
│       │   │   ├── ui/                  # Jetpack Compose screens, components, theme & i18n
│       │   │   ├── viewmodel/           # YomicViewModel & modular feature controllers
│       │   │   └── worker/              # WorkManager background download & sync workers
│       │   └── res/                     # Drawables, icons, themes, network security config
│       └── test/                        # JUnit 4 + Coroutines + MockWebServer unit tests
├── gradle/                              # Version catalog (libs.versions.toml) & wrapper
├── scripts/                             # PowerShell CLI setup & one-click build-and-run scripts
├── .github/workflows/                   # Automated GitHub Actions APK CI pipeline
└── build.gradle.kts                     # Root Gradle configuration
```

---

## ⚠️ Disclaimer

Yomic Mobile does not host, own, or distribute any comic content. All manga, manhwa, manhua, and webtoon metadata and images are fetched directly from third-party websites via user-installed extensions. Please support official creators and publishers whenever possible.

---

## 📄 License

Distributed under the **MIT License**. See [LICENSE](./LICENSE) for more information.

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:00AAFF,100:0052CC&height=120&section=footer" width="100%"/>
