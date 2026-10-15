# perf-analyzer-adapter (Android)

[![JitPack](https://jitpack.io/v/parityfix/perf_analyzer.svg)](https://jitpack.io/#parityfix/perf_analyzer)

Android SDK for [ParityFix](https://github.com/parityfix/perf_analyzer) — streams real-device
performance telemetry to the ParityFix dashboard over a websocket. Package:
`dev.perfanalyzer`.

## Install

Via [JitPack](https://jitpack.io/#parityfix/perf_analyzer):

```groovy
// settings.gradle (Groovy)
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

```kotlin
// settings.gradle.kts (Kotlin DSL)
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

```groovy
// app/build.gradle (Groovy)
dependencies {
    // Artifact name = the GitHub repo name (perf_analyzer). Any other name
    // makes JitPack answer 401 Unauthorized, not 404.
    implementation 'com.github.parityfix:perf_analyzer:v1.0.2'
}
```

```kotlin
// app/build.gradle.kts (Kotlin DSL)
dependencies {
    implementation("com.github.parityfix:perf_analyzer:v1.0.2")
}
```

## Quick start

### Kotlin
```kotlin
import android.app.Application
import dev.perfanalyzer.PerfAnalyzer

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfAnalyzer.start(this) // ws://localhost:7001 by default — see PerfConfig
    }
}
```

### Java
```java
import android.app.Application;
import dev.perfanalyzer.PerfAnalyzer;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PerfAnalyzer.start(this); // ws://localhost:7001 by default — see PerfConfig
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
