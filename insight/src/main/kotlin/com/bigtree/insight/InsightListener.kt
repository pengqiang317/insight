package com.bigtree.insight

fun interface InsightListener {
    fun onRecord(type: String, summary: String)
}
