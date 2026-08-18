# perf-analyzer-adapter (Android)

Android SDK for [ParityFix](https://github.com/parityfix) — streams real-device
performance telemetry to the ParityFix dashboard over a websocket. Package:
`dev.perfanalyzer`.

## Install

Via [JitPack](https://jitpack.io):

```groovy
// settings.gradle / settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

```groovy
// app/build.gradle
dependencies {
    implementation 'com.github.parityfix:perf-analyzer-adapter:<tag>'
}
```

## Quick start

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfAnalyzer.start(this) // ws://localhost:7001 by default — see PerfConfig
    }
}
```

`start()` wires up everything with zero other app code:

- **Frame timing** — phase breakdown (build/raster) via Android's FrameMetrics
  API on API 24+, falling back to total frame duration below that.
- **Memory** — PSS polled every 2s.
- **View overdraw** — heuristic stacked-background detection per screen.
- **Layout build time** — how long each screen took to inflate + measure +
  layout, for both Activities and Fragments (Jetpack Navigation).
- **Crashes** — fatal exceptions parsed from logcat.

Already have the zero-code adb Live Monitor covering frames/memory/CPU/crashes
and just want Compose/API visibility? Use `PerfAnalyzer.connect(config)`
instead of `.start()`.

### Compose

```kotlin
// Recomposition counts (flushed once/sec):
Text("hi", modifier = Modifier.perfTrack("Greeting"))

// Time to first composition:
Column(modifier = Modifier.perfTrackBuild("HomeScreen")) { ... }
```

### Network calls

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(PerfInterceptor())
    .build()
```

## Configuration

```kotlin
PerfAnalyzer.start(this, PerfConfig(
    host = "192.168.1.23", // your dev machine's LAN IP, or "localhost" + `adb reverse tcp:7001 tcp:7001`
    port = 7001,
))
```

## Known limits

- Compose has no public API to walk its composition tree or read recomposition
  counts from outside the app (that's Android Studio's private App Inspection
  protocol) — `Modifier.perfTrack()`/`perfTrackBuild()` are opt-in, one line
  per composable you want visibility into.
- View overdraw is a heuristic (stacked opaque backgrounds), not real
  per-pixel GPU overdraw.
