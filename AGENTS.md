# SiftAlpha Studio Agent Instructions（协作规则）

This file is the first project instruction file to read at the start of every task. It defines
the durable collaboration contract for `kuashan/siftalpha-studio`; it is not a replacement for the
current code, the current pull request, or the latest development log.

## Mandatory startup（开始工作前必须执行）

1. Read this file, `docs/PROJECT_CONTEXT.md`, and the newest relevant file in `docs/DEV_NOTES/`.
2. Read the latest entry in `docs/DEV_LOG.md` and identify the current status and next action.
3. Inspect `git status`, the current branch, `HEAD`, the open PR（合并请求）, and recent GitHub
   Actions（自动构建检查） results before changing files.
4. Preserve existing user changes. Do not reset, discard, or overwrite unrelated work in a dirty
   worktree（工作区）.
5. If project documents conflict, prefer an explicit current user decision and the current checked
   out code/PR over an older historical note. Record the resolution in the project context or log.

## Project identity（项目身份）

- Repository（仓库）: `kuashan/siftalpha-studio`
- Product: SiftAlpha Studio
- Android application id（应用标识）: `com.siftalpha.studio`
- Repository visibility（仓库可见性）: Public（公开） by deliberate choice, so free GitHub cloud
  builds remain available.
- Primary runtime path（主要运行路径）: Python projects through the existing Termux/PRoot
  contract. Do not introduce a new runtime language promise without an explicit decision.

## Non-negotiable workflow（不可改变的工作流程）

- Use GitHub Actions as the supported build environment. The project does not depend on a local
  Gradle（构建工具）、JDK（Java 开发工具包）、Android Studio, or Android SDK installation for
  normal validation.
- Every installable update must increase Android `versionCode`（版本代码）, keep the application
  id unchanged, and update `versionName`（版本名称） consistently.
- Each meaningful feature first produces a Trusted Signed Debug APK（稳定签名调试包） for the
  user's real device acceptance（真机验收） and upgrade-in-place（覆盖安装） testing.
- An ordinary public pull-request Debug APK（普通公开调试包） uses ordinary CI signing（持续集成
  签名）. It is valid for cloud checks and fresh-install testing only; it must not be presented as
  the default upgradeable device package.
- Stable-signed candidate packages must be created inside a protected signing boundary（受保护的签名
  边界）. Never expose signing secrets to untrusted pull-request code or its build/test steps. The
  current Trusted Signed Debug APK（稳定签名调试包） workflow is still limited to protected
  `main`/legacy-branch builds, so an isolated protected candidate-signing workflow is required before
  an unmerged PR candidate can be called upgradeable.
- Do not claim device acceptance from CI alone. Record the user's actual result as `PASS`, `FAIL`,
  or `NOT TESTED`.
- Keep the active feature PR Draft（草稿） while several features are being device-tested. Do not
  merge（合并）, mark ready, or close the PR automatically. Merge only after the user confirms the
  relevant batch is accepted and explicitly authorizes the merge decision.
- Do not create a release tag or call a test artifact a formal release without an explicit release
  decision.

## Documentation contract（文档记录规则）

After every meaningful feature or workflow change, update the same change set with:

- scope and user-visible behavior;
- the decision and its reason;
- changed files and important compatibility boundaries;
- local static checks, unit tests, and cloud workflow results;
- candidate version, commit SHA（提交标识）, PR, artifact（构建产物） name, and checksum（校验值）
  when a package is produced;
- real-device acceptance result and the next action.

Use `docs/PROJECT_CONTEXT.md` for durable rules and the current handoff（交接状态）. Use
`docs/DEV_LOG.md` for the chronological summary. Use a dated `docs/DEV_NOTES/` file for detailed
feature notes, and `docs/WORKFLOWS/` for repeatable operational procedures. Keep `README.md` as
the human-facing overview and link to the canonical records instead of duplicating every detail.

## Sensitive information（敏感信息边界）

Never commit or log secret values, tokens, passwords, private keys, keystores, base64 signing
payloads, or copied credential files. It is safe to record a secret name, whether it is configured,
and which protected workflow consumes it. Runtime configuration values must remain in the existing
protected store and one-shot runtime payload.

## Communication（沟通要求）

When responding in Chinese, append a Chinese explanation in parentheses after professional English
terms, for example `artifact（构建产物）`, `workflow（工作流程）`, and `checksum（校验值）`.
Lead with the outcome, state evidence and blockers plainly, and do not describe an untested item as
complete.
