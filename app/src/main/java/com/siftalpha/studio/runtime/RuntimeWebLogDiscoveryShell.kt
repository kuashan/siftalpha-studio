package com.siftalpha.studio.runtime

/**
 * Bounded guest-side fallback that turns ordinary local Web log messages into the existing
 * SIFTALPHA_WEB_URL protocol. It never scans ports and never accepts external hosts.
 *
 * The Android endpoint probe still decides whether Browser is actually available; this helper only
 * surfaces a candidate that PID/socket discovery could not observe across the Termux/PRoot boundary.
 * The caller must define a shell variable named `log` containing the Runtime log path.
 */
object RuntimeWebLogDiscoveryShell {

    fun shellSnippet(): String = """
        siftalpha_log_web_candidate=''
        if [ -s "${'$'}log" ]; then
          siftalpha_log_web_candidate="${'$'}(tail -n 160 "${'$'}log" 2>/dev/null | \
            grep -Eio 'https?://(127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1\]|\[::\]):[0-9]{1,5}[^[:space:]]*' | \
            tail -n 1 || true)"

          if [ -z "${'$'}siftalpha_log_web_candidate" ]; then
            siftalpha_log_web_bare="${'$'}(tail -n 160 "${'$'}log" 2>/dev/null | \
              grep -Eio '(127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1\]|\[::\]):[[:space:]]*[0-9]{1,5}' | \
              tail -n 1 | tr -d '[:space:]' || true)"
            if [ -n "${'$'}siftalpha_log_web_bare" ]; then
              siftalpha_log_web_candidate="http://${'$'}siftalpha_log_web_bare"
            fi
          fi

          if [ -z "${'$'}siftalpha_log_web_candidate" ]; then
            siftalpha_log_web_port="${'$'}(tail -n 160 "${'$'}log" 2>/dev/null | \
              sed -nE 's/.*([Ll]isten(ing)?|[Rr]unning)[^0-9]{0,64}[Pp]ort[[:space:]:=]*([0-9]{1,5}).*/\3/p' | \
              tail -n 1 || true)"
            if [ -n "${'$'}siftalpha_log_web_port" ]; then
              siftalpha_log_web_candidate="http://127.0.0.1:${'$'}siftalpha_log_web_port"
            fi
          fi

          case "${'$'}siftalpha_log_web_candidate" in
            http://0.0.0.0:*)
              siftalpha_log_web_candidate="http://127.0.0.1:${'$'}{siftalpha_log_web_candidate#http://0.0.0.0:}"
              ;;
            https://0.0.0.0:*)
              siftalpha_log_web_candidate="https://127.0.0.1:${'$'}{siftalpha_log_web_candidate#https://0.0.0.0:}"
              ;;
            http://\[::\]:*)
              siftalpha_log_web_candidate="http://127.0.0.1:${'$'}{siftalpha_log_web_candidate#http://[::]:}"
              ;;
            https://\[::\]:*)
              siftalpha_log_web_candidate="https://127.0.0.1:${'$'}{siftalpha_log_web_candidate#https://[::]:}"
              ;;
          esac

          siftalpha_log_web_port="${'$'}(printf '%s\n' "${'$'}siftalpha_log_web_candidate" | \
            sed -nE 's#^https?://(\[[^]]+\]|[^:/]+):([0-9]{1,5}).*#\2#p')"
          if printf '%s' "${'$'}siftalpha_log_web_port" | grep -Eq '^[0-9]+${'$'}' && \
             [ "${'$'}siftalpha_log_web_port" -ge 1 ] && \
             [ "${'$'}siftalpha_log_web_port" -le 65535 ]; then
            echo 'SIFTALPHA_WEB_DISCOVERY_SOURCE=RUNTIME_LOG'
            printf 'SIFTALPHA_WEB_URL=%s\n' "${'$'}siftalpha_log_web_candidate"
          else
            echo 'SIFTALPHA_WEB_LOG_DISCOVERY=NO_CANDIDATE'
          fi
        else
          echo 'SIFTALPHA_WEB_LOG_DISCOVERY=NO_LOG'
        fi
        unset siftalpha_log_web_candidate siftalpha_log_web_bare siftalpha_log_web_port
    """.trimIndent()
}
