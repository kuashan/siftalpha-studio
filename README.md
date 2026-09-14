# SiftAlpha Studio

SiftAlpha Studio is an Android workspace for managing, editing, preparing, and running Python projects and local automation services. It brings project files, runtime state, dependency preparation, logs, and recovery actions into one mobile-first workflow.

The current public candidate is **v0.8.0-alpha10** (`versionCode 86`). The Android application id is `com.siftalpha.studio`.

## Current capabilities

- SAF-backed project directories, including existing Acode project folders
- Python file editing with file-tree operations, search, recent files, and unsaved-change protection
- `.py`, ZIP, and GitHub project import
- Per-project runtime preparation, status, logs, start/stop, and cleanup
- Python and Node.js runtime planning, including local web endpoint discovery
- Runtime storage visibility and cleanup safeguards
- English, Simplified Chinese, Traditional Chinese, Japanese, and Korean resources

The alpha10 candidate is an active development release. Automated checks are part of the public baseline; real-device and real-user acceptance should be evaluated separately for each change.

## Project continuity and records

Start a new development session by reading [AGENTS.md](AGENTS.md), then
[docs/PROJECT_CONTEXT.md](docs/PROJECT_CONTEXT.md), and the newest entry in
[docs/DEV_LOG.md](docs/DEV_LOG.md). These files are the canonical project handoff
（项目交接记录） for durable requirements, current status, decisions, validation evidence,
artifact（构建产物） details, and real-device acceptance. Detailed feature notes remain in
[`docs/DEV_NOTES/`](docs/DEV_NOTES/); repeatable operational procedures remain in
[`docs/WORKFLOWS/`](docs/WORKFLOWS/).

## Android and runtime requirements

The build targets Android API 36, uses JDK 17, Gradle 9.3.1, Android Gradle Plugin 9.1.1, and Build Tools 36.0.0. The minimum Android API is 26.

Runtime execution uses a user-installed Termux environment through the public `RUN_COMMAND` contract, with `proot-distro` and an Ubuntu environment providing Python, pip, Git, tmux, and project-specific tools. Termux must allow external app commands. Project source remains in Android shared storage; runtime state and credentials stay in the runtime environment.

## Cloud-only build policy

This public repository intentionally uses GitHub Actions as the supported build and test
environment. A local Gradle, JDK, Android Studio, or Android SDK installation is not required for
the project workflow. Each cloud runner provisions the pinned JDK 17, Android SDK/API 36,
Build Tools 36.0.0, and Gradle 9.3.1 versions for the duration of the job, then discards the
temporary environment.

Every installable update must increase Android `versionCode` over the previous `main` build and
update `versionName` to match the candidate. GitHub Actions checks this on pull requests.

The Android application id and signing certificate also matter for upgrade-in-place. The
post-merge Trusted Signed Debug artifact is the package intended to overwrite an existing trusted
development install without clearing app data. A public pull-request Debug artifact is for CI and
device testing and may have a different debug signer.

The project default for real-device acceptance is now the Trusted Signed Debug APK（稳定签名调试
包）, including upgrade-in-place testing. The ordinary public pull-request Debug APK（普通公开调试
包） remains useful for fork-safe CI（分支安全持续集成） and fresh-install checks only. An unmerged
candidate becomes upgradeable only after the protected candidate-signing workflow（受保护的候选版本
签名流程） signs the exact public-validated commit; signing secrets must never enter untrusted
pull-request build or test steps.

## GitHub Actions artifacts

Pull requests and pushes to `main` run localization checks, exact-head/unit checks, and an ordinary debug APK build. The ordinary APK workflow uploads an artifact named `SiftAlpha-Studio-debug-<commit-sha>` for each successful run. Open the repository's **Actions**, select a successful **Android Debug APK** run, and download its artifact.

Ordinary public CI uses the Android/Gradle default debug signing behavior and never receives trusted signing secrets. Its APK is for tests and fresh-install checks; it **may not upgrade over a stable-development-signed SiftAlpha Studio build**.

The protected candidate workflow can be triggered directly by the repository owner posting exactly
`/stable-debug` on an open same-repository pull request. It locates the successful public Android
artifact for that exact commit, verifies it, re-signs the APK with the stable certificate, and uploads
the trusted candidate. It never checks out or executes pull-request source while signing secrets are
available.

The separate **Trusted Signed Debug APK** workflow runs automatically for pushes to the repository's `main` branch and can also be manually dispatched. It restores the protected development keystore to a runner-temporary path, builds the same debug variant, verifies the certificate fingerprint and APK metadata, uploads `SiftAlpha-Studio-trusted-debug-<version>-<short-sha>`, and removes the temporary keystore. Only this trusted artifact is intended for `adb install -r` upgrade-in-place over an existing stable development install. It does not uninstall the app or clear its data.

Pushing a version tag matching `v*` automatically builds a signed release variant, verifies its signer and package, uploads an Actions artifact, and publishes the APK to the matching GitHub Release. The release workflow uses the stable signing configuration and creates a public release asset; do not create a `v*` tag until that version is intended for distribution.

The stable signer is not stored in this repository. No signing private key, password, keystore, or base64 signing payload belongs in source, workflows, artifacts, or logs.

## Source availability / License

This is a public source repository for SiftAlpha Studio Android development and CI. The source is public primarily for development transparency, review, and GitHub Actions/CI/build distribution.

**Public source. All Rights Reserved. No open-source license.**

Public visibility does not grant permission to use, copy, modify, redistribute, sublicense, sell, or integrate this project into another product. Except for rights required by GitHub Terms or GitHub platform features, any additional permission must be obtained from the repository owner through GitHub. See [COPYRIGHT.md](COPYRIGHT.md). Third-party dependencies remain subject to their own licenses as described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

See [SECURITY.md](SECURITY.md) for disclosure and signing policy, and [CONTRIBUTING.md](CONTRIBUTING.md) for contribution and validation requirements.
