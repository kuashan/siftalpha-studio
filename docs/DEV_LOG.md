# SiftAlpha Studio Development Log（总开发日志）

This file is the chronological summary（按时间汇总） for project decisions, implementation
milestones, cloud validation, artifact delivery, and real-device acceptance. Detailed technical
notes remain in dated files under `docs/DEV_NOTES/`; repeatable procedures remain under
`docs/WORKFLOWS/`.

## Recording rules（记录规则）

Every meaningful entry should include: date, scope, reason or decision, branch/PR, version, code
changes, validation evidence, artifact information, device acceptance, and next action. Use
`PASS`, `FAIL`, `PENDING`, or `NOT TESTED` instead of vague language. Never record secret values.

## 2026-09-14 — W2 Runtime Lifecycle Reliability（运行生命周期可靠性）

- Branch/PR（分支/合并请求）: codex/w1c-config-wizard, PR #5, Draft（草稿）, open and
  unmerged（开放且未合并）.
- Candidate（候选版本）: 0.8.0-alpha12, Android versionCode 88, application id（应用标识）
  com.siftalpha.studio.
- Code candidate commit（代码候选提交）: f7466e370bbfe915de98f5adb9726581bd2d8010.
- Scope（范围）: project-scoped lifecycle resolution（项目级生命周期解析）, foreground status
  recovery（前台状态恢复）, failure-reason persistence（失败原因持久化）, duplicate-start
  protection（重复启动保护）, runtime-discovery persistence（运行配置发现持久化）, and
  secret redaction（秘密脱敏）.
- Changed areas（变更区域）: Runtime Center（运行中心） state/action policy（状态/动作策略）,
  Android Keystore-backed configuration metadata（Android Keystore 保护配置元数据）, localized
  lifecycle labels（本地化生命周期文案）, and JVM unit tests（JVM 单元测试）.
- Safety boundaries（安全边界）: no secret value, password, token, keystore, or signing payload was
  added to source, logs, or documentation; existing Termux/PRoot protocol（Termux/PRoot 协议）,
  process ownership, web URL validation, cleanup rules, and supported runtime scope remain intact.
- Cloud validation（云端验证）: three public checks for this candidate are NOT RUN（未运行） at
  documentation time. No local Gradle/JDK/Android SDK（本地 Gradle/JDK/Android SDK） was used.
- Trigger observation（触发观察）: the branch synchronization event is still pending; historical green
  runs are not accepted as W2 evidence.
- Trusted Signed Debug APK（稳定签名调试包）: NOT REQUESTED（未请求） until all three checks
  pass on this exact commit.
- Device acceptance（真机验收）: NOT TESTED（未测试）. Green CI（绿色持续集成） is not device
  acceptance.
- Next action（下一步）: run Android Debug APK（普通调试 APK）、Exact Head Unit（精确头部单元）、
  and Localization（本地化） checks on the same final commit; then add the exact /stable-debug
  PR comment（合并请求评论） if needed, verify the protected APK, and wait for separate device
  acceptance.

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
- Code candidate commit: `23ccb2161ec8039c01877e0c46d76dd99ab93426`.
- Validation status: local static checks passed; all three cloud checks passed:
  - [Android Debug APK 34808995008](https://github.com/kuashan/siftalpha-studio/actions/runs/34808995008)
  - [Exact Head Unit 34808994996](https://github.com/kuashan/siftalpha-studio/actions/runs/34808994996)
  - [Localization 34808994993](https://github.com/kuashan/siftalpha-studio/actions/runs/34808994993)
- Test artifact: `SiftAlpha-Studio-v0.8.0-alpha10-code86-debug.zip`, containing the ordinary
  Debug APK. APK SHA-256:
  `e8b953944bb69500ab1aed8ac8a8c33590a79366048574d26401e2e3c61b1df6`.
- Device acceptance: `PENDING`.
- Next action: the alpha10 Debug APK has been provided; record the user's device result after
  installation and testing.

## 2026-09-14 — Launcher icon repair（启动图标修复）

- Scope: replace the unreliable generated icon path with the approved SiftAlpha Studio artwork,
  wire the manifest and adaptive icon resources directly, and add a launcher-resource validator.
- User acceptance: the user confirmed that the app icon displays normally on the device.
- Detailed note: [W1A_ALPHA7_ICON_REPAIR_2026-09-14.md](DEV_NOTES/W1A_ALPHA7_ICON_REPAIR_2026-09-14.md).

## 2026-09-14 — Test-package-first workflow（先测试包流程）

- Historical decision: every feature update is delivered first as a test package for real-device
  testing. The package type was later superseded by the stable-signed device-test decision below;
  the test-first and user-acceptance gates remain active.
- Decision: a single Draft PR may carry multiple features while the user tests them; do not merge
  or produce a formal release package automatically after each individual test package.
- Decision: keep the repository Public because the project intentionally uses free GitHub Actions
  cloud builds.
- Related records: [POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md](WORKFLOWS/POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md)
  and [STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md](DEV_NOTES/STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md).

## 2026-09-14 — Stable-signed device test package required（稳定签名设备测试包要求）

- User decision: from the next device-acceptance candidate onward, the default test package must be
  a Trusted Signed Debug APK（稳定签名调试包）. It must use the stable development certificate so
  the user can install updates over the existing stable-signed app without repeatedly uninstalling.
- Reason: alpha10 was an ordinary public Debug APK（普通公开调试包）. Its cloud-run signer can
  differ from the signer of the previously installed package, which caused Android's signature
  mismatch and blocked upgrade-in-place（覆盖安装）.
- Ordinary public pull-request Debug artifacts remain available for fork-safe CI（分支安全持续集成）
  and fresh-install checks, but they are not upgradeable device packages and must not be presented
  as such.
- Security boundary: the protected candidate-signing workflow（受保护的候选版本签名流程） signs
  only the exact public-validated APK after the repository owner authorizes `/stable-debug` on the PR.
  Signing secrets may be used only in the isolated signer and are never exposed to untrusted
  pull-request build/test steps.
- One-time migration: if the device still has an ordinary alpha10/previous package installed, the
  user may need to uninstall it once before installing the first stable-signed test package. After
  that baseline, every installable candidate must retain `com.siftalpha.studio`, use a higher
  `versionCode`, and keep the stable signer.
- Current scope: the protected candidate-signing workflow is implemented on `main`; the PR #5
  documentation records the direct trigger. No code version bump was made by this workflow change.
  Alpha10 device acceptance remains `PENDING` until the stable-signed candidate is tested.
- Next action: trigger `/stable-debug` on PR #5 after its public checks are successful, then provide
  the stable-signed alpha10 candidate for the user's real-device acceptance before deciding whether
  to merge.

## 2026-09-14 — About tagline update（关于页面文案更新）

- Branch/PR: `codex/w1c-config-wizard`, PR #5, Draft and unmerged.
- User request: add `这个世界很美。` to the existing About（关于） software-introduction text.
- Implementation: updated the existing `settings_about_summary` resource that is rendered by
  `SettingsScreen`; no new unused string or layout path was introduced.
- Candidate bump: `0.8.0-alpha11`, `versionCode 87`, while keeping application id
  `com.siftalpha.studio` and the stable signing lineage unchanged.
- Cloud validation: all three public GitHub Actions checks passed for commit
  `e5b7dcf198ee2a05dd5b483ea1a9423bdf917d24`:
  - Android Debug APK: [34815147559](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147559)
  - Exact Head Unit: [34815147586](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147586)
  - Localization: [34815147620](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147620)
- Protected signing: [run 34815293599](https://github.com/kuashan/siftalpha-studio/actions/runs/34815293599)
  passed. The delivered APK SHA-256 is
  `d2bad4b7d7b7e8d66c61eb211aa1e49a210d481c6eb2a47d506c173fa5308d03`.
- Device acceptance: `PENDING`; after the public checks, produce a new Trusted Signed Debug APK
  for real-device testing. Do not merge automatically.

## 2026-09-14 — Direct stable candidate generation verified（直接生成稳定候选包已验证）

- Scope: remove the need for the user to manually start a privileged GitHub Actions workflow while
  preserving the signing boundary for an open pull request.
- Implementation: the protected `Trusted Signed Candidate APK` workflow is available on `main` and
  accepts the repository owner's exact `/stable-debug` PR comment. It resolves the exact PR head,
  requires the successful public validation workflows, downloads their APK without executing PR code,
  and signs only that APK with the protected stable certificate.
- Source candidate: PR #5, `codex/w1c-config-wizard`, commit
  `8f822ba60ea213d2b6658a017df5cb5ab17070c2`.
- Public validation: Android Debug APK run
  [34812853160](https://github.com/kuashan/siftalpha-studio/actions/runs/34812853160) passed;
  Exact Head Unit and Localization also passed.
- Protected signing: run
  [34813023478](https://github.com/kuashan/siftalpha-studio/actions/runs/34813023478) passed.
- Delivered candidate: `0.8.0-alpha10`, `versionCode 86`, application id
  `com.siftalpha.studio`, stable certificate SHA-256
  `1d96e9ce12c06e6ff0571cf8f82cf2461ac3b5261189ad06ab0747ce92f8192e`.
- APK SHA-256: `d1fe058d369e567f8b90e526a3a0d58ab4ef398c92355c9a354f66922a1a0fc2`.
- Device acceptance: `PENDING`; the user must install and test this stable-signed package. If the
  current installation was made with the old ordinary public Debug signer, a one-time uninstall is
  expected before establishing the stable-signed baseline.
- Next action: after the user confirms device acceptance, continue feature work in the Draft PR;
  do not merge automatically.

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
