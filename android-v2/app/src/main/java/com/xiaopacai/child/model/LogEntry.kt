package com.xiaopacai.child.model

data class LogEntry(
    val ts: Long,
    val level: String,
    val tag: String,
    val msg: String
)
