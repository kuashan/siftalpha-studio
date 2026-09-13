package com.siftalpha.studio.runtime

/**
 * Runtime-owned Web listener discovery used by LOGS/browser inspection.
 *
 * Discovery is deliberately bounded and project-process-scoped. The fast path inspects Termux
 * procfs for the managed PID/PGID. PRoot can hide socket fd metadata from that host-side view, so a
 * second pass executes the same PID-scoped inspection from inside the Ubuntu guest. This is not a
 * 1..65535 loopback scan and does not fall back to unrelated listeners owned by the Termux UID.
 */
object RuntimeWebPortDiscovery {
    private const val D = "$"
    private const val MAX_RUNTIME_CANDIDATES = 6

    private val preferredPorts = listOf(
        5173, // Vite
        3000, // Next / common Node dev server
        8000, // FastAPI / common app server
        8080,
        8501, // Streamlit
        7860, // Gradio
        8050, // Dash
        5000, // Flask / common Node server
        8888,
        5001,
        8001,
        8081,
        3001,
    )

    fun rankCandidates(candidates: Collection<Int>): List<Int> {
        val valid = candidates.filter { it in 1..65535 }.distinct()
        val preferred = preferredPorts.filter { it in valid }
        val remainder = valid.filterNot { it in preferredPorts }.sorted()
        return preferred + remainder
    }

    fun shellSnippet(): String {
        val preferred = preferredPorts.joinToString(" ")
        val guestScript = guestDiscoveryScript(preferred)
        val quotedGuestScript = shellQuote(guestScript)
        return """
            siftalpha_web_runtime_pids() {
              web_root_pid="${D}1"
              web_pgid="${D}2"
              {
                [ -n "${D}web_root_pid" ] && printf '%s\n' "${D}web_root_pid"
                [ -n "${D}web_root_pid" ] && siftalpha_descendants "${D}web_root_pid" 2>/dev/null || true
                if [ -n "${D}web_pgid" ] && command -v ps >/dev/null 2>&1; then
                  ps -eo pid=,pgid= 2>/dev/null | awk -v g="${D}web_pgid" '${D}2 == g { print ${D}1 }' || true
                fi
              } | awk 'NF && !seen[${D}1]++'
            }

            siftalpha_web_socket_inodes_for_pids() {
              for web_pid in "${D}@"; do
                [ -d "/proc/${D}web_pid/fd" ] || continue
                for web_fd in /proc/${D}web_pid/fd/*; do
                  web_link="${D}(readlink "${D}web_fd" 2>/dev/null || true)"
                  case "${D}web_link" in
                    socket:\[*\])
                      web_inode="${D}{web_link#socket:[}"
                      web_inode="${D}{web_inode%]}"
                      case "${D}web_inode" in
                        ''|*[!0-9]*) ;;
                        *) printf '%s\n' "${D}web_inode" ;;
                      esac
                      ;;
                  esac
                done
              done | awk 'NF && !seen[${D}1]++'
            }

            siftalpha_web_ports_for_inodes() {
              web_inodes=" ${D}1 "
              [ "${D}web_inodes" != '  ' ] || return 0
              for web_table in /proc/net/tcp /proc/net/tcp6; do
                [ -r "${D}web_table" ] || continue
                awk 'NR > 1 && ${D}4 == "0A" { split(${D}2, a, ":"); print a[2], ${D}10 }' "${D}web_table" 2>/dev/null || true
              done | while read -r web_hex_port web_inode; do
                [ -n "${D}web_hex_port" ] && [ -n "${D}web_inode" ] || continue
                case "${D}web_inodes" in
                  *" ${D}web_inode "*)
                    web_port="${D}((16#${D}web_hex_port))"
                    if [ "${D}web_port" -ge 1 ] 2>/dev/null && [ "${D}web_port" -le 65535 ] 2>/dev/null; then
                      printf '%s\n' "${D}web_port"
                    fi
                    ;;
                esac
              done | sort -n -u
            }

            siftalpha_web_candidate_ports() {
              web_root_pid="${D}1"
              web_pgid="${D}2"
              web_pids="${D}(siftalpha_web_runtime_pids "${D}web_root_pid" "${D}web_pgid" | tr '\n' ' ')"
              [ -n "${D}{web_pids// }" ] || return 0
              # shellcheck disable=SC2086
              web_inodes="${D}(siftalpha_web_socket_inodes_for_pids ${D}web_pids | tr '\n' ' ')"
              siftalpha_web_ports_for_inodes "${D}web_inodes"
            }

            siftalpha_web_http_probe() {
              web_port="${D}1"
              if command -v timeout >/dev/null 2>&1; then
                web_first_line="${D}(timeout 1 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/${D}1" || exit 1; printf "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n" >&3; IFS= read -r line <&3 || true; printf "%s" "${D}line"' _ "${D}web_port" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
              elif command -v busybox >/dev/null 2>&1; then
                web_first_line="${D}(busybox timeout 1 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/${D}1" || exit 1; printf "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n" >&3; IFS= read -r line <&3 || true; printf "%s" "${D}line"' _ "${D}web_port" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
              else
                return 1
              fi
              case "${D}web_first_line" in
                HTTP/*) return 0 ;;
                *) return 1 ;;
              esac
            }

            siftalpha_web_order_ports() {
              web_ports="${D}1"
              web_ordered=''
              for web_preferred in $preferred; do
                case " ${D}web_ports " in
                  *" ${D}web_preferred "*) web_ordered="${D}web_ordered ${D}web_preferred" ;;
                esac
              done
              for web_port in ${D}web_ports; do
                case " ${D}web_ordered " in
                  *" ${D}web_port "*) ;;
                  *) web_ordered="${D}web_ordered ${D}web_port" ;;
                esac
              done
              printf '%s\n' "${D}web_ordered"
            }

            siftalpha_web_probe_ports() {
              web_source="${D}1"
              shift
              web_ports="${D}*"
              [ -n "${D}{web_ports// }" ] || return 2
              web_ordered="${D}(siftalpha_web_order_ports "${D}web_ports")"
              web_checked=0
              for web_port in ${D}web_ordered; do
                if [ "${D}web_checked" -ge $MAX_RUNTIME_CANDIDATES ]; then
                  echo 'SIFTALPHA_WEB_RUNTIME_CANDIDATE_LIMIT=REACHED'
                  break
                fi
                web_checked="${D}((web_checked + 1))"
                printf 'SIFTALPHA_WEB_PORT_CANDIDATE=%s source=%s\n' "${D}web_port" "${D}web_source"
                if siftalpha_web_http_probe "${D}web_port"; then
                  printf 'SIFTALPHA_WEB_AUTODISCOVERY=PASS source=%s port=%s\n' "${D}web_source" "${D}web_port"
                  printf 'SIFTALPHA_WEB_URL=http://127.0.0.1:%s\n' "${D}web_port"
                  return 0
                fi
              done
              return 1
            }

            siftalpha_web_guest_fallback() {
              web_root_pid="${D}1"
              web_pgid="${D}2"
              if ! command -v proot-distro >/dev/null 2>&1; then
                echo 'SIFTALPHA_WEB_GUEST_SCOPE=UNAVAILABLE reason=PROOT_DISTRO_MISSING'
                return 2
              fi
              web_guest_output="${D}(proot-distro login --bind "${D}ROOT:/root/projects" ubuntu -- bash -lc $quotedGuestScript siftalpha-web "${D}web_root_pid" "${D}web_pgid" 2>/dev/null || true)"
              [ -n "${D}web_guest_output" ] && printf '%s\n' "${D}web_guest_output"
              case "${D}web_guest_output" in
                *'SIFTALPHA_WEB_AUTODISCOVERY=PASS source=PROOT_PROJECT_PID_SCOPE'*) return 0 ;;
                *) return 1 ;;
              esac
            }

            siftalpha_web_autodiscover() {
              web_root_pid="${D}1"
              web_pgid="${D}2"
              web_ports="${D}(siftalpha_web_candidate_ports "${D}web_root_pid" "${D}web_pgid" | tr '\n' ' ')"

              if [ -n "${D}{web_ports// }" ]; then
                if siftalpha_web_probe_ports PROJECT_PID_SCOPE ${D}web_ports; then
                  return 0
                fi
                echo 'SIFTALPHA_WEB_PRIMARY_SCOPE=NO_HTTP_ENDPOINT'
              else
                echo 'SIFTALPHA_WEB_PRIMARY_SCOPE=NO_LISTEN_PORT'
              fi

              if siftalpha_web_guest_fallback "${D}web_root_pid" "${D}web_pgid"; then
                return 0
              fi

              if [ -n "${D}{web_ports// }" ]; then
                echo 'SIFTALPHA_WEB_AUTODISCOVERY=NO_HTTP_ENDPOINT source=PROJECT_AND_PROOT_PID_SCOPE'
              else
                echo 'SIFTALPHA_WEB_AUTODISCOVERY=NO_LISTEN_PORT source=PROJECT_AND_PROOT_PID_SCOPE'
              fi
              return 0
            }

            siftalpha_web_autodiscover "${D}pid" "${D}pgid" || true
        """.trimIndent()
    }

    private fun guestDiscoveryScript(preferred: String): String = """
        web_root_pid="${D}1"
        web_pgid="${D}2"

        guest_descendants() {
          guest_parent="${D}1"
          guest_frontier="${D}guest_parent"
          guest_seen=" ${D}guest_parent "
          while [ -n "${D}{guest_frontier// }" ]; do
            guest_next=''
            for guest_pid in ${D}guest_frontier; do
              guest_children="${D}(cat "/proc/${D}guest_pid/task/${D}guest_pid/children" 2>/dev/null || true)"
              for guest_child in ${D}guest_children; do
                case "${D}guest_seen" in
                  *" ${D}guest_child "*) ;;
                  *)
                    guest_seen="${D}guest_seen${D}guest_child "
                    guest_next="${D}guest_next ${D}guest_child"
                    printf '%s\n' "${D}guest_child"
                    ;;
                esac
              done
            done
            guest_frontier="${D}guest_next"
          done
        }

        guest_pids="${D}(
          {
            [ -n "${D}web_root_pid" ] && printf '%s\n' "${D}web_root_pid"
            [ -n "${D}web_root_pid" ] && guest_descendants "${D}web_root_pid"
            if [ -n "${D}web_pgid" ] && command -v ps >/dev/null 2>&1; then
              ps -eo pid=,pgid= 2>/dev/null | awk -v g="${D}web_pgid" '${D}2 == g { print ${D}1 }' || true
            fi
          } | awk 'NF && !seen[${D}1]++' | tr '\n' ' '
        )"
        if [ -z "${D}{guest_pids// }" ]; then
          echo 'SIFTALPHA_WEB_GUEST_SCOPE=NO_PROJECT_PIDS'
          exit 0
        fi

        guest_inodes="${D}(
          for guest_pid in ${D}guest_pids; do
            [ -d "/proc/${D}guest_pid/fd" ] || continue
            for guest_fd in /proc/${D}guest_pid/fd/*; do
              guest_link="${D}(readlink "${D}guest_fd" 2>/dev/null || true)"
              case "${D}guest_link" in
                socket:\[*\])
                  guest_inode="${D}{guest_link#socket:[}"
                  guest_inode="${D}{guest_inode%]}"
                  case "${D}guest_inode" in
                    ''|*[!0-9]*) ;;
                    *) printf '%s\n' "${D}guest_inode" ;;
                  esac
                  ;;
              esac
            done
          done | awk 'NF && !seen[${D}1]++' | tr '\n' ' '
        )"
        if [ -z "${D}{guest_inodes// }" ]; then
          echo 'SIFTALPHA_WEB_GUEST_SCOPE=NO_SOCKET_INODES'
          exit 0
        fi

        guest_ports="${D}(
          for guest_table in /proc/net/tcp /proc/net/tcp6; do
            [ -r "${D}guest_table" ] || continue
            awk 'NR > 1 && ${D}4 == "0A" { split(${D}2, a, ":"); print a[2], ${D}10 }' "${D}guest_table" 2>/dev/null || true
          done | while read -r guest_hex_port guest_inode; do
            case " ${D}guest_inodes " in
              *" ${D}guest_inode "*)
                guest_port="${D}((16#${D}guest_hex_port))"
                [ "${D}guest_port" -ge 1 ] 2>/dev/null && [ "${D}guest_port" -le 65535 ] 2>/dev/null && printf '%s\n' "${D}guest_port"
                ;;
            esac
          done | sort -n -u | tr '\n' ' '
        )"
        if [ -z "${D}{guest_ports// }" ]; then
          echo 'SIFTALPHA_WEB_GUEST_SCOPE=NO_LISTEN_PORT'
          exit 0
        fi

        guest_ordered=''
        for guest_preferred in $preferred; do
          case " ${D}guest_ports " in
            *" ${D}guest_preferred "*) guest_ordered="${D}guest_ordered ${D}guest_preferred" ;;
          esac
        done
        for guest_port in ${D}guest_ports; do
          case " ${D}guest_ordered " in
            *" ${D}guest_port "*) ;;
            *) guest_ordered="${D}guest_ordered ${D}guest_port" ;;
          esac
        done

        guest_checked=0
        for guest_port in ${D}guest_ordered; do
          if [ "${D}guest_checked" -ge $MAX_RUNTIME_CANDIDATES ]; then
            echo 'SIFTALPHA_WEB_RUNTIME_CANDIDATE_LIMIT=REACHED source=PROOT_PROJECT_PID_SCOPE'
            break
          fi
          guest_checked="${D}((guest_checked + 1))"
          printf 'SIFTALPHA_WEB_PORT_CANDIDATE=%s source=PROOT_PROJECT_PID_SCOPE\n' "${D}guest_port"
          guest_first_line="${D}(timeout 1 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/${D}1" || exit 1; printf "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n" >&3; IFS= read -r line <&3 || true; printf "%s" "${D}line"' _ "${D}guest_port" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
          case "${D}guest_first_line" in
            HTTP/*)
              printf 'SIFTALPHA_WEB_AUTODISCOVERY=PASS source=PROOT_PROJECT_PID_SCOPE port=%s\n' "${D}guest_port"
              printf 'SIFTALPHA_WEB_URL=http://127.0.0.1:%s\n' "${D}guest_port"
              exit 0
              ;;
          esac
        done
        echo 'SIFTALPHA_WEB_GUEST_SCOPE=NO_HTTP_ENDPOINT'
    """.trimIndent()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
