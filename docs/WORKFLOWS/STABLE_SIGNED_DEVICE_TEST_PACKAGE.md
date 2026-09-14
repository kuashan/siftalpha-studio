# Stable-signed device test package（稳定签名设备测试包）

**Status:** Required from the next device-acceptance candidate（下一个真机验收候选版本起生效）  
**Scope:** Every installable feature candidate delivered for real-device testing

## Policy

The default package for a feature's real-device acceptance（真机验收） is a Trusted Signed Debug
APK（稳定签名调试包）. It must keep the stable development certificate, the application id
`com.siftalpha.studio`, and a higher Android `versionCode`（版本代码） so the user can install the
candidate over the existing stable-signed app without clearing app data.

An ordinary public pull-request Debug APK（普通公开调试包） remains useful for fork-safe CI（分支
安全持续集成）, cloud checks, and fresh-install testing. Its signer may vary between cloud runners,
so it is not an upgradeable device package and must not be presented as one.

## Required safe sequence

1. Run the public pull-request checks against the exact candidate commit: localization, unit/host
   checks, version validation, and ordinary Debug APK build. These steps receive no signing secrets.
2. After the candidate is eligible for device testing, use an isolated protected candidate-signing
   workflow（受保护的候选版本签名流程） to check out and sign that exact commit with the stable
   development signer.
3. Verify the package id, `versionName`（版本名称）, higher `versionCode`, stable certificate
   fingerprint, launcher icon, and artifact digest（构建产物摘要） before upload.
4. Provide the extracted stable-signed APK to the user for real-device testing. Record the source
   commit, workflow run, artifact name, APK checksum（APK 校验值）, certificate fingerprint, and
   device result as `PASS`, `FAIL`, or `NOT TESTED`.
5. Keep the PR Draft（草稿） while the user tests the current batch. Do not merge, mark ready, or
   create a formal release automatically.

Signing secrets must be available only inside the protected signer. They must never be passed to
untrusted pull-request build/test steps, written to the repository, or recorded in logs. The existing
protected workflow is currently limited to protected `main`/legacy-branch builds; it cannot by itself
make an unmerged PR artifact upgradeable. That protected candidate path is therefore a required
workflow follow-up.

## One-time migration from ordinary Debug

Alpha10 was delivered as an ordinary public Debug APK. If Android reports a signature mismatch with
the package currently installed on the device, uninstall the ordinary package once, then install the
first stable-signed device test package. This is a one-time migration to the stable-signed baseline;
future candidates must retain the same signer and increase `versionCode` for in-place updates.

Never uninstall or clear app data as part of a normal upgrade test. If a clean install is intentional,
record that it is a fresh-install test rather than an upgrade result.

## Required record fields

For every stable-signed device test package, record:

- candidate `versionName` and `versionCode`;
- source commit SHA（提交标识） and PR（合并请求）;
- successful public validation runs and the protected signing run;
- GitHub artifact name and downloaded APK checksum;
- verified stable certificate fingerprint;
- user's real-device acceptance result and the next action.

Record secret names and whether the protected configuration is available when useful, but never record
secret values, passwords, private keys, keystores, or base64 signing payloads.
