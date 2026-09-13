package com.siftalpha.studio.runtime

/**
 * Defines how Runtime commands use Termux RUN_COMMAND stdin as a transport channel.
 *
 * Project secrets still come from the Keystore-backed secret namespace. Runtime infrastructure may
 * additionally provide a deferred one-shot payload (for example the Runtime Agent bearer token).
 * Both paths deliberately keep sensitive payloads out of shellScript / label / description.
 */
object RuntimeStdinPolicy {
    private const val DEV_NULL_PREFIX = "exec </dev/null\n"

    fun resolvePayload(command: RuntimeCommand, secretPayload: String?): String? {
        check(command.secretNamespace == null || command.stdinPayloadProvider == null) {
            "secretNamespace and stdinPayloadProvider are mutually exclusive"
        }
        return command.stdinPayloadProvider?.invoke() ?: secretPayload
    }

    fun effectiveShellScript(command: RuntimeCommand, resolvedPayload: String?): String {
        val expectsTransport = command.secretNamespace != null || command.stdinPayloadProvider != null
        return if (expectsTransport && resolvedPayload == null) {
            DEV_NULL_PREFIX + command.shellScript
        } else {
            command.shellScript
        }
    }

    fun shouldAttachStdin(command: RuntimeCommand, resolvedPayload: String?): Boolean =
        (command.secretNamespace != null || command.stdinPayloadProvider != null) && resolvedPayload != null
}
