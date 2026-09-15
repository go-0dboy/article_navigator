# Building

## Toolchain

The project pins its build versions in `gradle/libs.versions.toml` and CI currently uses:

- JDK 17;
- Gradle 9.6.0;
- Android Gradle Plugin 9.4.0;
- compile/target SDK 36 (stable Android 16);
- SDK Build Tools 36.0.0;
- minimum SDK 26.

The AndroidX/Compose dependency line is intentionally pinned to versions compatible with stable API 36. Moving to dependencies that require a preview/newer compile SDK is an explicit build decision, not an incidental dependency update.

AGP 9 uses built-in Kotlin for Android modules. Pure JVM modules use the explicit Kotlin JVM plugin.

## CI quality gates

GitHub Actions runs the gates independently so failures are attributable:

```bash
gradle test --stacktrace
gradle lint --stacktrace
gradle assembleDebug --stacktrace
```

A successful workflow publishes `article-navigator-debug` as an installable APK artifact.

No development phase is complete while any of these gates is red.

## Local build

Until the Gradle Wrapper binary is committed, install Gradle 9.6.0 and JDK 17, configure Android SDK 36 with Build Tools 36.0.0, then run the same commands as CI.

Committing the official Gradle Wrapper remains a build-tooling task. GitHub CI is already reproducible because the workflow provisions the exact Gradle version explicitly; the wrapper will remove the separate local Gradle installation requirement.
