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

- Candidate: `0.8.0-alpha9`
- Android `versionCode`（版本代码）: `85`
- Feature branch（功能分支）: `codex/w1c-config-wizard`
- Active PR: #5, open, Draft（草稿）, unmerged（未合并）
- Code candidate tested by cloud CI（云端持续集成）: commit `8615c68204633e0912cfac432e564ad59c1dfa31`
- Current feature slice（当前功能切片）: W1C Python configuration detection and sequential
  configuration wizard（Python 配置检测和顺序配置向导）.

### Latest cloud validation（最近一次云端验证）

All three checks for the code candidate completed successfully:

- [Android Debug APK run 34806983723](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983723)
- [Exact Head Unit run 34806983715](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983715)
- [Localization run 34806983732](https://github.com/kuashan/siftalpha-studio/actions/runs/34806983732)

Artifact（构建产物）: `SiftAlpha-Studio-debug-ab2598d18d70be751c622d8f083c6524040f79af`

Downloaded test package（已下载测试包）: `SiftAlpha-Studio-v0.8.0-alpha9-code85-debug.zip`,
containing `app-debug.apk`.

- APK SHA-256 checksum（APK 文件校验值）:
  `339ced7e2cce565b449476b6f0356775e8a016b2583d00b3f75d13578ad27cc2`
- ZIP SHA-256 checksum（ZIP 文件校验值）:
  `01f6e2ea313f31f78245e6b0940a8440820dc68f1439edee3449dac5ff4bbf43`
- Signing（签名）: ordinary public Debug signing（普通公开调试签名）, not Trusted Signed（不是
  正式受信签名）.
- Device acceptance（真机验收）: `PENDING`（待验收） as of this update. Do not mark W1C as
  accepted until the user reports the result.

## Confirmed user requirements（已确认的用户要求）

### Build and release

- Use GitHub Actions cloud builds only; local Gradle/JDK/Android SDK is not a project dependency.
- Keep the repository Public to retain free cloud build access.
- Increment `versionCode` for every installable update so Android accepts the upgrade path.
- Keep the package name and stable signing lineage unchanged for trusted upgrade-in-place（覆盖安装）.
- Provide an ordinary Debug APK for every feature acceptance pass before discussing a formal package.
- The post-merge Trusted Signed Debug workflow produces the package intended to overwrite an existing
  trusted development install without uninstalling or clearing data.

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
2. After preparation succeeds, enable `Run`; do not enable `Configuration` merely because static
   inspection found optional candidates.
3. The first run is allowed to expose what configuration the runtime actually needs.
4. Once an actionable runtime finding is reported, enable `Configuration` and show the required
   values sequentially.
5. Runtime-reported required items cannot be skipped. Static optional candidates may be skipped.
6. After at least one value is saved, automatically run the project once again. Skipping optional
   candidates alone must not cause an endless retry/prompt loop.
7. Keep the list editor available for later updates or clearing Studio-managed values.

Configuration values remain in the existing Android Keystore-backed protected store and one-shot
runtime payload. Never write them to source files, `.env`, logs, GitHub, or this documentation.

## Related records（相关记录）

- [Chronological development log（按时间总开发日志）](DEV_LOG.md)
- [W1C detailed development note（W1C 详细开发记录）](DEV_NOTES/V0_8_W1C_CONFIGURATION_WIZARD_2026-09-14.md)
- [Launcher icon repair（启动图标修复）](DEV_NOTES/W1A_ALPHA7_ICON_REPAIR_2026-09-14.md)
- [UI foundation（界面基础）](DEV_NOTES/V0_8_W1A_UI_FOUNDATION_2026-09-13.md)
- [Stable signing and upgrade continuity（稳定签名与覆盖安装连续性）](DEV_NOTES/STABLE_SIGNING_UPGRADE_CONTINUITY_2026-09-13.md)
- [Post-merge official validation package（合并后正式验证包）](WORKFLOWS/POST_MERGE_OFFICIAL_VALIDATION_PACKAGE.md)
- [Public repository and source policy（公开仓库与源代码政策）](WORKFLOWS/PUBLIC_SOURCE_ALL_RIGHTS_RESERVED_2026-09-13.md)

## Current next action（当前下一步）

The user should install the alpha9 ordinary Debug APK and test the W1C flow on the authorized
device. After the user's result is reported, append the result to `docs/DEV_LOG.md`, update the
status in this file, and then either continue the next feature slice or prepare the accepted batch
for merge.
