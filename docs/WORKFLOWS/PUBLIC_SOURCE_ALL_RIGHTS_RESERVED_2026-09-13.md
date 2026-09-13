# Public Source / All Rights Reserved Workflow

Date: 2026-09-13

Repository: `kuashan/siftalpha-studio`

## Authorization position

The repository remains Public so that its source can be developed, reviewed, and built through GitHub Actions/CI. Public visibility does not mean the project is open source and does not grant permission to use, copy, modify, redistribute, sublicense, sell, or integrate the project into another product.

The project is All Rights Reserved and has no open-source license. The only exceptions are rights required by GitHub Terms or GitHub platform features, including the ability to view and fork a Public repository on GitHub. Third-party dependencies remain subject to their own licenses and notices.

## Repository changes

- Removed the root Apache-2.0 `LICENSE` file.
- Added `COPYRIGHT.md` with the project copyright and source-availability position.
- Updated `README.md`, `CONTRIBUTING.md`, and `SECURITY.md` to remove licensing ambiguity and preserve the CI, disclosure, and signing policies.
- Kept GitHub Actions workflows and application/runtime configuration unchanged.
- Kept `THIRD_PARTY_NOTICES.md`; it documents upstream dependency notices and does not license SiftAlpha Studio.

## Validation

- Confirm repository visibility remains Public.
- Confirm the existing GitHub Actions workflows remain present and enabled.
- Confirm the Android application id and version remain unchanged: `com.siftalpha.studio`, `0.7.0-alpha15`, `versionCode 77`.
- Run `git grep -ni` for Apache-2.0, Apache License, open source, MIT, and GPL authorization wording; review third-party notices and source comments separately from project licensing.
- Run `git diff --check`.
