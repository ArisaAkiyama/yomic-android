<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0052CC,100:00AAFF&height=200&section=header&text=Yomic%20Mobile&fontSize=70&fontColor=fff&animation=fadeIn&fontAlignY=38&desc=Pembaca%20Manga,%20Manhwa%20%26%20Webtoon%20Android%20Native%20Modern&descAlignY=60&descSize=19" width="100%"/>

<div align="center">

<img src="./app/src/main/res/drawable/yomic_logo.png" alt="Yomic Mobile Logo" width="112"/>

<br/>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=18&pause=1000&color=0066FF&center=true&vCenter=true&width=740&lines=Baca+Manga+%7C+Manhwa+%7C+Manhua+%7C+Webtoon+di+Android;102%2B+Ekstensi+JavaScript+Modular+(QuickJS+%2B+Jsoup);Dibangun+dengan+Kotlin%2C+Jetpack+Compose+%26+Material+3;Tanpa+Iklan+%E2%80%94+Ringan%2C+Open+Source+%26+Gratis+Selamanya" alt="Typing SVG"/>

<br/><br/>

[![Lisensi: MIT](https://img.shields.io/badge/Lisensi-MIT-yellow.svg?style=flat-square)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024--35)-3DDC84.svg?style=flat-square&logo=android&logoColor=white)](https://github.com/ArisaAkiyama/yomic-android)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-4285F4.svg?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Ekstensi](https://img.shields.io/badge/Ekstensi-102%2B%20Sumber-22C55E.svg?style=flat-square)](https://github.com/ArisaAkiyama/extension-yomic)
[![Build APK](https://img.shields.io/github/actions/workflow/status/ArisaAkiyama/yomic-android/android-mobile-build.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=CI%20Build)](https://github.com/ArisaAkiyama/yomic-android/actions/workflows/android-mobile-build.yml)
[![Versi Desktop](https://img.shields.io/badge/Desktop-Yomic%20v2%20(Windows)-0078D4.svg?style=flat-square&logo=windows)](https://github.com/ArisaAkiyama/yomic)

<br/>

### 🌐 Bahasa / Language

[![English](https://img.shields.io/badge/Language-English-0066CC?style=for-the-badge)](./README.md)
[![Bahasa Indonesia](https://img.shields.io/badge/Bahasa-Indonesia%20(Aktif)-22C55E?style=for-the-badge)](./README.id.md)

<br/>

<table>
  <tr>
    <td align="center" width="240">
      <b>📥 Unduh APK Android</b><br/><br/>
      <a href="https://github.com/ArisaAkiyama/yomic-android/actions/workflows/android-mobile-build.yml">
        <img src="https://img.shields.io/badge/GitHub_Actions-Unduh_APK-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Unduh Yomic Mobile APK"/>
      </a>
    </td>
    <td align="center" width="240">
      <b>💻 Yomic Desktop (Windows)</b><br/><br/>
      <a href="https://github.com/ArisaAkiyama/yomic">
        <img src="https://img.shields.io/badge/Windows_PC-Yomic_v2-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Yomic v2 Desktop"/>
      </a>
    </td>
    <td align="center" width="240">
      <b>☕ Dukungan &amp; Donasi</b><br/><br/>
      <a href="https://trakteer.id/Arisa-Akiyama" target="_blank">
        <img src="https://edge-cdn.trakteer.id/images/embed/trbtn-red-1.png?v=14-05-2025" alt="Dukung di Trakteer" height="40"/>
      </a>
    </td>
  </tr>
</table>

<p align="center">
  <b>Jika Anda menyukai Yomic Mobile, jangan lupa berikan ⭐ pada repositori ini!</b>
</p>

</div>

---

## 📖 Daftar Isi

- [Ringkasan](#-ringkasan)
- [Fitur Utama](#-fitur-utama)
- [Arsitektur Aplikasi](#️-arsitektur-aplikasi)
- [Cara Instalasi](#-cara-instalasi)
- [Build dari Source Code](#️-build-dari-source-code)
- [Struktur Proyek](#-struktur-proyek)
- [Penafian (Disclaimer)](#️-penafian-disclaimer)
- [Lisensi](#-lisensi)

---

## 🔍 Ringkasan

**Yomic Mobile** (`id.yomic.mobile`) adalah aplikasi pembaca manga, manhwa, manhua, dan webtoon **Android Native** yang modern, ringan, dan *open-source*. Dibangun menggunakan **Kotlin**, **Jetpack Compose**, dan **Material 3**, aplikasi ini menghadirkan seluruh fitur unggulan serta identitas visual [Yomic v2 Desktop](https://github.com/ArisaAkiyama/yomic) ke perangkat Android dengan navigasi **5-Tab ala Mihon/Tachiyomi**, mesin ekstensi **QuickJS + Jsoup** yang kompatibel dengan 102+ ekstensi JavaScript Yomic, unduhan offline format `.cbz`, serta kompatibilitas penuh file cadangan `.yomicbackup` lintas platform (PC & Android).

---

## ✨ Fitur Utama

### 🎨 Antarmuka Material 3 Adaptif & Tema Khas Yomic
- **Identitas Visual Yomic**: Tema **Oceanic Light**, **Antigravity Carbon Dark**, dan **Deep-Ocean Reader Dark (`#0A0A0A`)** beserta opsi **AMOLED Pure Black**.
- **Navigasi Bawah 5-Tab Ergonomis**:
  1. **Pustaka (Library)**: Tab kategori horizontal, tampilan Grid 3-kolom / List, pencarian instan, *BottomSheet* 4-tab (`Kategori | Saring | Sumber | Urutkan`), aksi massal (*batch selection*), dan *pull-to-refresh*.
  2. **Pembaruan (Updates)**: Umpan bab terbaru yang dikelompokkan berdasarkan `Hari Ini`, `Kemarin`, dan `Sebelumnya` dengan tombol unduh & baca cepat.
  3. **Riwayat (History)**: Linimasa bacaan kronologis beserta pencarian dan tombol lanjutkan membaca.
  4. **Jelajahi (Hub 2-Tab)**:
     - **Sumber**: Katalog sumber terpasang (`Komiku`, `Kiryuu`, `MangaDex`, `Softkomik`, `WestManga`, `VoraToon`, dll.), filter bahasa (`ID` / `EN`), indikator kesehatan server, dan sakelar bahasa `ID / EN` khusus MangaDex.
     - **Ekstensi**: Manajer ekstensi daring yang terhubung ke repositori [ArisaAkiyama/extension-yomic](https://github.com/ArisaAkiyama/extension-yomic) untuk memasang, memperbarui, dan menghapus ekstensi dalam satu ketukan.
  5. **Lainnya (More)**: Sakelar cepat **Mode Gelap** & **Mode Penyamaran / Offline**, akses ke **Antrean Unduhan**, **Statistik Membaca**, **Kelola Kategori**, **Pengumuman**, **Kirim Masukan**, **Panduan Pemula**, dan **Pengaturan**.
- **Dukungan Dua Bahasa (`ID` / `EN`)**: Ganti bahasa antarmuka Indonesia dan Inggris secara instan tanpa perlu memulai ulang aplikasi.

### 📖 Pembaca Layar Penuh & Pemrosesan Gambar Canggih
- **3 Mode Baca**: **Webtoon** (gulir vertikal mulus dengan pengaturan jarak halaman), **Halaman Tunggal**, dan **Dua Halaman** dengan arah baca `LTR` / `RTL`.
- **Kontrol Interaktif**: Navigasi Ketuk 3-Zona (Kiri / Tengah / Kanan), *Double-Tap & Pinch-to-Zoom*, *Auto-Scroll*, navigasi halaman dengan tombol volume fisik, dan penahan layar tetap menyala.
- **Pipeline Gambar Coil 3 Khusus**:
  - **Dekripsi Gambar Otomatis (`AesImageDecryptor`)**: Mendekripsi stream gambar terproteksi AES-CBC dan XOR secara langsung di memori.
  - **Injeksi Header Per-Gambar (`PipeHeaderOkHttpInterceptor`)**: Mendukung sintaks pipe `url|Referer=...|User-Agent=...` khas ekstensi Yomic.
  - **Pemrosesan Gambar Cerdas (`YomicImageProcessor`)**: Pemotongan pinggiran putih/hitam otomatis (*auto-crop borders*), *smart downscaling*, dan penajaman gambar (*unsharp mask*).
  - **Pembaca Arsip `.cbz` Langsung (`CbzPageFetcher`)**: Membaca halaman langsung dari file `.cbz` tanpa mengekstrak file sementara ke penyimpanan.

### 🌐 Mesin Ekstensi QuickJS & Ketahanan Jaringan
- **Kompatibel 100% dengan Ekstensi Yomic Desktop**: Menjalankan skrip `.js` secara native menggunakan **QuickJS (`quickjs-kt`)** yang dipadukan dengan **`JsoupDomBridge`** serta fallback **`WebViewPageExtractor`** untuk situs berbasis SPA / Next.js.
- **DNS-over-HTTPS (DoH)**: Mendukung **Cloudflare (`1.1.1.1`)**, **Google (`8.8.8.8`)**, dan **AdGuard** untuk menembus pemblokiran DNS ISP.
- **Penyelesai Tantangan Cloudflare**: Dilengkapi `CloudflareSolverActivity` interaktif yang tersinkronisasi dengan `WebViewCookieJar`.

### 📚 Unduhan Offline, Statistik, Pelacak & Cadangan Lintas Platform
- **Background Worker Handal (WorkManager)**: `ChapterDownloadWorker` untuk unduhan multi-bab di latar belakang (format folder maupun arsip `.cbz`, unduh otomatis bab berikutnya, hapus otomatis setelah dibaca) dan `LibraryUpdateWorker` terjadwal.
- **Dasbor Statistik Membaca**: Melacak total bab terbaca, estimasi jam baca, grafik aktivitas 14 hari terakhir, komposisi genre favorit, dan *Daily Streak*.
- **Sinkronisasi MyAnimeList (MAL) OAuth2 PKCE**: Tautkan komik ke akun MAL dan sinkronkan progres bab secara otomatis.
- **Kunci Biometrik Aplikasi**: Lindungi akses aplikasi dengan sidik jari atau pemindai wajah (`androidx.biometric`).
- **Cadangan `.yomicbackup` Lintas Platform**: Ekspor dan pulihkan data pustaka secara mulus antara **Yomic v2 Desktop (PC)** dan **Yomic Mobile (Android)**.

---

## 🏗️ Arsitektur Aplikasi

```text
┌──────────────────────────────────────────────────────────────────────┐
│                 Lapisan UI Jetpack Compose (Material 3)              │
│   5-Tab NavHost • Pustaka • Jelajahi • Detail • Pembaca • Statistik  │
└───────────────────────────────┬──────────────────────────────────────┘
                                │  StateFlow / Unidirectional Data Flow
┌───────────────────────────────▼──────────────────────────────────────┐
│            ViewModel & Pengendali Fitur (YomicViewModel)             │
│   LibraryController • ReaderController • BrowseController • Sync     │
└──────────────┬────────────────────────────────┬──────────────────────┘
               │                                │
┌──────────────▼─────────────────┐ ┌────────────▼──────────────────────┐
│   Lapisan Data & Penyimpanan   │ │   Mesin Ekstensi & Jaringan       │
│ • Room SQLite DB (DAO/Skema)   │ │ • QuickJsExtensionEngine          │
│ • DataStore Preferences        │ │ • JsoupDomBridge & WebView        │
│ • WorkManager (Unduh/Sinkron)  │ │ • OkHttp + DoH + CookieJar        │
│ • .yomicbackup & MAL PKCE      │ │ • Coil 3 + AES/CBZ/ImageProcessor │
└────────────────────────────────┘ └───────────────────────────────────┘
```

---

## 📦 Cara Instalasi

1. Buka halaman [**GitHub Actions — Yomic Mobile Android APK**](https://github.com/ArisaAkiyama/yomic-android/actions/workflows/android-mobile-build.yml) (atau halaman [**Releases**](https://github.com/ArisaAkiyama/yomic-android/releases)).
2. Pilih hasil build terbaru, lalu unduh salah satu artifact berikut:
   - **`Yomic-Mobile-release-apk`** *(Direkomendasikan — telah dioptimasi dengan R8, ukuran sangat ringan)*
   - **`Yomic-Mobile-debug-apk`** *(Untuk pengujian/debugging, dapat dipasang berdampingan sebagai `id.yomic.mobile.debug`)*
3. Ekstrak file `.zip` hasil unduhan dan pasang file `.apk` di perangkat Android Anda (minimal Android 7.0 / API 24).

---

## 🛠️ Build dari Source Code

Anda dapat meng-compile **Yomic Mobile** menggunakan **Toolchain CLI ringan (~500 MB tanpa Android Studio)** maupun menggunakan **Android Studio**.

### Opsi 1 — Build CLI Ringan Tanpa Android Studio

```powershell
# 1. Clone repository
git clone https://github.com/ArisaAkiyama/yomic-android.git
cd yomic-android

# 2. Jalankan setup toolchain sekali saja (Menginstal OpenJDK 17 + Android CLI + SDK API 35 + opsional scrcpy)
.\scripts\setup-android-cli.ps1 -WithScrcpy

# 3. Build, pasang otomatis ke HP Android via USB/Wireless ADB, dan tampilkan layar HP di PC
.\scripts\build-and-run.ps1 -Scrcpy

# Atau build Release APK super ringan (R8 Minified) tanpa install langsung
.\scripts\build-and-run.ps1 -Release -NoInstall
```

### Opsi 2 — Perintah Gradle Standar

```bash
# Menjalankan seluruh unit test (QuickJS JVM bridge, Room DAO, Dekripsi Gambar, Repository)
./gradlew :app:testGithubDebugUnitTest

# Build APK Debug & Release (varian GitHub dengan katalog ekstensi bawaan)
./gradlew :app:assembleGithubDebug :app:assembleGithubRelease

# Build APK varian Neutral (tanpa URL repositori ekstensi pihak ketiga bawaan)
./gradlew :app:assembleNeutralRelease
```

---

## 📁 Struktur Proyek

```text
yomic-android/
├── app/
│   ├── schemas/                         # Skema ekspor Room SQLite
│   └── src/
│       ├── main/
│       │   ├── java/id/yomic/mobile/
│       │   │   ├── data/
│       │   │   │   ├── backup/          # Eksportir/importir .yomicbackup lintas platform
│       │   │   │   ├── image/           # Pipeline Coil 3, dekripsi AES, pembaca CBZ, auto-crop
│       │   │   │   ├── local/           # Room Database, Entity, dan DAO
│       │   │   │   ├── network/         # OkHttp client, DoH provider, Cloudflare solver
│       │   │   │   ├── preferences/     # Pengaturan DataStore & penyimpanan token terenkripsi
│       │   │   │   ├── repository/      # Repository Pustaka, Pembaca, Ekstensi, Unduhan, Riwayat
│       │   │   │   ├── service/         # Layanan pembaruan aplikasi & sinkronisasi pustaka
│       │   │   │   └── tracker/         # Layanan pelacak MyAnimeList OAuth2 PKCE
│       │   │   ├── di/                  # Dependency container (AppContainer)
│       │   │   ├── engine/              # Runtime QuickJS, Jsoup DOM bridge, WebView extractor
│       │   │   ├── model/               # Model domain aplikasi
│       │   │   ├── ui/                  # Layar Jetpack Compose, komponen, tema & lokalisasi ID/EN
│       │   │   ├── viewmodel/           # YomicViewModel & controller modular per fitur
│       │   │   └── worker/              # Background worker WorkManager (unduhan & sinkronisasi)
│       │   └── res/                     # Drawable, ikon, tema XML, konfigurasi keamanan jaringan
│       └── test/                        # Unit test JUnit 4 + Coroutines + MockWebServer
├── gradle/                              # Katalog versi (libs.versions.toml) & Gradle wrapper
├── scripts/                             # Skrip PowerShell setup CLI & build-and-run satu klik
├── .github/workflows/                   # Pipeline CI GitHub Actions otomatis untuk build APK
└── build.gradle.kts                     # Konfigurasi Gradle tingkat root
```

---

## ⚠️ Penafian (Disclaimer)

Yomic Mobile tidak menyimpan, memiliki, atau mendistribusikan konten komik apa pun. Seluruh metadata dan gambar manga, manhwa, manhua, serta webtoon diambil secara langsung dari situs pihak ketiga melalui ekstensi yang dipasang oleh pengguna. Mohon dukung kreator dan penerbit resmi kapan pun memungkinkan.

---

## 📄 Lisensi

Didistribusikan di bawah **Lisensi MIT**. Lihat file [LICENSE](./LICENSE) untuk informasi selengkapnya.

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:00AAFF,100:0052CC&height=120&section=footer" width="100%"/>
