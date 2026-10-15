# Building Call Log Vault

Call Log Vault is a native Android app written in Kotlin against the
Android framework APIs only: no Gradle, no AndroidX, no third party
libraries. The build is a small shell script that drives the standard
SDK tools directly (aapt2, kotlinc, d8, apksigner), which keeps the
whole pipeline easy to read and audit.

## Prerequisites

- JDK 17 (java on PATH, or JAVA_HOME pointing at it)
- Android SDK with platform android-34 and build-tools 34.0.0
  (ANDROID_HOME pointing at the SDK, default ~/Android/Sdk)
- Kotlin compiler 1.9.x (kotlinc on PATH, or KOTLINC pointing at it;
  kotlin-stdlib.jar is taken from the compiler's lib folder unless
  KOTLIN_STDLIB points somewhere else)
- A keystore of your own for signing (see below)

## Running the JVM tests

The app's pure logic (number handling, backup formats, restore and
throttle rules, display strings) lives in VaultLogic.kt and is covered
by a plain JVM test suite, no emulator needed:

```
./tests/run-tests.sh
```

The script compiles VaultLogic.kt together with tests/VaultLogicTest.kt
and runs it, printing a check count and ALL_VAULT_TESTS_PASSED on
success. It expects the JDK and Kotlin compiler in the locations set
at the top of the script; adjust those three path variables if your
toolchain lives elsewhere.

## Building the APK

Signing inputs come from the environment, so the script carries no
machine specific paths and no keystore of its own:

```
export CLV_KEYSTORE=/path/to/your.keystore
export CLV_STORE_PW_FILE=/path/to/password-file.txt   # or: export CLV_STORE_PASSWORD=...
export CLV_KEY_ALIAS=release                          # optional, default "release"
VERSION_CODE=1 VERSION_NAME=1.0.0 ./build-manual.sh
```

The signed APK is written to the project root as
call-log-vault-v<VERSION_NAME>.apk, and the script prints its SHA-256
at the end. If you do not have a keystore yet, create one with the
keytool that ships with the JDK:

```
keytool -genkeypair -keystore my.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

## A note on official releases

Official releases are signed with the maintainer's key and published
together with their SHA-256 hashes. Android only accepts an update to
an installed app when the update is signed with the same key as the
installed build, so an APK you sign with your own keystore will not
install over an official release (and the other way round): uninstall
first, or treat your build as its own app. Your call data is safe
either way if you keep a backup ZIP (in the app: Backup to ZIP, or the
auto backup file) and restore from it afterwards.
