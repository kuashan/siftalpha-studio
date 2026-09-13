package com.siftalpha.studio.runtime

import java.util.concurrent.CopyOnWriteArraySet

/**
 * 进程内 Runtime 结果总线。
 *
 * v0.4 增加一个小型最近结果缓存：项目依赖安装可能持续较久，用户短暂切到
 * 其他 APP 后 Activity 会进入 onStop。结果在这段时间返回时不会立刻丢失，
 * 回到 SiftAlpha Studio 后可以按 executionId 消费。
 */
object TermuxResultBus {
    private val listeners = CopyOnWriteArraySet<(RuntimeResult) -> Unit>()
    private val recent = LinkedHashMap<Int, RuntimeResult>()
    private val recentLock = Any()

    fun addListener(listener: (RuntimeResult) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (RuntimeResult) -> Unit) {
        listeners -= listener
    }

    fun publish(result: RuntimeResult) {
        synchronized(recentLock) {
            recent[result.executionId] = result
            while (recent.size > MAX_RECENT_RESULTS) {
                val first = recent.entries.firstOrNull()?.key ?: break
                recent.remove(first)
            }
        }
        listeners.forEach { listener ->
            runCatching { listener(result) }
        }
    }

    fun consume(executionId: Int): RuntimeResult? =
        synchronized(recentLock) { recent.remove(executionId) }

    private const val MAX_RECENT_RESULTS = 32
}
