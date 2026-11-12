package com.bigtree.insight

import java.util.concurrent.atomic.AtomicBoolean

class InsightSpan internal constructor(
    private val name: String,
    private val beganElapsedMs: Long,
) : AutoCloseable {
    private val extras = LinkedHashMap<String, String>()
    private val ended = AtomicBoolean(false)

    fun put(key: String, value: String): InsightSpan {
        extras[key] = value
        return this
    }

    fun end() {
        if (!ended.compareAndSet(false, true)) {
            return
        }
        Insight.finishSpan(name, beganElapsedMs, LinkedHashMap(extras))
    }

    override fun close() {
        end()
    }
}
