package com.xiaopacai.child.model

data class DiagnosticRecord(
    val id: Long = 0,
    val checkKey: String,
    val title: String,
    val description: String,
    val ready: Boolean,
    val checkedAt: Long = System.currentTimeMillis() / 1000
)
