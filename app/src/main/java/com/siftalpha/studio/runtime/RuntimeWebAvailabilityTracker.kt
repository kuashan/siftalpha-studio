package com.siftalpha.studio.runtime

import android.os.Handler
import android.os.Looper

/**
 * Activity-lifetime cache and scheduler for loopback Web listener checks.
 *
 * A confirmed result is revalidated in the background on a short cadence. The last result stays
 * visible while that recheck is in flight, avoiding Browser-button flicker; a failed recheck then
 * disables the entry immediately. Results are dropped whenever the Activity leaves the foreground,
 * and a lifecycle generation rejects any probe that started before that pause.
 */
class RuntimeWebAvailabilityTracker(
    private val probe: (String) -> Boolean = { RuntimeWebEndpointProbe.isListening(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onChanged: () -> Unit,
) {

    private data class ProbeResult(
        val reachable: Boolean,
        val checkedAtEpochMs: Long,
        val generation: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val results = mutableMapOf<String, ProbeResult>()
    private val inFlight = mutableSetOf<String>()
    private val scheduledVersions = mutableMapOf<String, Long>()
    private val generations = mutableMapOf<String, Int>()
    private var lifecycleGeneration = 0
    private var active = false

    fun resume() {
        active = true
    }

    fun pause() {
        active = false
        lifecycleGeneration += 1
        handler.removeCallbacksAndMessages(null)
        scheduledVersions.clear()
        results.clear()
        inFlight.clear()
    }

    fun invalidate(projectKey: String) {
        generations[projectKey] = generation(projectKey) + 1
        val prefix = keyPrefix(projectKey)
        results.keys.removeAll { it.startsWith(prefix) }
        inFlight.removeAll { it.startsWith(prefix) }
        scheduledVersions.keys.removeAll { it.startsWith(prefix) }
    }

    fun reachableUrl(
        projectKey: String,
        runtimeState: RuntimeState,
        candidateUrls: List<String>,
    ): String? {
        if (runtimeState != RuntimeState.RUNNING || candidateUrls.isEmpty()) return null
        val now = clock()
        val urls = candidateUrls.distinct()
        urls.forEach { url -> ensureProbe(projectKey, url, now) }
        return urls.firstOrNull { url ->
            val result = results[key(projectKey, url)] ?: return@firstOrNull false
            result.generation == generation(projectKey) && result.reachable
        }
    }

    fun verifyNow(projectKey: String, url: String, callback: (Boolean) -> Unit) {
        val generation = generation(projectKey)
        val lifecycle = lifecycleGeneration
        Thread {
            val reachable = probe(url)
            val checkedAt = clock()
            handler.post {
                if (!active || lifecycle != lifecycleGeneration) return@post
                if (generation != generation(projectKey)) return@post
                val key = key(projectKey, url)
                val previous = results[key]
                results[key] = ProbeResult(reachable, checkedAt, generation)
                scheduleRecheck(projectKey, url, checkedAt, RECHECK_INTERVAL_MS)
                if (previous?.reachable != reachable) onChanged()
                callback(reachable)
            }
        }.start()
    }

    private fun ensureProbe(projectKey: String, url: String, now: Long) {
        if (!active) return
        val key = key(projectKey, url)
        val generation = generation(projectKey)
        val lifecycle = lifecycleGeneration
        val cached = results[key]
        if (cached != null && cached.generation == generation) {
            val age = (now - cached.checkedAtEpochMs).coerceAtLeast(0L)
            if (age < RECHECK_INTERVAL_MS) {
                scheduleRecheck(projectKey, url, cached.checkedAtEpochMs, RECHECK_INTERVAL_MS - age)
                return
            }
        }
        if (!inFlight.add(key)) return

        Thread {
            val reachable = probe(url)
            val checkedAt = clock()
            handler.post {
                if (!active || lifecycle != lifecycleGeneration) return@post
                if (generation != generation(projectKey)) return@post
                inFlight.remove(key)
                val previous = results[key]
                results[key] = ProbeResult(reachable, checkedAt, generation)
                scheduleRecheck(projectKey, url, checkedAt, RECHECK_INTERVAL_MS)
                if (previous?.reachable != reachable) onChanged()
            }
        }.start()
    }

    private fun scheduleRecheck(
        projectKey: String,
        url: String,
        checkedAt: Long,
        delayMs: Long,
    ) {
        if (!active) return
        val key = key(projectKey, url)
        if (scheduledVersions[key] == checkedAt) return
        scheduledVersions[key] = checkedAt
        handler.postDelayed(
            {
                if (scheduledVersions[key] != checkedAt) return@postDelayed
                scheduledVersions.remove(key)
                val current = results[key]
                if (
                    active &&
                    current?.checkedAtEpochMs == checkedAt &&
                    current.generation == generation(projectKey)
                ) {
                    ensureProbe(projectKey, url, clock())
                }
            },
            delayMs.coerceAtLeast(1L),
        )
    }

    private fun generation(projectKey: String): Int = generations[projectKey] ?: 0

    private fun keyPrefix(projectKey: String): String = "${projectKey.length}:$projectKey:"

    private fun key(projectKey: String, url: String): String = "${keyPrefix(projectKey)}$url"

    companion object {
        private const val RECHECK_INTERVAL_MS = 2_000L
    }
}
