# SiftAlpha Studio

SiftAlpha Studio is an Android workspace for managing, editing, preparing, and running Python projects and local automation services. It brings project files, runtime state, dependency preparation, logs, and recovery actions into one mobile-first workflow.

The current public snapshot is **v0.7.0-alpha15** (`versionCode 77`). The Android application id is `com.siftalpha.studio`.

## Current capabilities

- SAF-backed project directories, including existing Acode project folders
- Python file editing with file-tree operations, search, recent files, and unsaved-change protection
- `.py`, ZIP, and GitHub project import
- Per-project runtime preparation, status, logs, start/stop, and cleanup
- Python and Node.js runtime planning, including local web endpoint discovery
- Runtime storage visibility and cleanup safeguards
- English, Simplified Chinese, Traditional Chinese, Japanese, and Korean resources

The alpha15 snapshot is an active development release. Automated checks are part of the public baseline; real-device and real-user acceptance should be evaluated separately for each change.

## Android and runtime requirements

The build targets Android API 36, uses JDK 17, Gradle 9.3.1, Android Gradle Plugin 9.1.1, and Build Tools 36.0.0. The minimum Android API is 26.

Runtime execution uses a user-installed Termux environment through the public `RUN_COMMAND` contract, with `proot-distro` and an Ubuntu environment providing Python, pip, Git, tmux, and project-specific tools. Termux must allow external app commands. Project source remains in Android shared storage; runtime state and credentials stay in the runtime environment.

## Build locally

Install JDK 17, Android SDK platform/API 36, Build Tools 36.0.0, and Gradle 9.3.1. From the repository root run:

```text
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug
```

`assembleDebug` also runs the JVM unit suite. The resulting APK is `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions artifacts

Pull requests and pushes to `main` run localization checks, exact-head/unit checks, and an ordinary debug APK build. The APK workflow uploads an artifact named `SiftAlpha-Studio-debug-<commit-sha>` for each successful run. Open the repository's **Actions**, select a successful **Android Debug APK** run, and download its artifact.

Public CI uses the Android/Gradle default debug signing behavior. The CI debug APK is for testing and **may not upgrade over a privately signed SiftAlpha Studio build**. Stable release signing is intentionally not configured in this public repository; no signing private key or signing payload belongs in source, workflows, artifacts, or logs.

## Open-source status

This repository is the public source distribution of SiftAlpha Studio and is licensed under Apache-2.0. See [SECURITY.md](SECURITY.md) for disclosure and signing policy, and [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution and validation requirements.
