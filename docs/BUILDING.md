# Building

## Toolchain

The project pins its build versions in `gradle/libs.versions.toml` and CI currently uses:

- JDK 17;
- Gradle 9.6.0;
- Android Gradle Plugin 9.4.0;
- compile/target SDK 37;
- minimum SDK 26.

AGP 9 uses built-in Kotlin for Android modules. Pure JVM modules use the explicit Kotlin JVM plugin.

## CI

GitHub Actions installs the pinned Gradle version and Android SDK and runs:

```bash
gradle test lint assembleDebug --stacktrace
```

A successful workflow publishes `article-navigator-debug` as an APK artifact.

## Local build

Until the Gradle Wrapper binary is committed, install Gradle 9.6.0 and JDK 17, configure Android SDK 37, then run the same command as CI.

Adding the wrapper is a build-tooling task only and does not affect project architecture.
