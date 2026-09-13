# Third-Party Notices / 第三方依赖说明

## Termux

SiftAlpha Studio v0.1.1 **不包含 Termux APK**，也不把 Termux 二进制库打包进应用。

APP 通过 Termux 官方公开的 `RUN_COMMAND` Intent 与用户单独安装的 Termux 通信。

### Termux RUN_COMMAND contract 常量

`app/src/main/java/com/siftalpha/studio/runtime/TermuxContract.kt` 中使用的少量协议常量值来自 Termux 官方：

```text
termux-app/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
```

该上游文件标记为：

```text
SPDX-License-Identifier: MIT
```

这里只复制 SiftAlpha Studio 实际需要的公开 Intent 字符串，不复制 Termux 的实现代码。

最初原型曾尝试直接依赖 `termux-shared`，但 GitHub Actions 中 JitPack 返回 401。为减少构建供应链依赖，v0.1.1 改为维护最小 contract；后续升级 Termux 兼容范围时需要对照官方常量重新审计。

## Ubuntu / PRoot

v0.1.1 不打包 Ubuntu rootfs 和 PRoot。它们仍由用户的 Termux / proot-distro 环境管理。
