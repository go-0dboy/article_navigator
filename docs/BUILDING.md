# Building

## Toolchain

The project pins its build versions in `gradle/libs.versions.toml` and CI currently uses:

- JDK 17;
- Gradle 9.6.0;
- Android Gradle Plugin 9.4.0;
- compile/target SDK 36 (stable Android 16);
- SDK Build Tools 36.0.0;
- minimum SDK 26.

AGP 9.4 supports newer preview API levels, but the project intentionally targets the latest stable Android SDK for reproducible CI and release builds. Preview SDK adoption must be an explicit architecture/build decision rather than an incidental dependency update.

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

Committing the Gradle Wrapper is part of the build-foundation work so a fresh clone can eventually build with `./gradlew` without a separately installed Gradle distribution.
