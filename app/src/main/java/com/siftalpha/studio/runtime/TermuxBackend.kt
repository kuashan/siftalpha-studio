package com.siftalpha.studio.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

class TermuxBackend(private val context: Context) : RuntimeBackend {

    override fun isAvailable(): Boolean = isTermuxInstalled()

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(): Boolean {
        return context.checkSelfPermission(TermuxContract.RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun execute(command: RuntimeCommand): Int {
        check(isTermuxInstalled()) { "未检测到 Termux" }

        // Sensitive data is resolved only immediately before dispatch. It never enters
        // RuntimeCommand.shellScript / label / description.
        val secretPayload = command.secretNamespace
            ?.let { ProjectSecretStore(context).runtimePayload(it) }
        val stdinPayload = RuntimeStdinPolicy.resolvePayload(command, secretPayload)
        val effectiveShellScript = RuntimeStdinPolicy.effectiveShellScript(command, stdinPayload)

        val executionId = NEXT_ID.incrementAndGet()
        val callbackIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
        }

        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        val pendingIntent = PendingIntent.getService(
            context,
            executionId,
            callbackIntent,
            pendingFlags,
        )

        val intent = Intent().apply {
            setClassName(TermuxContract.PACKAGE_NAME, TermuxContract.RUN_COMMAND_SERVICE)
            action = TermuxContract.ACTION_RUN_COMMAND
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(TermuxContract.EXTRA_ARGUMENTS, arrayOf("-lc", effectiveShellScript))
            if (RuntimeStdinPolicy.shouldAttachStdin(command, stdinPayload)) {
                putExtra(TermuxContract.EXTRA_STDIN, stdinPayload)
            }
            putExtra(TermuxContract.EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(TermuxContract.EXTRA_BACKGROUND, command.background)
            putExtra(TermuxContract.EXTRA_COMMAND_LABEL, command.label)
            putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, command.description)
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, pendingIntent)
        }

        context.startService(intent)
        return executionId
    }

    companion object {
        private val NEXT_ID = AtomicInteger(1000)

        val FIRST_RUN_SETUP_COMMAND = """
            mkdir -p ~/.termux
            touch ~/.termux/termux.properties
            if grep -qE '^\\s*allow-external-apps\\s*=\\s*true\\s*$' ~/.termux/termux.properties; then
              echo 'allow-external-apps already enabled'
            else
              printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties
            fi
            termux-reload-settings 2>/dev/null || true
            echo 'SiftAlpha Studio Termux bridge setup complete.'
        """.trimIndent()

        val CONNECTION_TEST = RuntimeCommand(
            shellScript = "printf 'SIFTALPHA_TERMUX_BRIDGE_OK\\n'; printf 'TERMUX_PREFIX=%s\\n' \"${'$'}PREFIX\"; uname -m",
            label = "SiftAlpha Studio 连接测试",
            description = "验证 SiftAlpha Studio 是否可以通过官方 RUN_COMMAND 接口调用 Termux。",
        )

        val ENVIRONMENT_PROBE = RuntimeCommand(
            shellScript = """
                echo '=== SiftAlpha Runtime Probe ==='
                printf 'TERMUX=OK\\n'
                printf 'ARCH='; uname -m

                if [ -d /storage/emulated/0 ]; then
                  echo 'SHARED_STORAGE=OK'
                else
                  echo 'SHARED_STORAGE=MISSING'
                fi

                if command -v proot-distro >/dev/null 2>&1; then
                  echo 'PROOT_DISTRO=OK'
                else
                  echo 'PROOT_DISTRO=MISSING'
                  exit 20
                fi

                echo '--- Ubuntu ---'
                if proot-distro login ubuntu -- bash -lc '
                  echo UBUNTU=OK
                  printf "PYTHON="; python3 --version 2>&1 || true
                  printf "PIP="; python3 -m pip --version 2>&1 || true
                  printf "GIT="; git --version 2>&1 || true
                  printf "VENV="; python3 -m venv --help >/dev/null 2>&1 && echo OK || echo MISSING
                  printf "TMUX="; command -v tmux >/dev/null 2>&1 && tmux -V || echo MISSING
                  printf "LIBC="; (ldd --version 2>&1 | head -n 1) || echo MISSING
                '; then
                  echo 'UBUNTU_LOGIN=OK'
                else
                  code=${'$'}?
                  printf 'UBUNTU_LOGIN=FAILED:%s\\n' "${'$'}code"
                  exit "${'$'}code"
                fi
            """.trimIndent(),
            label = "SiftAlpha Studio 运行环境检测",
            description = "检测 Termux、共享存储、proot-distro、Ubuntu、Python、pip、venv、Git、tmux 与 libc。",
        )
    }
}
