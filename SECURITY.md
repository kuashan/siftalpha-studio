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

The public CI debug artifact is for testing only and may not upgrade over a privately signed build. Stable signing keys are deliberately outside this repository and outside this migration. Do not add a stable signing workflow or secret reference until its threat model, branch protections, and release procedure are documented and reviewed.
