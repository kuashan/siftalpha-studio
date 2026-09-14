# SiftAlpha Studio Development Log（总开发日志）

This file is the chronological summary（按时间汇总） for project decisions, implementation
milestones, cloud validation, artifact delivery, and real-device acceptance. Detailed technical
notes remain in dated files under `docs/DEV_NOTES/`; repeatable procedures remain under
`docs/WORKFLOWS/`.

## Recording rules（记录规则）

Every meaningful entry should include: date, scope, reason or decision, branch/PR, version, code
changes, validation evidence, artifact information, device acceptance, and next action. Use
`PASS`, `FAIL`, `PENDING`, or `NOT TESTED` instead of vague language. Never record secret values.

## 2026-09-14 — Persistent project handoff（持久项目交接）

- Decision（决定）: use the repository as the durable source of truth（唯一可信记录） so a new
  conversation can recover project rules without relying on chat memory.
- Added: root `AGENTS.md`, `docs/PROJECT_CONTEXT.md`, and this chronological log.
- The context file records the current candidate, active PR, cloud validation, package checksums,
  user requirements, W1C acceptance contract, and links to detailed historical notes.
- Sensitive-data boundary: secret names and workflow status may be recorded; secret values,
  keystores, passwords, and tokens may not be recorded.
- Next action: keep these files updated in the same change set as meaningful implementation or
  workflow changes.

## 2026-09-14 — W1C configuration discovery and retry flow

- Branch/PR: `codex/w1c-config-wizard`, PR #5, Draft and unmerged.
- Candidate: `0.8.0-alpha9`, `versionCode 85`.
- User requirement at the time: after environment preparation, `Run` must be available; the first
  run should reveal runtime-required configuration; required values cannot be skipped; saving
  configuration automatically retries the run.
- Implementation: static Python inspection distinguishes required direct environment access from
  optional getters; runtime errors can promote missing names to required findings; the sequential
  wizard saves values through the protected store and automatically retries after a saved change.
- Validation: ProjectActionPolicy and RuntimeConfigurationDiagnostic unit coverage, localization
  validators, localized UI source validator, version-code validator, Exact Head Unit, Localization,
  and Android Debug APK cloud workflows all passed.
- Cloud evidence:
  - Android Debug APK: [34806983723](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983723)
  - Exact Head Unit: [34806983715](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983715)
  - Localization: [34806983732](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983732)
- Test artifact: `SiftAlpha-Studio-v0.8.0-alpha9-code85-debug.zip`, containing the ordinary
  Debug APK. APK SHA-256:
  `339ced7e2cce565b449476b6f0356775e8a016b2583d00b3f75d13578ad27cc2`.
- Device acceptance: `PENDING`; the user must test the flow on a real device.
- Next action: record the user's result before changing the acceptance status or deciding on merge.
- Detailed note: [V0_8_W1C_CONFIGURATION_WIZARD_2026-09-14.md](DEV_NOTES/V0_8_W1C_CONFIGURATION_WIZARD_2026-09-14.md).

## 2026-09-14 — Configuration button and run-as-detection correction（配置按钮与运行检测修正）

- User correction: Configuration must not be gated by runtime discovery. It should be available
  before and after environment preparation; Run is the detection operation.
- Required behavior: a run may report necessary or non-necessary configuration. Non-necessary
  values can be skipped without blocking the project. Completing the configuration action triggers
  one more run when the environment is ready, so the project is checked again.
- Implementation: the action policy now treats Configuration as an independent project operation;
  the wizard completion callback no longer depends on whether a value was saved or runtime
  discovery had already happened; the retry is guarded against an unprepared environment, an
  active process, and another pending operation.
- Candidate bump: `0.8.0-alpha10`, `versionCode 86`.
- Validation status: local static checks and cloud build `PENDING` until the new candidate runs
  through GitHub Actions. A new Debug APK is required for real-device acceptance.
- Device acceptance: `PENDING`.
- Next action: provide the alpha10 Debug APK, then record the user's device result.

## 2026-09-14 — Launcher icon repair（启动图标修复）

- Scope: replace the unreliable generated icon path with the approved SiftAlpha Studio artwork,
  wire the manifest and adaptive icon resources directly, and add a launcher-resource validator.
- User acceptance: the user confirmed that the app icon displays normally on the device.
- Detailed note: [W1A_ALPHA7_ICON_REPAIR_2026-09-14.md](DEV_NOTES/W1A_ALPHA7_ICON_REPAIR_2026-09-14.md).

## 2026-09-14 — Test-package-first workflow（先测试包流程）

- Decision: every feature update is delivered first as an ordinary Debug APK for real-device
  testing. The user may uninstall the previous formal/trusted installation when the ordinary Debug
  signer differs.
- Decision: a single Draft PR may carry multiple features while the user tests them; do not merge
  or produce a formal release package automatically after each individual test package.
- Decision: keep the repository Public because the project intentionally uses free GitHub Actions
  cloud builds.
- Related records: [POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md](WORKFLOWS/POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md)
  and [STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md](DEV_NOTES/STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md).

## 2026-09-13 — Stable signing and upgrade continuity（稳定签名与覆盖安装连续性）

- Decision: ordinary public PR Debug APKs and protected Trusted Signed Debug APKs are separate
  products with different signing purposes.
- The stable certificate fingerprint and secret-name boundary are documented; no keystore, private
  key, password, or encoded signing payload is stored in the repository.
- Detailed note: [STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md](DEV_NOTES/STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md).

## Historical records（历史记录）

- [V0_8_W1A_UI_FOUNDATION_2026-09-13.md](DEV_NOTES/V0_8_W1A_UI_FOUNDATION_2026-09-13.md) records the
  initial v0.8 UI foundation, browser preference, localization, and the earlier icon acceptance
  failure that led to the later repair.
- [PUBLIC_REPOSITORY_BOOTSTRAP.md](WORKFLOWS/PUBLIC_REPOSITORY_BOOTSTRAP.md) records the public CI
  bootstrap and its signing boundary.
