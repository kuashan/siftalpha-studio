package com.siftalpha.studio.runtime

/**
 * SiftAlpha Studio 使用到的 Termux RUN_COMMAND 最小 Intent contract。
 *
 * 常量值来自 Termux 官方 termux-shared/TermuxConstants.java（SPDX-License-Identifier: MIT）。
 * 这里只保留 SiftAlpha Studio 实际需要的公开协议字段，避免把整个 termux-shared
 * 二进制库打入 APP，同时避免 CI 依赖 JitPack 可用性。
 */
object TermuxContract {
    const val PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
}
