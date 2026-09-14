# SiftAlpha Studio Project Context（项目上下文）

This is the persistent project handoff（持久交接档案） for new conversations and new
contributors. It records the current operating contract and the latest known state. Update it when
the active branch, candidate, acceptance status, or next action changes.

Last updated: 2026-09-14

## Source-of-truth order（信息优先级）

1. The user's explicit decision in the current conversation.
2. The current checked-out branch, current PR（合并请求）, and current source code.
3. This file and the latest `docs/DEV_LOG.md` entry.
4. Detailed dated notes and older historical records.

Older notes with an earlier version or branch are historical evidence, not the current product
status. When a later decision changes an earlier rule, update this file and the relevant log rather
than silently relying on the old note.

## Project identity（项目身份）

- Repository（仓库）: `kuashan/siftalpha-studio`
- Product: SiftAlpha Studio
- Application id（应用标识）: `com.siftalpha.studio`
- Visibility（可见性）: Public（公开）. This is intentional because the project uses free GitHub
  Actions cloud builds（云端构建）.
- Main product direction（产品方向）: an Android workspace for importing, editing, preparing,
  running, observing, and recovering Python projects and local automation services.

## Current candidate（当前候选版本）

- Candidate: `0.8.0-alpha12`
- Android `versionCode`（版本代码）: `88`
- Android `versionName`（版本名称）: `0.8.0-alpha12`
- Feature branch（功能分支）: `codex/w1c-config-wizard`
- Active PR: #5, open, Draft（草稿）, unmerged（未合并）
- Previous alpha9 code candidate tested by cloud CI（云端持续集成）: commit
  `8615c68204633e0912cfac432e564ad59c1dfa31`
- Current feature slice（当前功能切片）: W1C Python configuration detection and sequential
  configuration wizard（Python 配置检测和顺序配置向导）, with the configuration-button and
  run-as-detection correction, plus the About（关于） tagline update in the alpha11 candidate.

### Current W2 alpha12 validation status（当前 W2 alpha12 验证状态）

- Code candidate commit（代码候选提交）: b6135d705441b8b3149f4249defe39e6e801a72f.
- W2 implementation is complete on the PR branch, but the three public cloud checks（云端检查）
  have not yet run for this candidate: NOT RUN（未运行）.
- Trusted Signed Debug APK（稳定签名调试包）: NOT PRODUCED（未生成）. The exact fallback comment
  was posted, but the candidate workflow failed safely because same-commit public checks are missing.
- Candidate workflow（候选流程）: run 34832205686 FAILED（失败）, missing Android Debug APK,
  Exact Head Unit, and Localization for the current PR head; no signing step or APK artifact was produced.
- Device acceptance（真机验收）: NOT TESTED（未测试）. CI（持续集成）结果不能代替真机验收.

### Latest alpha11 cloud validation（最近一次 alpha11 云端验证）

All three public checks for the alpha11 candidate passed:

- [Android Debug APK run 34815147559](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147559)
- [Exact Head Unit run 34815147586](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147586)
- [Localization run 34815147620](https://github.com/kuashan/siftalpha-studio/actions/runs/34815147620)

Code candidate commit（代码候选提交）: `e5b7dcf198ee2a05dd5b483ea1a9423bdf917d24`.

Protected candidate signing（受保护候选签名）:
[run 34815293599](https://github.com/kuashan/siftalpha-studio/actions/runs/34815293599), `SUCCESS`.
The direct APK is `0.8.0-alpha11`, `versionCode 87`, with APK SHA-256
`d2bad4b7d7b7e8d66c61eb211aa1e49a210d481c6eb2a47d506c173fa5308d03`.

Device acceptance（真机验收）: `PENDING`（待验收）.

### Latest alpha10 cloud validation（最近一次 alpha10 云端验证）

All three checks for the alpha10 code candidate completed successfully:

- [Android Debug APK run 34808995008](https://github.com/kuashan/siftalpha-studio/actions/runs/34808995008)
- [Exact Head Unit run 34808994996](https://github.com/kuashan/siftalpha-studio/actions/runs/34808994996)
- [Localization run 34808994993](https://github.com/kuashan/siftalpha-studio/actions/runs/34808994993)

Code candidate commit（代码候选提交）: `23ccb2161ec8039c01877e0c46d76dd99ab93426`.

Artifact（构建产物）: `SiftAlpha-Studio-debug-ec982c4a05d977753a1979d389961e2648a8d4cf`

Downloaded alpha10 test package（已下载 alpha10 测试包）: `SiftAlpha-Studio-v0.8.0-alpha10-code86-debug.zip`,
containing `app-debug.apk`.

- APK SHA-256 checksum（APK 文件校验值）:
  `e8b953944bb69500ab1aed8ac8a8c33590a79366048574d26401e2e3c61b1df6`
- ZIP SHA-256 checksum（ZIP 文件校验值）:
  `a5f00b0c82d32115dc3eed37e8ac71273cfd867e93f051c43b4cee91513ceef9`
- Signing（签名）: ordinary public Debug signing（普通公开调试签名）, not Trusted Signed（不是
  正式受信签名）.
- Device acceptance（真机验收）: `PENDING`（待验收） as of this update. Do not mark W1C as
  accepted until the user reports the result. This ordinary package is not an upgrade path for the
  stable-signed installation.

### Latest trusted alpha10 candidate（最近的受信 alpha10 候选包）

- Source candidate: PR #5, commit
  `8f822ba60ea213d2b6658a017df5cb5ab17070c2`.
- Protected candidate run（受保护候选流程）:
  [34813023478](https://github.com/kuashan/siftalpha-studio/actions/runs/34813023478), `SUCCESS`.
- Package: `0.8.0-alpha10`, `versionCode 86`, application id
  `com.siftalpha.studio`.
- Certificate SHA-256（证书校验值）:
  `1d96e9ce12c06e6ff0571cf8f82cf2461ac3b5261189ad06ab0747ce92f8192e`.
- APK SHA-256（APK 文件校验值）:
  `d1fe058d369e567f8b90e526a3a0d58ab4ef398c92355c9a354f66922a1a0fc2`.
- Device acceptance（真机验收）: `PENDING`（待验收）. This is the first stable-signed baseline
  intended to solve upgrade-in-place; a one-time uninstall may be needed from the previous
  ordinary-signed installation.

## Confirmed user requirements（已确认的用户要求）

### Build and release

- Use GitHub Actions cloud builds only; local Gradle/JDK/Android SDK is not a project dependency.
- Keep the repository Public to retain free cloud build access.
- Increment `versionCode` for every installable update so Android accepts the upgrade path.
- Keep the package name and stable signing lineage unchanged for trusted upgrade-in-place（覆盖安装）.
- The default device-acceptance artifact for every feature pass is a Trusted Signed Debug APK
  （稳定签名调试包）, so the user can test and upgrade in place without repeatedly uninstalling the
  app.
- An ordinary public pull-request Debug APK（普通公开调试包） is only for cloud checks and
  fresh-install testing. It must not be described as upgradeable.
- An unmerged candidate uses the isolated protected candidate-signing workflow（受保护的候选版本签名
  流程） by the repository owner's exact `/stable-debug` PR command. It signs the exact public-
  validated APK artifact, without checking out or executing untrusted pull-request code while signing
  secrets are available.
- The post-merge Trusted Signed Debug workflow produces the package intended to overwrite an existing
  trusted development install without uninstalling or clearing data.
- One-time migration（一次性迁移）: because alpha10 was built with an ordinary public Debug signer,
  the user may need to uninstall the currently installed ordinary package before installing the first
  stable-signed test package. From that stable-signed baseline onward, later candidates must keep the
  same signer and increase `versionCode` so Android can accept in-place updates.

### Acceptance and PR workflow

- The user installs and tests the Debug APK on a real device.
- The active Draft PR can contain several feature slices while they are being tested.
- Continue development after the user reports a feature passes; do not turn the test package into a
  formal release automatically.
- Do not merge or mark the PR ready before the relevant batch is accepted and the user authorizes
  the merge decision.
- Record CI results separately from real-device results.

### Communication

- In Chinese responses, every professional English term must be followed by a Chinese explanation in
  parentheses.
- Report failures and unknowns directly; never infer device acceptance from a green workflow.

## W1C behavior contract（W1C 行为约定）

The accepted target flow is:

1. Import a Python project and prepare its environment.
2. Keep `Configuration` available before and after preparation, unless a conflicting runtime
   operation is pending. It may show static candidates or saved values proactively.
3. After preparation succeeds, enable `Run`; the first run is the runtime detection pass.
4. Once an actionable runtime finding is reported, use `Configuration` to show the required values
   sequentially.
5. Runtime-reported required items cannot be skipped. Static optional candidates may be skipped.
6. After the configuration action completes, automatically run the project once again when the
   environment is ready, including when optional candidates were skipped. This is a fresh runtime
   detection pass; it must not issue a runtime command before preparation or while another operation
   is pending.
7. Keep the list editor available for later updates or clearing Studio-managed values.

Configuration values remain in the existing Android Keystore-backed protected store and one-shot
runtime payload. Never write them to source files, `.env`, logs, GitHub, or this documentation.

## Related records（相关记录）

- [Chronological development log（按时间总开发日志）](DEV_LOG.md)
- [W2 runtime lifecycle note（W2 运行生命周期记录）](DEV_NOTES/V0_8_W2_RUNTIME_LIFECYCLE_2026-09-14.md)
- [W1C detailed development note（W1C 详细开发记录）](DEV_NOTES/V0_8_W1C_CONFIGURATION_WIZARD_2026-09-14.md)
- [Launcher icon repair（启动图标修复）](DEV_NOTES/W1A_ALPHA7_ICON_REPAIR_2026-09-14.md)
- [UI foundation（界面基础）](DEV_NOTES/V0_8_W1A_UI_FOUNDATION_2026-09-13.md)
- [Stable signing and upgrade continuity（稳定签名与覆盖安装连续性）](DEV_NOTES/STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md)
- [Stable-signed device test package（稳定签名设备测试包流程）](WORKFLOWS/STABLE_SIGNED_DEVICE_TEST_PACKAGE.md)
- [Post-merge official validation package（合并后正式验证包）](WORKFLOWS/POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md)
- [Public repository and source policy（公开仓库与源代码政策）](WORKFLOWS/PUBLIC_SOURCE_ALL_RIGHTS_RESERVED_2026-09-13.md)

## Current next action（当前下一步）

The protected candidate-signing workflow is available on `main`, and the direct `/stable-debug`
path is the current stable-signing trigger for PR #5. W2 alpha12 implementation is committed to
`codex/w1c-config-wizard`, but public checks and the protected candidate package are still pending.
Run the three public checks on the final W2 commit through a GitHub-authenticated synchronization or
manual trigger, then request the Trusted Signed Debug APK（稳定签名调试包）. The current connector
cannot call workflow_dispatch（手动触发工作流）, so no APK is available yet. Keep PR #5 Draft and do
not merge automatically.
