# Contributing

Thanks for helping improve SiftAlpha Studio.

## Before opening a pull request

1. Keep changes focused and preserve the Android application id and current version unless the change explicitly requires a release update.
2. Keep all five locale resource sets aligned: English, Simplified Chinese, Traditional Chinese, Japanese, and Korean.
3. Run the localization validators:

   ```text
   python3 tools/validate_localization_resources.py
   python3 tools/validate_localized_ui_sources.py
   ```

4. Run the JVM unit suite and debug build:

   ```text
   gradle --no-daemon :app:testDebugUnitTest
   gradle --no-daemon :app:assembleDebug
   ```

5. Run `git diff --check` and describe any real-device Runtime or Termux/PRoot regression coverage in the pull request.

## Runtime regression expectations

Changes affecting project import, dependency preparation, Runtime selection, process lifecycle, logs, web discovery, storage cleanup, or secret injection should include focused tests and explain the safety boundary. Do not claim real-device acceptance from CI alone. When applicable, test both a normal project and a failure/recovery path.

## Pull requests

Pull requests run read-only, fork-safe checks and an ordinary debug build. Do not add secrets to a pull request and do not rely on repository secrets being available to forked pull requests. Keep generated files, local configuration, signing material, and credentials out of commits.
