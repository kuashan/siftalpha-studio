# Contributing

SiftAlpha Studio is a public source repository, but no open-source license is granted. Do not assume that public visibility authorizes code contributions or reuse.

## Issues and bug reports

Issues and bug reports are welcome. Do not include credentials, tokens, private keys, signing material, or other sensitive information in an issue; use [SECURITY.md](SECURITY.md) for vulnerability reports.

## Invited code contributions

Unsolicited code pull requests and code contributions are not accepted. Submit a code contribution only when the repository owner has explicitly invited it through GitHub. An invitation to contribute does not transfer or waive copyright; any additional permission or contribution terms must be agreed with the repository owner before work is submitted.

### Before opening an invited pull request

1. Keep changes focused and preserve the Android application id. If the change produces an installable candidate, increase `versionCode` over `main` and update `versionName`.
2. Keep all five locale resource sets aligned: English, Simplified Chinese, Traditional Chinese, Japanese, and Korean.
3. Run the localization validators:

   ```text
   python3 tools/validate_localization_resources.py
   python3 tools/validate_localized_ui_sources.py
   ```

4. Use the GitHub Actions checks as the build environment. A local Gradle/JDK/Android SDK setup is
   not required; review the Android Debug APK, Exact Head Unit, and Localization results on the
   pull request. For real-device acceptance, authorize the protected candidate workflow with the
   exact `/stable-debug` PR command, then use its Trusted Signed Debug APK（稳定签名调试包） when
   upgrade-in-place testing is required. The ordinary public pull-request APK is limited to CI and
   fresh-install testing because it may have a different signer.

5. Run `git diff --check` and describe any real-device Runtime or Termux/PRoot regression coverage in the pull request.

## Runtime regression expectations

Changes affecting project import, dependency preparation, Runtime selection, process lifecycle, logs, web discovery, storage cleanup, or secret injection should include focused tests and explain the safety boundary. Do not claim real-device acceptance from CI alone. When applicable, test both a normal project and a failure/recovery path.

## Pull requests

Only invited code pull requests should be opened. Pull requests run read-only, fork-safe checks and an ordinary debug build. Do not add secrets to a pull request and do not rely on repository secrets being available to forked pull requests. Keep generated files, local configuration, signing material, and credentials out of commits.
