
# W2 Runtime Lifecycle Reliability（W2 运行生命周期可靠性）

Date（日期）: 2026-09-14  
Branch/PR（分支/合并请求）: codex/w1c-config-wizard, PR #5, Draft（草稿）  
Candidate（候选版本）: 0.8.0-alpha12, versionCode 88  
Application id（应用标识）: com.siftalpha.studio  
Code candidate commit（代码候选提交）: b6135d705441b8b3149f4249defe39e6e801a72f

## Goal（目标）

Make Runtime Center（运行中心） reflect the real project process across prepare, detection,
configuration, rerun, stop, failure, and app reopen transitions（准备、检测、配置、重新运行、停止、
失败和重新打开应用的转换）.

## Implementation（实现）

- Added a pure lifecycle resolver（纯生命周期解析器） with nine user-facing states:
  environment not prepared（环境未准备）, preparing（准备中）, ready to run（可以运行）, detecting
  （检测中）, needs configuration（需要配置）, running（运行中）, stopped（已停止）, run failed
  （运行失败）, and recovering（恢复中）.
- Added per-project non-secret lifecycle persistence（非秘密生命周期持久化）. Persisted active
  states are never trusted as proof of a running process; foreground recovery issues a real STATUS
  command（状态命令） first.
- Added a bounded redacted failure reason（有界脱敏失败原因） and renders it in the project card.
  Output panels and error dialogs redact configured protected values before display.
- Added a side-effect boundary（副作用边界） that rejects START（启动） when the environment is not
  ready, a project is active, recovery is pending, or runtime discovery reports missing required
  configuration（必要配置）.
- Persisted runtime configuration discovery metadata by project folder identity（项目目录身份） so
  a reopened app does not forget that a previous run requires configuration. Secret values remain in
  the existing Android Keystore-backed store.
- Kept existing local web URL validation（本地 Web 地址验证）, process ownership（进程所有权）,
  Termux/PRoot protocol（Termux/PRoot 协议）, cleanup behavior（清理行为）, Python/Node.js runtime
  scope（Python/Node.js 运行时范围）, and no Universal Dashboard（统一控制面板） rule.

## Verification plan（验证计划）

- New JVM unit tests（JVM 单元测试） cover lifecycle mapping and bounded failure reasons.
- Existing action-policy tests now verify that runtime-discovered required configuration blocks a
  retry until Configuration（配置） is completed.
- Cloud checks（云端检查）: NOT RUN（未运行） at note creation; they must run against the same
  final commit. Candidate signing run 34832205686 FAILED（失败） because those same-commit checks
  were absent; the signing job was skipped.
- Real-device acceptance（真机验收）: NOT TESTED（未测试）. It must be reported separately from
  Actions（自动化流程） results.
