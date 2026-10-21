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
    // From v1.0.3 the repo has two modules, so JitPack names the SDK
    // com.github.parityfix.perf_analyzer:perf_analyzer (note the dot). The old
    // com.github.parityfix:perf_analyzer now means "every module", including the
    // Gradle plugin jar — don't use it. A misspelled name gets 401 Unauthorized.
    implementation 'com.github.parityfix.perf_analyzer:perf_analyzer:v1.0.5'
}
```

```kotlin
// app/build.gradle.kts (Kotlin DSL)
dependencies {
    implementation("com.github.parityfix.perf_analyzer:perf_analyzer:v1.0.5")
}
```

### API calls with no code: the `dev.perfanalyzer` Gradle plugin

Android gives a library no way to reach the `OkHttpClient` your app builds, so
`PerfAnalyzer.start()` alone can't see API calls. The plugin closes that gap at
build time (the same approach Firebase Performance uses): in the **debug**
build it rewrites every `OkHttpClient.Builder().build()` and `OkHttpClient()`,
in your code and in libraries (Retrofit, Coil, Glide's OkHttp integration), so
each client gets `PerfInterceptor` exactly once. Your source is never changed,
and release builds are not touched.

Gradle looks plugins up by id, and JitPack only serves `com.github.*`
coordinates, so `settings.gradle` maps the id to the plugin module once:

```groovy
// settings.gradle (Groovy)
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'dev.perfanalyzer') {
                useModule("com.github.parityfix.perf_analyzer:perf-plugin:${requested.version}")
            }
        }
    }
}
```

```kotlin
// settings.gradle.kts (Kotlin DSL)
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.perfanalyzer") {
                useModule("com.github.parityfix.perf_analyzer:perf-plugin:${requested.version}")
            }
        }
    }
}
```

```groovy
// app/build.gradle (Groovy)
plugins {
    id 'com.android.application'
    id 'dev.perfanalyzer' version 'v1.0.5'
}
```

```kotlin
// app/build.gradle.kts (Kotlin DSL)
plugins {
    id("com.android.application")
    id("dev.perfanalyzer") version "v1.0.5"
}
```

**Older `apply plugin:` style** (a root `buildscript { }` block)? Skip the
`settings.gradle` mapping above — it only applies to `plugins { id ... }` —
and put the plugin on the buildscript classpath instead:

```groovy
// build.gradle (project root)
buildscript {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }   // must be here, not only in settings.gradle
    }
    dependencies {
        // ...
        classpath 'com.github.parityfix.perf_analyzer:perf-plugin:v1.0.5'
    }
}

// app/build.gradle
apply plugin: 'com.android.application'
apply plugin: 'dev.perfanalyzer'
```

Mixing the two (the `settings.gradle` mapping plus `apply plugin:`) fails with
"Plugin with id 'dev.perfanalyzer' not found".

The plugin also adds the matching SDK as a `debugImplementation` dependency
(the rewritten code calls into it), so the SDK line above is only needed if
you call `PerfAnalyzer` from code that's compiled into release builds as well.
You still call `PerfAnalyzer.start(this)` as below — the plugin only adds the
interceptor. Requires Android Gradle Plugin 8.1 or newer.

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

- **Frame timing** — via Android's FrameMetrics API on API 24+ (the same data
  Android Studio and JankStats use), split the way Studio splits it:
  **main thread** (waiting + input + animation + measure/layout + draw + sync)
  vs **RenderThread** (command issue + swap buffers). GPU time and the frame's
  real deadline (8.3ms on a 120Hz screen) are sent separately on API 31+.
  Below API 24, total frame duration only.
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

Easiest: apply the [`dev.perfanalyzer` Gradle plugin](#api-calls-with-no-code-the-devperfanalyzer-gradle-plugin)
and every OkHttp client is covered. Can't add a Gradle plugin? Add the
interceptor by hand — it's the same interceptor, and the plugin never adds it twice:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(PerfInterceptor())
    .build()
```

Each call reports URL, method, status, content type, response size and
duration (request start → response body fully read). Bodies are counted as
your app reads them, never buffered, so large downloads and streams are safe.

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
- The Gradle plugin covers OkHttp-based clients only (OkHttp, Retrofit, Coil,
  Glide's OkHttp integration, Ktor's OkHttp engine) — not Volley, raw
  `HttpURLConnection`, Cronet or Ktor CIO. It instruments the `debug` build
  type only.
- A call's event is sent when the app finishes reading (or closes) the
  response body. A body the app never reads or closes reports nothing — OkHttp
  requires closing it anyway.
- Debuggable builds run noticeably slower than release on Android (debug
  checks, less ahead-of-time compilation). Reports show a **debuggable build**
  badge so they aren't compared with Android Studio numbers by mistake.
  For release-like numbers, use a non-debuggable build marked profileable —
  the same kind Android Studio profiles:

  ```xml
  <!-- src/release/AndroidManifest.xml (or a dedicated benchmark build type) -->
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:tools="http://schemas.android.com/tools">
      <application>
          <profileable android:shell="true" tools:targetApi="29" />
      </application>
  </manifest>
  ```

  The SDK switches itself off in non-debuggable builds, so measure such a
  build with ParityFix's zero-code adb live monitor (`npm start` → Android
  live monitor) — frames, memory, CPU and crashes need no SDK.
- Jank is judged the way Android Studio judges it: a frame is janky when it
  misses the device's own frame deadline (8.3ms at 120Hz, 16.6ms at 60Hz,
  sent on API 31+), not a fixed 16.6ms.

## Releasing (maintainers)

Push a git tag (e.g. `v1.0.5`); JitPack builds both modules from it:

- SDK: `com.github.parityfix.perf_analyzer:perf_analyzer:<tag>` (`com.github.parityfix:perf_analyzer:<tag>` is JitPack's all-modules bundle — it drags the plugin jar into apps)
- Gradle plugin: `com.github.parityfix.perf_analyzer:perf-plugin:<tag>`

Check both resolve on [jitpack.io](https://jitpack.io/#parityfix/perf_analyzer)
before announcing a release. Run `./gradlew :perf-plugin:test` before tagging —
it covers the bytecode rewrite.
