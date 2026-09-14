# Security Policy

## Scope

This policy covers the SiftAlpha Studio source code and the public GitHub Actions workflows in this repository.

## Source rights

This policy does not grant a software license. Public source visibility is provided for development transparency, review, and GitHub Actions/CI/build distribution; it does not authorize use, copying, modification, redistribution, sublicensing, sale, or integration into another product. Except for rights required by GitHub Terms or GitHub platform features, request any additional permission from the repository owner through GitHub. Third-party dependencies remain subject to their own licenses.

## Reporting a vulnerability

Please do not publish credentials, tokens, private keys, signing material, or a complete exploit in a public issue. If private vulnerability reporting is enabled for this repository, use that channel. Otherwise, open a minimal issue requesting a private contact method and include only enough information to establish the affected area.

Reports are triaged in good faith. Please include the affected version or commit, a concise impact description, reproduction steps that do not disclose secrets, and any suggested mitigation.

## Secret policy

- Never commit `.env` files, credentials, tokens, private keys, certificates, keystores, or base64-encoded secret payloads.
- Do not place secrets in workflow YAML, build output, test fixtures, screenshots, or logs.
- Runtime credentials belong in the user's private Termux/Ubuntu environment and must not be copied into shared project storage.
- If a secret is exposed, revoke or rotate it immediately and then remove the exposure from every distribution path.

## Pull requests, forks, and signing

Workflows triggered by `pull_request` are designed to use no repository secrets. They run read-only checks and ordinary debug builds against the exact candidate commit. Forks must be treated as untrusted code.

The ordinary public CI debug artifact is for testing only and may not upgrade over a stable-development-signed build. It never references trusted signing secrets.

The separate `Trusted Signed Debug APK` workflow runs only for pushes to, or manual dispatches on, `kuashan/siftalpha-studio`'s `main` branch. It has `contents: read` permissions, does not run for pull requests or forks, restores the keystore only to a runner-temporary file, verifies the expected certificate fingerprint before upload, and removes that file in an `always()` cleanup step. The trusted artifact is the only public-CI artifact intended for upgrade-in-place testing.

The project policy now requires the Trusted Signed Debug APK（稳定签名调试包） as the default package
for real-device feature acceptance and in-place upgrade testing. An unmerged candidate may use that
signer only through an isolated protected candidate-signing workflow（受保护的候选版本签名流程）
that signs the exact public-validated commit. Until that workflow exists, the ordinary pull-request
artifact must be treated as non-upgradeable, even when its public checks pass.

The stable development signer exists only in protected GitHub Actions Secrets and a controlled private/local source. The workflow consumes these secret names only: `SIFTALPHA_DEBUG_KEYSTORE_B64`, `SIFTALPHA_DEBUG_STORE_PASSWORD`, `SIFTALPHA_DEBUG_KEY_ALIAS`, and `SIFTALPHA_DEBUG_KEY_PASSWORD`. Their values must never appear in source, workflow text, logs, artifacts, issues, pull requests, or comments. Fork pull requests never receive them.
