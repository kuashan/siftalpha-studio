package com.siftalpha.studio.runtime

/**
 * SiftAlpha Studio 的运行时抽象层。
 *
 * UI / 项目管理器只依赖这个接口，不直接依赖 Termux。
 * 未来如果增加 EmbeddedLinuxBackend，项目层无需推倒重写。
 */
interface RuntimeBackend {
    fun isAvailable(): Boolean
    fun execute(command: RuntimeCommand): Int
}

data class RuntimeCommand(
    val shellScript: String,
    val label: String,
    val description: String = label,
    val background: Boolean = true,
    /**
     * 可选的项目级秘密命名空间。Backend 只在真正执行前读取本地 Keystore 密文，
     * 明文不会进入 shellScript / label / description。
     */
    val secretNamespace: String? = null,
    /**
     * 可选的一次性 stdin 载荷提供器。用于 Runtime Agent token 等不应进入命令字符串的
     * 短生命周期数据。Provider 只在 Backend 真正发送命令前求值，返回内容不得写入日志。
     *
     * 与 secretNamespace 互斥；项目 Secrets 继续由 Keystore 路径负责。
     */
    val stdinPayloadProvider: (() -> String?)? = null,
)

data class RuntimeResult(
    val executionId: Int,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalErrorCode: Int,
    val internalErrorMessage: String,
) {
    val successful: Boolean
        get() = internalErrorCode == android.app.Activity.RESULT_OK && exitCode == 0
}
