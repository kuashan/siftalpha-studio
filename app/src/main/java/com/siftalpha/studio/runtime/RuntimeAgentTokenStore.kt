package com.siftalpha.studio.runtime

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Stores the localhost Runtime Agent bearer token in Android app-private no-backup storage.
 *
 * The token is not user data and should not be restored onto another device. A fresh app install
 * therefore gets a fresh 256-bit token; starting the Agent writes that token into Ubuntu private
 * storage with mode 0600.
 */
class RuntimeAgentTokenStore(context: Context) {
    private val tokenFile = File(context.noBackupFilesDir, TOKEN_FILE_NAME)

    @Synchronized
    fun getOrCreate(): String {
        val existing = runCatching { tokenFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (RuntimeAgentProtocol.isValidToken(existing)) return existing!!
        return rotate()
    }

    @Synchronized
    fun rotate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(RuntimeAgentProtocol.isValidToken(token))

        tokenFile.parentFile?.mkdirs()
        val temp = File(tokenFile.parentFile, "$TOKEN_FILE_NAME.tmp")
        temp.writeText(token, Charsets.UTF_8)
        if (!temp.renameTo(tokenFile)) {
            tokenFile.writeText(token, Charsets.UTF_8)
            temp.delete()
        }
        return token
    }

    companion object {
        private const val TOKEN_FILE_NAME = "runtime-agent-token-v1"
    }
}
