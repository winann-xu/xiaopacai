// [android-tv] 公告共享状态 —— 首页「内嵌公告框」与实时推送/拉取共用一处数据源
//
// 数据来源两条，都只承载**已发布**的公告（服务端 /api/v1/device/announcements 已按
// status=published + 目标设备 + 有效期内 过滤，撤回/删除的不会返回）：
//   1) HTTPS 拉取：CloudSyncServiceTV.refreshAnnouncementsIntoStore()（首页每 30s 刷新一次）
//   2) P2P 实时推送：announcement_push（新增/更新） / announcement_clear（撤回或删除）
package com.xiaopacai.tvos.ui.announcement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object AnnouncementStore {

    data class Item(
        val id: Int,
        val title: String,
        val content: String,
        /** normal | important | urgent */
        val priority: String,
        val publishedAt: Long?,
    ) {
        val isImportant: Boolean get() = priority != "normal"
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** 当前应展示的已发布公告（撤回/删除后会被移除） */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** 服务端列表整体替换（首页/心跳拉取） */
    fun replaceFromServer(arr: JSONArray) {
        _items.value = parse(arr)
    }

    /** P2P 实时推送：按 id 合并更新（新的在前），不动其它已发布公告 */
    fun upsertFromPush(arr: JSONArray) {
        val incoming = parse(arr)
        if (incoming.isEmpty()) return
        val merged = LinkedHashMap<Int, Item>()
        incoming.forEach { merged[it.id] = it }
        _items.value.forEach { if (!merged.containsKey(it.id)) merged[it.id] = it }
        _items.value = merged.values.sortedByDescending { it.publishedAt ?: 0L }
    }

    /** 撤回/删除：移除指定 id；未带 id 时清空（下一次拉取会回填仍在发布的） */
    fun remove(ids: Set<Int>) {
        _items.value = if (ids.isEmpty()) emptyList()
        else _items.value.filterNot { it.id in ids }
    }

    private fun parse(arr: JSONArray): List<Item> {
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString()?.toIntOrNull() ?: continue
            out.add(
                Item(
                    id = id,
                    title = o.optString("title", "").ifBlank { "公告" },
                    content = o.optString("content", ""),
                    priority = normalizePriority(o.opt("priority")),
                    // HTTP 列表用 publishedAt；P2P 推送只有 created_at → 兜底
                    publishedAt = (o.optLong("publishedAt", 0L).takeIf { it > 0L }
                        ?: o.optLong("created_at", 0L).takeIf { it > 0L }),
                )
            )
        }
        return out.sortedByDescending { it.publishedAt ?: 0L }
    }

    /** priority 在设备 HTTP 通道是字符串，在 P2P 推送里是数字（0/1/2） */
    private fun normalizePriority(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "normal"
        is Number -> when (value.toInt()) {
            2 -> "urgent"
            1 -> "important"
            else -> "normal"
        }
        else -> value.toString().lowercase().ifBlank { "normal" }
    }
}
