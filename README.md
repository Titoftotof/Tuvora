<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Tuvora" width="300" />
  <br />
  <br />

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Stremio Addon ecosystem • Android TV optimized • Playback-focused experience
  </p>

</div>

## About

Tuvora TV is a modern media player designed specifically for Android TV.

It acts as a client-side playback interface that can integrate with the Stremio addon ecosystem for content discovery and source resolution through user-installed extensions.

Built with Kotlin and optimized for a TV-first viewing experience.

## Installation

### Android TV

Download the latest APK from [GitHub Releases](https://github.com/Titoftotof/Tuvora/releases/latest) and install on your Android TV device.

## TV Login Web Page

The TV login page is hosted via GitHub Pages at:
https://titoftotof.github.io/Tuvora/tv-login/

## Development

### Prerequisites

- Android Studio (latest version)
- JDK 17+
- Android SDK (API 36)
- Gradle 8.0+

### Setup

```bash
git clone https://github.com/Titoftotof/Tuvora.git
cd Tuvora
```

### Full Debug Build

```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug
```

## Built With

* Kotlin
* Jetpack Compose & TV Material3
* ExoPlayer / Media3
* Hilt (Dependency Injection)
* Retrofit (Networking)
* Gradle
