package com.bigtree.insight

class InsightConfig internal constructor(
    val endpoint: String?,
    val debug: Boolean,
    val debugSpecified: Boolean,
    val slowTaskMs: Long,
    val blockThresholdMs: Long,
) {
    class Builder {
        private var endpoint: String? = null
        private var debug: Boolean = false
        private var debugSpecified: Boolean = false
        private var slowTaskMs: Long = 100
        private var blockThresholdMs: Long = 200

        fun endpoint(value: String): Builder = apply { endpoint = value }

        fun debug(value: Boolean): Builder = apply {
            debug = value
            debugSpecified = true
        }

        /** 单次任务超过这个毫秒数时，日志里会标出 slow。 */
        fun slowTaskMs(value: Long): Builder = apply { slowTaskMs = value }

        /** 主线程超过这个毫秒数没有处理消息时，抓一次主线程栈。传 0 关闭。 */
        fun blockThresholdMs(value: Long): Builder = apply { blockThresholdMs = value }

        fun build(): InsightConfig = InsightConfig(
            endpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() },
            debug = debug,
            debugSpecified = debugSpecified,
            slowTaskMs = slowTaskMs.coerceAtLeast(0),
            blockThresholdMs = blockThresholdMs.coerceAtLeast(0),
        )
    }

    internal companion object {
        val NONE = InsightConfig(
            endpoint = null,
            debug = false,
            debugSpecified = false,
            slowTaskMs = 100,
            blockThresholdMs = 200,
        )
    }
}
